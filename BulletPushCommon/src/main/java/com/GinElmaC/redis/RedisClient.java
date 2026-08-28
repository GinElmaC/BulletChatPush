package com.GinElmaC.redis;

import com.GinElmaC.constant.LinkConfigConstant;
import redis.clients.jedis.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * redis操作客户端
 */
public class RedisClient {
    private static final Integer REDIS_TIMEOUT = 2000;

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
            jedis.set(getServerIDKey(),"0");
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

}
