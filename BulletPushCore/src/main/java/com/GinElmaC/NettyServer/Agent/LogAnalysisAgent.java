package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * LogID 日志分析 Agent。
 * 模型第一轮只能通过工具查询日志，工具结果回填后第二轮才允许生成分析结论。
 */
public class LogAnalysisAgent {
    private static final String SYSTEM_PROMPT = """
            你是推送中台日志分析助手。
            用户只提供 LogID 时，必须先调用 query_push_logs_by_log_id 查询真实日志。
            如果可用工具列表中存在 MCP 工具，只能在查询真实日志后将其作为补充诊断能力使用。
            禁止在未调用工具或工具结果为空时推断日志内容和故障原因。
            查询结果返回后，分析日志链路中的异常、失败位置、影响范围和建议操作。
            不得输出 SQL、数据库表名、密钥、令牌或未在工具结果中出现的信息。
            输出中文，面向后端研发和运维人员。
            """;

    private static final Log log = LogFactory.getLog(LogAnalysisAgent.class);
    private final LlmAnalyzeClient llmAnalyzeClient = new LlmAnalyzeClient();
    private final AutoModelRouter autoModelRouter = new AutoModelRouter();
    private final AgentToolRegistry toolRegistry = new AgentToolRegistry();

    /**
     * 使用 Auto 路由按 LogID 查询日志并生成分析。
     */
    public LogAnalysisResult analyze(String logId) {
        return analyze(logId, AgentModelRegistry.AUTO);
    }

    /**
     * 处理一次完整的“模型请求工具 -> 执行工具 -> 模型分析”循环。
     */
    public LogAnalysisResult analyze(String logId, String requestedModel) {
        if (!hasText(logId)) {
            return LogAnalysisResult.serviceAnalyzeFailed();
        }
        AgentTraceContext traceContext = AgentTraceContext.forLogAnalysis(logId.trim());
        log.Info(traceContext.logContext().put("requestedModel", requestedModel),
                "AGENT_LOG_ANALYSIS_STARTED");
        AgentModelLease lease;
        try {
            lease = autoModelRouter.acquire(requestedModel);
        } catch (Exception e) {
            log.Error(traceContext.logContext().put("errorType", e.getClass().getSimpleName()),
                    "AGENT_MODEL_LEASE_FAILED",
                    e);
            return LogAnalysisResult.serviceAnalyzeFailed();
        }
        log.Info(traceContext.logContext().put("modelName", lease.model().name()),
                "AGENT_MODEL_LEASE_ACQUIRED");

        try {
            List<AgentChatMessage> messages = new ArrayList<>();
            messages.add(AgentChatMessage.system(SYSTEM_PROMPT));
            messages.add(AgentChatMessage.user("请分析日志 LogID: " + logId.trim()));

            AgentLlmResponse toolResponse = llmAnalyzeClient.chatWithTools(
                    lease.model(),
                    messages,
                    toolRegistry.definitions(QueryPushLogsByLogIdTool.NAME),
                    traceContext
            );
            if (!toolResponse.hasToolCalls()
                    || !QueryPushLogsByLogIdTool.NAME.equals(toolResponse.toolCalls().get(0).name())) {
                return fail(lease, traceContext);
            }

            messages.add(AgentChatMessage.assistant(toolResponse.content(), toolResponse.toolCalls()));
            for (AgentToolCall toolCall : toolResponse.toolCalls()) {
                log.Info(traceContext.logContext()
                                .put("modelName", lease.model().name())
                                .put("toolName", toolCall.name())
                                .put("toolCallId", toolCall.id()),
                        "AGENT_LOG_ANALYSIS_TOOL_REQUESTED");
                String toolResult = toolRegistry.execute(
                        toolCall,
                        new AgentToolRequest(logId.trim(), traceContext)
                );
                messages.add(AgentChatMessage.tool(toolCall.id(), toolResult));
            }
            AgentLlmResponse finalResponse = llmAnalyzeClient.chatWithTools(
                    lease.model(),
                    messages,
                    List.of(),
                    traceContext
            );
            if (finalResponse.hasToolCalls() || !hasText(finalResponse.content())) {
                return fail(lease, traceContext);
            }

            autoModelRouter.complete(lease, System.currentTimeMillis());
            LogAnalysisResult result = new LogAnalysisResult();
            result.setLogId(logId.trim());
            result.setModelName(lease.model().name());
            result.setAnalysis(finalResponse.content());
            log.Info(traceContext.logContext()
                            .put("modelName", lease.model().name())
                            .put("analysisLength", finalResponse.content().length()),
                    "AGENT_LOG_ANALYSIS_COMPLETED");
            return result;
        } catch (Exception e) {
            log.Error(traceContext.logContext().put("errorType", e.getClass().getSimpleName()),
                    "AGENT_LOG_ANALYSIS_FAILED",
                    e);
            return fail(lease, traceContext);
        }
    }

    /**
     * 工具或模型失败时不向调用方透出日志、模型或异常信息。
     */
    private LogAnalysisResult fail(AgentModelLease lease, AgentTraceContext traceContext) {
        try {
            autoModelRouter.fail(lease);
        } catch (Exception ignored) {
        }
        log.Warn(traceContext.logContext(), "AGENT_LOG_ANALYSIS_RETURNED_FAILED");
        return LogAnalysisResult.serviceAnalyzeFailed();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
