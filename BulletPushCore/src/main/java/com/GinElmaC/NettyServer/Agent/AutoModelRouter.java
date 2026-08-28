package com.GinElmaC.NettyServer.Agent;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 模型路由器。
 * Auto 模式根据 Redis 中的实时指标排序；固定模型模式只尝试用户指定的模型。
 */
public class AutoModelRouter {
    private final AgentModelRegistry modelRegistry = new AgentModelRegistry();
    private final AgentModelMetricsStore metricsStore = new AgentModelMetricsStore();

    /**
     * 选择模型并获取并发租约。
     * 即使本地预读指标显示模型可用，最终仍以 Redis Lua 的原子抢占结果为准。
     */
    public AgentModelLease acquire(String requestedModel) {
        List<AgentModel> candidates = candidates(requestedModel);
        long nowMillis = System.currentTimeMillis();
        for (AgentModel model : candidates) {
            AgentModelRuntime runtime = metricsStore.runtime(model, nowMillis);
            // 本地预读只用于快速跳过明显不可用模型，最终校验由 Redis Lua 原子完成。
            if (runtime.circuitOpen(nowMillis) || runtime.inFlight() >= model.maxConcurrency()) {
                continue;
            }
            String requestId = UUID.randomUUID().toString();
            if (metricsStore.tryAcquire(model, requestId, nowMillis)) {
                return new AgentModelLease(requestId, model, nowMillis);
            }
        }
        throw new IllegalStateException("no available agent model");
    }

    public void complete(AgentModelLease lease, long firstTokenAtMillis) {
        long nowMillis = System.currentTimeMillis();
        try {
            // 未收到内容 Token 时，使用总耗时作为首 Token 延迟的保守值。
            metricsStore.recordSuccess(
                    lease,
                    nowMillis - lease.startedAtMillis(),
                    firstTokenAtMillis == 0 ? nowMillis - lease.startedAtMillis() : firstTokenAtMillis - lease.startedAtMillis()
            );
        } finally {
            metricsStore.release(lease);
        }
    }

    public void fail(AgentModelLease lease) {
        try {
            metricsStore.recordFailure(lease, System.currentTimeMillis() - lease.startedAtMillis());
        } finally {
            metricsStore.release(lease);
        }
    }

    private List<AgentModel> candidates(String requestedModel) {
        if (requestedModel == null || AgentModelRegistry.AUTO.equalsIgnoreCase(requestedModel)) {
            // Auto 模式按负载分从低到高排序，调用方会逐个尝试原子抢占。
            return modelRegistry.enabledModels().stream()
                    .sorted(Comparator.comparingDouble(model -> metricsStore.runtime(model, System.currentTimeMillis()).loadScore(model)))
                    .toList();
        }
        // 固定模型模式不自动降级，避免用户指定模型被静默替换。
        AgentModel model = modelRegistry.findEnabled(requestedModel);
        if (model == null) {
            throw new IllegalArgumentException("agent model is unavailable:" + requestedModel);
        }
        return List.of(model);
    }
}
