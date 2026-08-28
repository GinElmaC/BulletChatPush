package com.GinElmaC.NettyServer.Agent;

/**
 * 单个 LLM 的静态定义。
 * 运行时负载、错误率等动态数据由 AgentModelMetricsStore 统一维护在 Redis。
 */
public record AgentModel(String name, String baseUrl, String apiKey, int maxConcurrency) {
    /**
     * 模型只有具备名称、网关地址和密钥后才允许进入路由候选集。
     */
    public boolean enabled() {
        return hasText(baseUrl) && hasText(apiKey) && hasText(name);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
