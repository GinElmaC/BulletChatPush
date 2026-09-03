package com.GinElmaC.redis;

import com.GinElmaC.constant.LinkConfigConstant;
import redis.clients.jedis.*;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * redis操作客户端
 */
public class RedisClient {
    private static final Integer REDIS_TIMEOUT = 2000;
    private static final String ROOM_NODE_SET_PREFIX = LinkConfigConstant.REDISKEY_PERFER + "Room_Nodes:";
    private static final String ROOM_NODE_LEASE_PREFIX = LinkConfigConstant.REDISKEY_PERFER + "Room_Node_Lease:";
    private static final String NODE_DELIVERY_CHANNEL_PREFIX = LinkConfigConstant.REDISKEY_PERFER + "Node_Delivery:";
    private static final String PUSH_NODE_REGISTRY_KEY = LinkConfigConstant.REDISKEY_PERFER + "Push_Node_Registry";
    private static final String PUSH_NODE_SNAPSHOT_PREFIX = LinkConfigConstant.REDISKEY_PERFER + "Push_Node_Snapshot:";

    private static final JedisPool jedisPool;

    static{
        JedisPoolConfig config = new JedisPoolConfig();
        // 连接池大小 预期QPS * 平均响应时间
        // 1条消息1000个人，就要查1000次，10个群，就是10000次
        config.setMaxTotal(500); // 最大连接数
        config.setMaxIdle(250); // 空闲连接数保持
        config.setMinIdle(50);  // 最小空闲连接数
        config.setMaxWait(Duration.ofMillis(500)); // 获取连接时的最大等待时间
        config.setTestOnBorrow(true); // 借用连接时进行有效性检查
        //初始化JedisPool
        jedisPool = new JedisPool(config,RedisConfig.REDIS_HOST,RedisConfig.REDIS_PORT,REDIS_TIMEOUT,RedisConfig.REDIS_PASSWORD);
        //初始化redis中服务器id
        try(Jedis jedis = jedisPool.getResource()){
            // 只在首次启动时初始化计数器，禁止后续节点将集群机器 ID 计数重置为 0。
            jedis.setnx(getServerIDKey(),"0");
        }
    }

    /**
     * 获取当前机器的serverid
     * @return
     */
    public static Integer initRedisServerId(){
        try(Jedis jedis = jedisPool.getResource()){
            Long incr = jedis.incr(getServerIDKey());
            return incr.intValue();
        }
    }

    /**
     * 获取redis的key
     * @return
     */
    public static String getServerIDKey(){
        return LinkConfigConstant.REDISKEY_SERVERID;
    }

    /**
     * 执行 Redis Lua 脚本。
     * Agent 模型路由通过该接口原子地完成租约回收、熔断校验和并发槽位抢占。
     */
    public static Object eval(String script, List<String> keys, List<String> args) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.eval(script, keys, args);
        }
    }

    /**
     * 读取 Hash 全量字段。
     * 用于读取模型实时并发、熔断状态以及分钟桶统计。
     */
    public static Map<String, String> hgetAll(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hgetAll(key);
        }
    }

    /**
     * 写入 Hash 字段集合。
     * Agent 会话元信息使用 Hash 保存，避免每轮对话重写整个会话 JSON。
     */
    public static void hmset(String key, Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.hmset(key, values);
        }
    }

    /**
     * 读取普通字符串值。
     * Agent scope 索引用于从固定分析范围快速定位当前会话。
     */
    public static String get(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        }
    }

    /**
     * 写入带过期时间的普通字符串值。
     */
    public static void setex(String key, int seconds, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.setex(key, (long) seconds, value);
        }
    }

    /**
     * 向 List 尾部追加元素，保证会话轮次按时间正序读取。
     */
    public static void rpush(String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.rpush(key, value);
        }
    }

    /**
     * 读取 List 指定区间。
     */
    public static List<String> lrange(String key, long start, long end) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.lrange(key, start, end);
        }
    }

    /**
     * 获取 List 当前长度。
     */
    public static long llen(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.llen(key);
        }
    }

    /**
     * 裁剪 List，Agent 会话仅保留最近固定轮数的原始对话。
     */
    public static void ltrim(String key, long start, long end) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.ltrim(key, start, end);
        }
    }

    /**
     * 刷新 key 的存活时间。
     */
    public static void expire(String key, int seconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.expire(key, (long) seconds);
        }
    }

    /**
     * 尝试抢占带过期时间的分布式锁。
     * 返回 true 表示当前调用方取得锁，锁值必须用于后续安全释放。
     */
    public static boolean tryLock(String key, String lockValue, long ttlMillis) {
        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.set(
                    key,
                    lockValue,
                    SetParams.setParams().nx().px(Math.max(ttlMillis, 1L))
            );
            return "OK".equals(result);
        }
    }

    /**
     * 仅释放当前调用方持有的锁，避免锁超时后误删其他请求的新锁。
     */
    public static void unlock(String key, String lockValue) {
        String script = """
                if redis.call('get', KEYS[1]) == ARGV[1] then
                    return redis.call('del', KEYS[1])
                end
                return 0
                """;
        eval(script, List.of(key), List.of(lockValue));
    }

    /**
     * 为本机持有连接的房间注册节点成员并刷新租约。
     * Set 保存候选节点，单独 lease key 用于过滤异常退出节点遗留的成员。
     */
    public static void registerRoomNode(long roomId, int machineId, int leaseSeconds) {
        String roomNodeSetKey = roomNodeSetKey(roomId);
        String member = String.valueOf(machineId);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.sadd(roomNodeSetKey, member);
            jedis.expire(roomNodeSetKey, (long) Math.max(leaseSeconds * 2, leaseSeconds));
            jedis.setex(roomNodeLeaseKey(roomId, machineId), (long) leaseSeconds, "1");
        }
    }

    /**
     * 查询持有指定房间连接的存活节点，同时清理已过期租约对应的集合成员。
     */
    public static Set<Integer> findActiveRoomNodes(long roomId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<Integer> activeNodes = new HashSet<>();
            for (String member : jedis.smembers(roomNodeSetKey(roomId))) {
                try {
                    int machineId = Integer.parseInt(member);
                    if (jedis.exists(roomNodeLeaseKey(roomId, machineId))) {
                        activeNodes.add(machineId);
                    } else {
                        jedis.srem(roomNodeSetKey(roomId), member);
                    }
                } catch (NumberFormatException e) {
                    jedis.srem(roomNodeSetKey(roomId), member);
                }
            }
            return activeNodes;
        }
    }

    /**
     * 本机不再持有该房间连接时删除本机成员与租约，不影响其他节点的房间成员。
     */
    public static void unregisterRoomNode(long roomId, int machineId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.srem(roomNodeSetKey(roomId), String.valueOf(machineId));
            jedis.del(roomNodeLeaseKey(roomId, machineId));
            if (jedis.scard(roomNodeSetKey(roomId)) == 0) {
                jedis.del(roomNodeSetKey(roomId));
            }
        }
    }

    /**
     * 将已由 message_out 消费节点判定目标机器的下行消息发布给指定推送节点。
     */
    public static long publishNodeDelivery(int machineId, String payload) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.publish(nodeDeliveryChannel(machineId), payload);
        }
    }

    /**
     * 阻塞订阅本机专属投递频道。调用方应在线程中执行，并通过 JedisPubSub#unsubscribe 停止。
     */
    public static void subscribeNodeDelivery(int machineId, JedisPubSub subscriber) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.subscribe(subscriber, nodeDeliveryChannel(machineId));
        }
    }

    /**
     * 注册或刷新推送节点快照。
     * registry 保存出现过的 machineId，snapshot 使用 TTL 表达节点存活租约。
     */
    public static void refreshPushNodeSnapshot(int machineId, String snapshotJson, int ttlSeconds) {
        String member = String.valueOf(machineId);
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.sadd(PUSH_NODE_REGISTRY_KEY, member);
            jedis.setex(pushNodeSnapshotKey(machineId), (long) ttlSeconds, snapshotJson);
        }
    }

    /**
     * 查询所有注册过的推送节点快照。
     * 返回 Map 的 key 是 machineId 字符串；value 为空表示快照租约已过期，节点应视为离线。
     */
    public static Map<String, String> listPushNodeSnapshots() {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<String> members = jedis.smembers(PUSH_NODE_REGISTRY_KEY);
            if (members == null || members.isEmpty()) {
                return Map.of();
            }
            Map<String, String> snapshots = new java.util.LinkedHashMap<>();
            for (String member : members) {
                snapshots.put(member, jedis.get(pushNodeSnapshotKey(member)));
            }
            return snapshots;
        }
    }

    /**
     * 节点优雅停止时删除存活租约，但保留 registry，便于管理后台展示离线节点。
     */
    public static void unregisterPushNode(int machineId) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(pushNodeSnapshotKey(machineId));
        }
    }

    private static String roomNodeSetKey(long roomId) {
        return ROOM_NODE_SET_PREFIX + roomId;
    }

    private static String roomNodeLeaseKey(long roomId, int machineId) {
        return ROOM_NODE_LEASE_PREFIX + roomId + ":" + machineId;
    }

    private static String nodeDeliveryChannel(int machineId) {
        return NODE_DELIVERY_CHANNEL_PREFIX + machineId;
    }

    private static String pushNodeSnapshotKey(int machineId) {
        return pushNodeSnapshotKey(String.valueOf(machineId));
    }

    private static String pushNodeSnapshotKey(String machineId) {
        return PUSH_NODE_SNAPSHOT_PREFIX + machineId;
    }
}
