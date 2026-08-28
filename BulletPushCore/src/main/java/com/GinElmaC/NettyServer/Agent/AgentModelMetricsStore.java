package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.redis.RedisClient;

import java.util.List;
import java.util.Map;

/**
 * 模型运行态存储。
 * Redis 负责跨实例共享并发租约、熔断状态和短窗口指标；模型静态配置仍由 AgentModelRegistry 管理。
 */
public class AgentModelMetricsStore {
    // 模型运行态 Redis Key 前缀。
    private static final String KEY_PREFIX = "push:agent:model:";
    // 分钟指标桶存活时间，保留当前及最近两分钟的路由依据。
    private static final int METRIC_BUCKET_TTL_SECONDS = 180;
    // 连续失败达到该次数后，临时熔断模型。
    private static final int CIRCUIT_BREAKER_FAILURE_THRESHOLD = 3;
    // 熔断持续时间。
    private static final long CIRCUIT_BREAKER_OPEN_MILLIS = 30_000;

    /**
     * 原子获取模型并发租约。
     * KEYS[1] 是运行态 Hash，KEYS[2] 是按过期时间排序的租约 ZSet，KEYS[3] 是单请求租约 Key。
     * 先清理异常断开留下的过期租约，再检查熔断和并发上限，最后增加 in_flight 并写入新租约。
     */
    private static final String ACQUIRE_SCRIPT = """
            local expired = redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[1])
            if expired > 0 then
                local current = tonumber(redis.call('HGET', KEYS[1], 'in_flight') or '0')
                redis.call('HSET', KEYS[1], 'in_flight', math.max(0, current - expired))
            end
            local circuitOpenUntil = tonumber(redis.call('HGET', KEYS[1], 'circuit_open_until') or '0')
            if circuitOpenUntil > tonumber(ARGV[1]) then
                return -1
            end
            local inFlight = tonumber(redis.call('HGET', KEYS[1], 'in_flight') or '0')
            if inFlight >= tonumber(ARGV[2]) then
                return 0
            end
            redis.call('HINCRBY', KEYS[1], 'in_flight', 1)
            redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
            redis.call('SET', KEYS[3], ARGV[1], 'EX', ARGV[5])
            return 1
            """;

    /**
     * 原子释放租约。
     * 仅在 ZSet 中成功删除 requestId 时递减 in_flight，保证重复释放不会造成并发计数为负。
     */
    private static final String RELEASE_SCRIPT = """
            local removed = redis.call('ZREM', KEYS[2], ARGV[1])
            if removed == 1 then
                local current = tonumber(redis.call('HGET', KEYS[1], 'in_flight') or '0')
                redis.call('HSET', KEYS[1], 'in_flight', math.max(0, current - 1))
            end
            redis.call('DEL', KEYS[3])
            return removed
            """;

    /**
     * 记录成功请求的分钟桶指标，并重置模型连续失败次数。
     */
    private static final String SUCCESS_SCRIPT = """
            redis.call('HINCRBY', KEYS[1], 'request_count', 1)
            redis.call('HINCRBY', KEYS[1], 'success_count', 1)
            redis.call('HINCRBY', KEYS[1], 'latency_total_ms', ARGV[1])
            redis.call('HINCRBY', KEYS[1], 'ttft_total_ms', ARGV[2])
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            redis.call('HSET', KEYS[2], 'consecutive_failures', 0, 'last_success_at', ARGV[4])
            return 1
            """;

    /**
     * 记录失败请求的分钟桶指标，并在连续失败达到阈值时打开熔断。
     */
    private static final String FAILURE_SCRIPT = """
            redis.call('HINCRBY', KEYS[1], 'request_count', 1)
            redis.call('HINCRBY', KEYS[1], 'error_count', 1)
            redis.call('HINCRBY', KEYS[1], 'latency_total_ms', ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[2])
            local failures = redis.call('HINCRBY', KEYS[2], 'consecutive_failures', 1)
            if failures >= tonumber(ARGV[3]) then
                redis.call('HSET', KEYS[2], 'circuit_open_until', ARGV[4])
            end
            return failures
            """;

    public boolean tryAcquire(AgentModel model, String requestId, long nowMillis) {
        // ZSet score 保存租约过期时间，服务异常退出后由下一次抢占请求负责回收。
        long expireAtMillis = nowMillis + AgentModelConfig.REQUEST_LEASE_SECONDS * 1000L;
        Object result = RedisClient.eval(
                ACQUIRE_SCRIPT,
                List.of(runtimeKey(model), leaseKey(model), requestKey(model, requestId)),
                List.of(
                        String.valueOf(nowMillis),
                        String.valueOf(model.maxConcurrency()),
                        String.valueOf(expireAtMillis),
                        requestId,
                        String.valueOf(AgentModelConfig.REQUEST_LEASE_SECONDS)
                )
        );
        return result instanceof Long && (Long) result == 1L;
    }

    public void release(AgentModelLease lease) {
        // ZREM 幂等：重复的完成或失败回调不会重复减少 in_flight。
        RedisClient.eval(
                RELEASE_SCRIPT,
                List.of(runtimeKey(lease.model()), leaseKey(lease.model()), requestKey(lease.model(), lease.requestId())),
                List.of(lease.requestId())
        );
    }

    public void recordSuccess(AgentModelLease lease, long durationMs, long ttftMs) {
        // 成功指标写入当前分钟桶，同时清空连续失败计数。
        long nowMillis = System.currentTimeMillis();
        RedisClient.eval(
                SUCCESS_SCRIPT,
                List.of(metricBucketKey(lease.model(), nowMillis), runtimeKey(lease.model())),
                List.of(
                        String.valueOf(Math.max(0, durationMs)),
                        String.valueOf(Math.max(0, ttftMs)),
                        String.valueOf(METRIC_BUCKET_TTL_SECONDS),
                        String.valueOf(nowMillis)
                )
        );
    }

    public void recordFailure(AgentModelLease lease, long durationMs) {
        // 失败指标同样写入分钟桶；达到连续失败阈值后设置熔断截止时间。
        long nowMillis = System.currentTimeMillis();
        RedisClient.eval(
                FAILURE_SCRIPT,
                List.of(metricBucketKey(lease.model(), nowMillis), runtimeKey(lease.model())),
                List.of(
                        String.valueOf(Math.max(0, durationMs)),
                        String.valueOf(METRIC_BUCKET_TTL_SECONDS),
                        String.valueOf(CIRCUIT_BREAKER_FAILURE_THRESHOLD),
                        String.valueOf(nowMillis + CIRCUIT_BREAKER_OPEN_MILLIS)
                )
        );
    }

    public AgentModelRuntime runtime(AgentModel model, long nowMillis) {
        Map<String, String> runtime = RedisClient.hgetAll(runtimeKey(model));
        long requestCount = 0;
        long errorCount = 0;
        long latencyTotal = 0;
        long ttftTotal = 0;
        // 使用最近三分钟滑动窗口，避免久远失败影响当前模型选择。
        for (long minute = nowMillis / 60_000 - 2; minute <= nowMillis / 60_000; minute++) {
            Map<String, String> bucket = RedisClient.hgetAll(metricBucketKeyForMinute(model, minute));
            requestCount += number(bucket, "request_count");
            errorCount += number(bucket, "error_count");
            latencyTotal += number(bucket, "latency_total_ms");
            ttftTotal += number(bucket, "ttft_total_ms");
        }
        return new AgentModelRuntime(
                (int) number(runtime, "in_flight"),
                requestCount,
                errorCount,
                requestCount == 0 ? 0 : latencyTotal / (double) requestCount,
                requestCount == 0 ? 0 : ttftTotal / (double) requestCount,
                number(runtime, "circuit_open_until")
        );
    }

    private String runtimeKey(AgentModel model) {
        // Hash：保存实时并发、连续失败次数和熔断截止时间。
        return KEY_PREFIX + model.name() + ":runtime";
    }

    private String leaseKey(AgentModel model) {
        // ZSet：member 为 requestId，score 为租约过期时间。
        return KEY_PREFIX + model.name() + ":leases";
    }

    private String requestKey(AgentModel model, String requestId) {
        // 单请求 Key 仅用于设置 TTL 和排查，不作为并发计数的唯一依据。
        return KEY_PREFIX + model.name() + ":request:" + requestId;
    }

    private String metricBucketKey(AgentModel model, long nowMillis) {
        // 按分钟切桶，读取时聚合最近三个桶。
        return metricBucketKeyForMinute(model, nowMillis / 60_000);
    }

    private String metricBucketKeyForMinute(AgentModel model, long minute) {
        return KEY_PREFIX + model.name() + ":metric:" + minute;
    }

    private long number(Map<String, String> values, String field) {
        try {
            return Long.parseLong(values.getOrDefault(field, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
