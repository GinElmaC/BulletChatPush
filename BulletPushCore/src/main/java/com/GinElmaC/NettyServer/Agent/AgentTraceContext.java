package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.LogContext;
import com.GinElmaC.utils.JsonUtil;

import java.util.List;
import java.util.UUID;

/**
 * 一次 Agent 请求的追踪上下文。
 * 所有 LLM、工具与内部步骤复用相同 traceId，最终统一写入 push_log.trace_id。
 */
public final class AgentTraceContext {
    private static final int MAX_LOG_PAYLOAD_LENGTH = 32_000;

    private final String traceId;
    private final Long uid;
    private final String conversationId;
    private final String scopeType;
    private final String logId;
    private final List<Integer> machineIds;

    private AgentTraceContext(
            String traceId,
            Long uid,
            String conversationId,
            String scopeType,
            String logId,
            List<Integer> machineIds
    ) {
        this.traceId = traceId;
        this.uid = uid;
        this.conversationId = conversationId;
        this.scopeType = scopeType;
        this.logId = logId;
        this.machineIds = machineIds == null ? List.of() : List.copyOf(machineIds);
    }

    public static AgentTraceContext forConversation(AgentConversationSession session) {
        return new AgentTraceContext(
                nextTraceId("Agent"),
                session.uid(),
                session.conversationId(),
                session.scopeType(),
                session.logId(),
                session.machineIds()
        );
    }

    public static AgentTraceContext forLogAnalysis(String logId) {
        return new AgentTraceContext(
                nextTraceId("AgentLog"),
                AgentConversationConfig.DEFAULT_UID,
                null,
                AgentConversationService.LOG_SCOPE,
                logId,
                List.of()
        );
    }

    public static AgentTraceContext forNodeAnalysis(Integer machineId) {
        return new AgentTraceContext(
                nextTraceId("AgentNode"),
                AgentConversationConfig.DEFAULT_UID,
                null,
                AgentConversationService.NODE_SCOPE,
                null,
                machineId == null ? List.of() : List.of(machineId)
        );
    }

    /**
     * 接收其他本系统 Agent 通过 MCP 透传的 Trace。
     * 只接受受限格式，避免外部请求将超长或异常内容写入 trace_id 字段。
     */
    public static AgentTraceContext forIncomingMcpTrace(String incomingTraceId, String logId) {
        String traceId = isValidTraceId(incomingTraceId) ? incomingTraceId : nextTraceId("AgentMcp");
        return new AgentTraceContext(
                traceId,
                AgentConversationConfig.DEFAULT_UID,
                null,
                "mcp",
                logId,
                List.of()
        );
    }

    /**
     * 每条日志创建新的 LogContext，避免同一上下文在多线程流式回调中发生字段串写。
     */
    public LogContext logContext() {
        LogContext context = LogContext.create()
                .traceId(traceId)
                .uid(uid)
                .put("agentTraceId", traceId)
                .put("agentScopeType", scopeType);
        if (conversationId != null) {
            context.msgId(conversationId);
            context.put("conversationId", conversationId);
        }
        if (logId != null) {
            context.put("scopeLogId", logId);
        }
        if (!machineIds.isEmpty()) {
            context.put("scopeMachineIds", machineIds);
        }
        return context;
    }

    /**
     * 请求上下文、工具结果和模型输出可能较大，保留可审计正文并显式标记被截断的数据。
     */
    public LogContext putPayload(LogContext context, String key, Object payload) {
        String text = payload instanceof String ? (String) payload : JsonUtil.toJson(payload);
        if (text == null) {
            return context.put(key, null);
        }
        if (text.length() <= MAX_LOG_PAYLOAD_LENGTH) {
            return context.put(key, text);
        }
        return context
                .put(key, text.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...[truncated]")
                .put(key + "Truncated", true)
                .put(key + "OriginalLength", text.length());
    }

    public String traceId() {
        return traceId;
    }

    private static String nextTraceId(String prefix) {
        // trace_id 字段长度为 64，使用短随机串保证同毫秒并发请求也不会冲突。
        return prefix + "_" + System.currentTimeMillis() + "_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static boolean isValidTraceId(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }
}
