package com.GinElmaC.NettyServer.Agent;

/**
 * Agent 流式分析回调。
 * SSE/WebSocket 接入层应在 onFailed 时清空已渲染的草稿，只展示固定失败文案。
 */
public interface AgentStreamObserver {
    /**
     * 接收模型输出的增量文本。
     */
    void onChunk(String chunk);

    /**
     * 服务侧分析失败，不携带底层异常或分析上下文。
     */
    void onFailed();
}
