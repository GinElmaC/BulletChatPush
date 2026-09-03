package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.LogLevel;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.log.PushLogRecord;
import com.GinElmaC.log.PushLogRepository;
import com.GinElmaC.log.PushLogSearchParam;
import com.GinElmaC.utils.JsonUtil;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 按 LogID 查询 push_log 的唯一工具。
 * 工具参数中的 logId 只用于校验，实际查询始终使用 AgentToolRequest 固定的请求 LogID。
 */
public class QueryPushLogsByLogIdTool implements AgentTool {
    public static final String NAME = "query_push_logs_by_log_id";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 100;
    private static final Log log = LogFactory.getLog(QueryPushLogsByLogIdTool.class);
    private final PushLogRepository pushLogRepository = new PushLogRepository();

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                NAME,
                "根据日志链路 LogID 查询 push_log 中的完整消息链路日志。",
                """
                        {
                          "type":"object",
                          "properties":{
                            "logId":{"type":"string","description":"需要分析的唯一日志链路ID"},
                            "level":{"type":"string","enum":["INFO","WARN","ERROR"],"description":"可选日志等级"},
                            "keyword":{"type":"string","description":"可选消息关键词"},
                            "limit":{"type":"integer","minimum":1,"maximum":100,"description":"返回日志条数"}
                          },
                          "required":["logId"]
                        }
                        """
        );
    }

    @Override
    public String execute(JsonObject arguments, AgentToolRequest request) {
        validateLogId(arguments, request.requestedLogId());
        PushLogSearchParam param = new PushLogSearchParam()
                .setLogId(request.requestedLogId())
                .setLevel(readLevel(arguments))
                .setKeyword(readText(arguments, "keyword"))
                .setLimit(readLimit(arguments))
                .setOffset(0);
        AgentTraceContext traceContext = request.traceContext();
        long startedAtMillis = System.currentTimeMillis();
        logQueryStarted(traceContext, param);
        try {
            List<LogAnalysisRecord> records = pushLogRepository.queryByLogId(param).stream()
                    .map(this::toAnalysisRecord)
                    .toList();
            String result = JsonUtil.toJson(new LogQueryResult(request.requestedLogId(), records));
            logQueryCompleted(traceContext, records.size(), result.length(), startedAtMillis);
            return result;
        } catch (Exception e) {
            logQueryFailed(traceContext, startedAtMillis, e);
            throw e;
        }
    }

    private void logQueryStarted(AgentTraceContext traceContext, PushLogSearchParam param) {
        if (traceContext == null) {
            return;
        }
        LogContext context = traceContext.logContext()
                .put("toolName", NAME)
                .put("queryLogId", param.getLogId())
                .put("queryLevel", param.getLevel() == null ? null : param.getLevel().getName())
                .put("queryLimit", param.getLimit());
        traceContext.putPayload(context, "queryKeyword", param.getKeyword());
        log.Info(context, "AGENT_TOOL_PUSH_LOG_QUERY_STARTED");
    }

    private void logQueryCompleted(
            AgentTraceContext traceContext,
            int recordCount,
            int resultLength,
            long startedAtMillis
    ) {
        if (traceContext == null) {
            return;
        }
        log.Info(traceContext.logContext()
                        .put("toolName", NAME)
                        .put("recordCount", recordCount)
                        .put("resultLength", resultLength)
                        .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis),
                "AGENT_TOOL_PUSH_LOG_QUERY_COMPLETED");
    }

    private void logQueryFailed(AgentTraceContext traceContext, long startedAtMillis, Exception error) {
        if (traceContext == null) {
            return;
        }
        log.Error(traceContext.logContext()
                        .put("toolName", NAME)
                        .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis)
                        .put("errorType", error.getClass().getSimpleName()),
                "AGENT_TOOL_PUSH_LOG_QUERY_FAILED",
                error);
    }

    private void validateLogId(JsonObject arguments, String requestedLogId) {
        String toolLogId = readText(arguments, "logId");
        if (toolLogId == null || !toolLogId.equals(requestedLogId)) {
            throw new IllegalArgumentException("tool logId does not match requested logId");
        }
    }

    private LogLevel readLevel(JsonObject arguments) {
        String level = readText(arguments, "level");
        if (level == null) {
            return null;
        }
        try {
            return LogLevel.valueOf(level);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String readText(JsonObject arguments, String key) {
        if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
            return null;
        }
        String value = arguments.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private int readLimit(JsonObject arguments) {
        if (arguments == null || !arguments.has("limit")) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(arguments.get("limit").getAsInt(), MAX_LIMIT));
        } catch (Exception e) {
            return DEFAULT_LIMIT;
        }
    }

    private LogAnalysisRecord toAnalysisRecord(PushLogRecord record) {
        return new LogAnalysisRecord(
                record.getLogTime() == null ? null : record.getLogTime().toString(),
                record.getLevelName(),
                record.getMachineId(),
                record.getHostIp(),
                record.getLoggerName(),
                record.getSourceFilePath(),
                record.getSourceLine(),
                truncate(record.getMessage(), 2_000),
                truncate(record.getThrowable(), 4_000)
        );
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }

    private record LogQueryResult(String logId, List<LogAnalysisRecord> logs) {
    }

    private record LogAnalysisRecord(
            String logTime,
            String level,
            Integer machineId,
            String hostIp,
            String logger,
            String sourceFilePath,
            Integer sourceLine,
            String message,
            String throwable
    ) {
    }
}
