package com.GinElmaC.NettyServer.Agent;

import java.util.List;

/**
 * 从 Redis 还原出的 Agent 会话快照。
 * summary 保存被压缩的旧轮次，turns 仅保存最近固定数量的原始对话。
 */
public record AgentConversationSession(
        String conversationId,
        long uid,
        String scopeType,
        List<Integer> machineIds,
        String logId,
        String requestedModel,
        String summary,
        int turnCount,
        long createdAtMillis,
        long updatedAtMillis,
        List<AgentConversationTurn> turns
) {
    public AgentConversationSession {
        machineIds = machineIds == null ? List.of() : List.copyOf(machineIds);
        turns = turns == null ? List.of() : List.copyOf(turns);
    }
}
