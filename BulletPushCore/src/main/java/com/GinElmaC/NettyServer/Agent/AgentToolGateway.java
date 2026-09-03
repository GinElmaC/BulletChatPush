package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Agent 工具调用的统一执行入口。
 * 注册表只负责找到工具，参数解析、失败关闭和结果裁剪集中放在这里。
 */
public class AgentToolGateway {
    private static final int DEFAULT_RESULT_MAX_LENGTH = 16_000;
    private static final Log log = LogFactory.getLog(AgentToolGateway.class);

    public String invoke(AgentTool tool, AgentToolCall toolCall, AgentToolRequest request) {
        JsonObject arguments = parseArguments(toolCall);
        AgentTraceContext traceContext = request == null ? null : request.traceContext();
        long startedAtMillis = System.currentTimeMillis();
        logToolStarted(traceContext, toolCall, arguments);
        try {
            String result = tool.execute(arguments, request);
            String truncatedResult = truncate(result == null ? "" : result, resultMaxLength());
            logToolCompleted(traceContext, toolCall, truncatedResult, startedAtMillis);
            return truncatedResult;
        } catch (Exception e) {
            logToolFailed(traceContext, toolCall, startedAtMillis, e);
            throw e;
        }
    }

    private void logToolStarted(
            AgentTraceContext traceContext,
            AgentToolCall toolCall,
            JsonObject arguments
    ) {
        if (traceContext == null) {
            return;
        }
        LogContext context = traceContext.logContext()
                .put("toolName", toolCall == null ? null : toolCall.name())
                .put("toolCallId", toolCall == null ? null : toolCall.id());
        traceContext.putPayload(context, "toolArguments", arguments == null ? null : arguments.toString());
        log.Info(context, "AGENT_TOOL_CALL_STARTED");
    }

    private void logToolCompleted(
            AgentTraceContext traceContext,
            AgentToolCall toolCall,
            String result,
            long startedAtMillis
    ) {
        if (traceContext == null) {
            return;
        }
        LogContext context = traceContext.logContext()
                .put("toolName", toolCall == null ? null : toolCall.name())
                .put("toolCallId", toolCall == null ? null : toolCall.id())
                .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis);
        traceContext.putPayload(context, "toolResult", result);
        log.Info(context, "AGENT_TOOL_CALL_COMPLETED");
    }

    private void logToolFailed(
            AgentTraceContext traceContext,
            AgentToolCall toolCall,
            long startedAtMillis,
            Exception error
    ) {
        if (traceContext == null) {
            return;
        }
        log.Error(traceContext.logContext()
                        .put("toolName", toolCall == null ? null : toolCall.name())
                        .put("toolCallId", toolCall == null ? null : toolCall.id())
                        .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis)
                        .put("errorType", error.getClass().getSimpleName()),
                "AGENT_TOOL_CALL_FAILED",
                error);
    }

    private JsonObject parseArguments(AgentToolCall toolCall) {
        if (toolCall == null || toolCall.argumentsJson() == null || toolCall.argumentsJson().isBlank()) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(toolCall.argumentsJson()).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("tool arguments must be json object", e);
        }
    }

    private int resultMaxLength() {
        return Math.max(1_000, AgentMcpConfig.RESULT_MAX_LENGTH <= 0
                ? DEFAULT_RESULT_MAX_LENGTH
                : AgentMcpConfig.RESULT_MAX_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }
}
