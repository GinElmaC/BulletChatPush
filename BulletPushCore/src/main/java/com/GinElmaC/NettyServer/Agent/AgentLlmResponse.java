package com.GinElmaC.NettyServer.Agent;

import java.util.List;

/**
 * LLM 的一次响应，可能是最终文本，也可能携带需要执行的工具调用。
 */
public record AgentLlmResponse(String content, List<AgentToolCall> toolCalls) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
