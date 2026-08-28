package com.GinElmaC.NettyServer.Agent;

import java.util.List;

/**
 * OpenAI-compatible 对话消息。
 * assistantToolCalls 用于将模型的工具请求原样带入下一轮对话，toolCallId 用于回填工具结果。
 */
public record AgentChatMessage(
        String role,
        String content,
        List<AgentToolCall> assistantToolCalls,
        String toolCallId
) {
    public static AgentChatMessage system(String content) {
        return new AgentChatMessage("system", content, List.of(), null);
    }

    public static AgentChatMessage user(String content) {
        return new AgentChatMessage("user", content, List.of(), null);
    }

    public static AgentChatMessage assistant(String content, List<AgentToolCall> toolCalls) {
        return new AgentChatMessage("assistant", content, toolCalls, null);
    }

    public static AgentChatMessage tool(String toolCallId, String content) {
        return new AgentChatMessage("tool", content, List.of(), toolCallId);
    }
}
