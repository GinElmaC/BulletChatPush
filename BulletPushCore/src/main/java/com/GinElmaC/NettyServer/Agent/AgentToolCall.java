package com.GinElmaC.NettyServer.Agent;

/**
 * 模型在 Chat Completions 响应中请求执行的工具调用。
 */
public record AgentToolCall(String id, String name, String argumentsJson) {
}
