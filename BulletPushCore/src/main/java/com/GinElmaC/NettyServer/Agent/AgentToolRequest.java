package com.GinElmaC.NettyServer.Agent;

/**
 * Agent 在一次工具调用循环内固定的安全上下文。
 * requestedLogId 来自前端请求，工具不得被模型参数替换为其他 LogID。
 * traceContext 用于将工具网关与工具内部日志串到同一次 Agent 请求。
 */
public record AgentToolRequest(String requestedLogId, AgentTraceContext traceContext) {
    public AgentToolRequest(String requestedLogId) {
        this(requestedLogId, null);
    }
}
