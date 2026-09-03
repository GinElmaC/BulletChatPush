package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * BulletChatPush 轻量 Agent Runner。
 * 仿照 ADK Runner 的事件流思想：业务层不直接判断某个 token 是否结束，而是由 Runner 收敛模型轮次和工具轮次。
 */
public class BulletAgentRunner {
    private static final Log log = LogFactory.getLog(BulletAgentRunner.class);
    private static final int MAX_TOOL_TURNS = 5;

    private final LlmAnalyzeClient llmAnalyzeClient;
    private final AgentToolRegistry toolRegistry;

    public BulletAgentRunner(LlmAnalyzeClient llmAnalyzeClient, AgentToolRegistry toolRegistry) {
        this.llmAnalyzeClient = llmAnalyzeClient;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 执行一次流式 Agent 对话。
     * 每一轮模型 SSE 结束后才判断是否存在工具调用；存在工具调用时先执行工具并继续下一轮。
     */
    public String runStream(
            AgentModel model,
            List<AgentChatMessage> originalMessages,
            List<AgentToolDefinition> tools,
            AgentToolRequest toolRequest,
            AgentTraceContext traceContext,
            Consumer<String> finalChunkConsumer
    ) {
        List<AgentChatMessage> messages = new ArrayList<>(originalMessages);
        List<AgentToolDefinition> safeTools = tools == null ? List.of() : tools;
        for (int turn = 1; turn <= MAX_TOOL_TURNS; turn++) {
            StringBuilder turnContent = new StringBuilder();
            boolean toolDecisionTurn = !safeTools.isEmpty();
            AgentLlmResponse response = llmAnalyzeClient.chatStreamWithTools(
                    model,
                    messages,
                    safeTools,
                    traceContext,
                    chunk -> {
                        turnContent.append(chunk);
                        // 工具决策轮的内容可能是函数调用协议，不能作为最终答案展示给用户。
                        if (!toolDecisionTurn) {
                            finalChunkConsumer.accept(chunk);
                        }
                    }
            );
            if (response.hasToolCalls()) {
                log.Info(traceContext.logContext()
                                .put("agentTurn", turn)
                                .put("toolCallCount", response.toolCalls().size()),
                        "AGENT_RUNNER_TOOL_TURN");
                messages.add(AgentChatMessage.assistant(response.content(), response.toolCalls()));
                for (AgentToolCall toolCall : response.toolCalls()) {
                    executeTool(messages, toolCall, toolRequest, traceContext);
                }
                // 首轮工具调用已完成，下一轮只请求最终分析结论，避免 tool_choice=required 造成重复调用。
                safeTools = List.of();
                continue;
            }
            String finalContent = response.content();
            if (toolDecisionTurn && hasText(finalContent)) {
                finalChunkConsumer.accept(finalContent);
            }
            log.Info(traceContext.logContext()
                            .put("agentTurn", turn)
                            .put("replyLength", finalContent == null ? 0 : finalContent.length()),
                    "AGENT_RUNNER_COMPLETED");
            return finalContent == null ? "" : finalContent;
        }
        throw new IllegalStateException("agent tool turns exceed limit");
    }

    private void executeTool(
            List<AgentChatMessage> messages,
            AgentToolCall toolCall,
            AgentToolRequest toolRequest,
            AgentTraceContext traceContext
    ) {
        long startedAtMillis = System.currentTimeMillis();
        log.Info(traceContext.logContext()
                        .put("toolCallId", toolCall.id())
                        .put("toolName", toolCall.name()),
                "AGENT_RUNNER_TOOL_STARTED");
        try {
            String result = toolRegistry.execute(toolCall, toolRequest);
            log.Info(traceContext.logContext()
                            .put("toolCallId", toolCall.id())
                            .put("toolName", toolCall.name())
                            .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis)
                            .put("toolResultLength", result == null ? 0 : result.length()),
                    "AGENT_RUNNER_TOOL_COMPLETED");
            messages.add(AgentChatMessage.tool(toolCall.id(), result));
        } catch (Exception e) {
            log.Error(traceContext.logContext()
                            .put("toolCallId", toolCall.id())
                            .put("toolName", toolCall.name())
                            .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis)
                            .put("errorType", e.getClass().getSimpleName()),
                    "AGENT_RUNNER_TOOL_FAILED",
                    e);
            throw e;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
