package com.GinElmaC.NettyServer.Agent;

/**
 * 从 Redis 聚合出的模型实时运行状态。
 * 该对象只用于一次路由计算，不直接写入 Redis。
 */
public record AgentModelRuntime(
        // 当前未完成的流式请求数。
        int inFlight,
        // 最近统计窗口内的总请求数。
        long requestCount,
        // 最近统计窗口内的失败请求数。
        long errorCount,
        // 最近统计窗口内的平均总耗时。
        double averageLatencyMs,
        // 最近统计窗口内的平均首 Token 延迟。
        double averageTtftMs,
        // 熔断结束时间，毫秒时间戳。
        long circuitOpenUntilMillis
) {
    /**
     * 熔断未结束时，模型不参与本次路由。
     */
    public boolean circuitOpen(long nowMillis) {
        return circuitOpenUntilMillis > nowMillis;
    }

    /**
     * 计算模型负载分数，分数越低优先级越高。
     * 权重依次考虑并发占用、总耗时、错误率和首 Token 延迟。
     */
    public double loadScore(AgentModel model) {
        if (model.maxConcurrency() <= 0) {
            return Double.MAX_VALUE;
        }
        double load = Math.min(1, inFlight / (double) model.maxConcurrency());
        double errorRate = requestCount == 0 ? 0 : errorCount / (double) requestCount;
        double latency = Math.min(1, averageLatencyMs / 5000);
        double ttft = Math.min(1, averageTtftMs / 2000);
        return 0.45 * load + 0.25 * latency + 0.20 * errorRate + 0.10 * ttft;
    }
}
