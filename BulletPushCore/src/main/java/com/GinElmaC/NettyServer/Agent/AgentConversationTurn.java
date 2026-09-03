package com.GinElmaC.NettyServer.Agent;

/**
 * 一轮完整的 Agent 对话。
 * Redis 的 List 以“轮”为粒度保存，长度直接对应最近保留的对话轮数。
 */
public record AgentConversationTurn(
        int turnNo,
        String userMessage,
        String assistantMessage,
        String model,
        long createdAtMillis
) {
}
