package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
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
        AgentModelLease lease;
        try {
            lease = autoModelRouter.acquire(requestedModel);
        } catch (Exception e) {
            return LogAnalysisResult.serviceAnalyzeFailed();
        }

        try {
            List<AgentChatMessage> messages = new ArrayList<>();
            messages.add(AgentChatMessage.system(SYSTEM_PROMPT));
            messages.add(AgentChatMessage.user("请分析日志 LogID: " + logId.trim()));

            AgentLlmResponse toolResponse = llmAnalyzeClient.chatWithTools(
                    lease.model(),
                    messages,
                    toolRegistry.definitions()
            );
            if (!toolResponse.hasToolCalls() || toolResponse.toolCalls().size() != 1
                    || !QueryPushLogsByLogIdTool.NAME.equals(toolResponse.toolCalls().get(0).name())) {
                return fail(lease);
            }

            AgentToolCall toolCall = toolResponse.toolCalls().get(0);
            log.Info(logContext(logId, lease.model().name())
                            .put("toolName", toolCall.name()),
                    "LOG_ANALYSIS_TOOL_CALLED");
            String toolResult = toolRegistry.execute(toolCall, new AgentToolRequest(logId.trim()));
            log.Info(logContext(logId, lease.model().name())
                            .put("toolName", toolCall.name())
                            .put("toolResultLength", toolResult.length()),
                    "LOG_ANALYSIS_TOOL_COMPLETED");

            messages.add(AgentChatMessage.assistant(toolResponse.content(), toolResponse.toolCalls()));
            messages.add(AgentChatMessage.tool(toolCall.id(), toolResult));
            AgentLlmResponse finalResponse = llmAnalyzeClient.chatWithTools(lease.model(), messages, List.of());
            if (finalResponse.hasToolCalls() || !hasText(finalResponse.content())) {
                return fail(lease);
            }

            autoModelRouter.complete(lease, System.currentTimeMillis());
            LogAnalysisResult result = new LogAnalysisResult();
            result.setLogId(logId.trim());
            result.setModelName(lease.model().name());
            result.setAnalysis(finalResponse.content());
            return result;
        } catch (Exception e) {
            return fail(lease);
        }
    }

    /**
     * 工具或模型失败时不向调用方透出日志、模型或异常信息。
     */
    private LogAnalysisResult fail(AgentModelLease lease) {
        try {
            autoModelRouter.fail(lease);
        } catch (Exception ignored) {
        }
        return LogAnalysisResult.serviceAnalyzeFailed();
    }

    private LogContext logContext(String logId, String modelName) {
        return LogContext.create()
                .traceId(logId)
                .put("modelName", modelName);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
