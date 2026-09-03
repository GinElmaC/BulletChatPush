package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.NettyServer.Monitor.NodeDetail;
import com.GinElmaC.NettyServer.Monitor.NodeMetrics;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.log.LogLevel;
import com.GinElmaC.log.PushLogRecord;
import com.GinElmaC.log.PushLogRepository;
import com.GinElmaC.log.PushLogSearchParam;
import com.GinElmaC.utils.JsonUtil;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 会话型排障助手服务。
 * 负责从 Redis 恢复短期记忆、为节点/日志会话补充固定上下文，并以流式方式输出模型回复。
 */
public class AgentConversationService {
    public static final String NODE_SCOPE = "node";
    public static final String LOG_SCOPE = "log";
    private static final Log log = LogFactory.getLog(AgentConversationService.class);
    private static final String NODE_SYSTEM_PROMPT = """
            你是推送中台节点排障助手。
            当前会话固定围绕指定节点范围继续排障，必须结合实时节点指标、最近错误日志和历史对话回答。
            要求：
            1. 只基于当前输入上下文分析，不得编造不存在的指标、日志或节点。
            2. 优先回答用户最新问题，但不能脱离当前节点排障范围。
            3. 如果证据不足，明确指出还缺少哪些信息。
            4. 输出中文，面向后端研发和运维人员。
            5. 如果最近错误日志里包含 sourceFilePath/sourceLine，且需要确认实现细节，可调用 query_project_code 查看对应源码片段。
            6. 代码工具只用于核实当前排障链路里的实现，不得脱离当前节点范围随意读取无关文件。
            """;
    private static final String LOG_SYSTEM_PROMPT = """
            你是推送中台日志排障助手。
            当前会话固定围绕一个 LogID 持续分析，必须只基于该 LogID 的真实日志结果和历史对话回答。
            要求：
            1. 禁止编造未出现在日志结果中的事实、错误栈、SQL 或配置项。
            2. 用户继续追问时，要保持围绕同一个 LogID 回答，不要跳到其他链路。
            3. 如果日志证据不足，明确说明缺少哪一段链路日志。
            4. 输出中文，面向后端研发和运维人员。
            5. 当日志里存在 sourceFilePath/sourceLine 且需要确认代码逻辑时，优先调用 query_project_code 查看对应源码片段。
            6. 如果代码工具返回 not_found 或 ambiguous，要明确告诉用户当前无法唯一定位源码。
            """;

    private final AgentConversationMemoryStore conversationMemoryStore = new AgentConversationMemoryStore();
    private final AgentConversationArchiveRepository conversationArchiveRepository =
            new AgentConversationArchiveRepository();
    private final PushLogRepository pushLogRepository = new PushLogRepository();
    private final RuleAnalyzer ruleAnalyzer = new RuleAnalyzer();
    private final LlmAnalyzeClient llmAnalyzeClient = new LlmAnalyzeClient();
    private final AutoModelRouter autoModelRouter = new AutoModelRouter();
    private final AgentToolRegistry agentToolRegistry = new AgentToolRegistry();
    private final BulletAgentRunner agentRunner = new BulletAgentRunner(llmAnalyzeClient, agentToolRegistry);

    /**
     * 创建新的排障会话。
     * 日志会话必须绑定唯一 LogID；节点会话固定绑定当前前端选中的机器 ID 范围。
     */
    public AgentConversationStartResponse startSession(
            String scopeType,
            List<Integer> machineIds,
            String logId,
            String requestedModel
    ) {
        String normalizedScopeType = normalizeScopeType(scopeType);
        String normalizedModel = normalizeRequestedModel(requestedModel);
        String normalizedLogId = normalizeText(logId);
        List<Integer> normalizedMachineIds = normalizeMachineIds(machineIds);
        if (LOG_SCOPE.equals(normalizedScopeType) && normalizedLogId == null) {
            throw new IllegalArgumentException("logId is required");
        }
        long uid = AgentConversationConfig.DEFAULT_UID;
        AgentConversationSession existingSession = conversationMemoryStore.findByScope(
                uid,
                normalizedScopeType,
                normalizedMachineIds,
                normalizedLogId
        );
        // 同一范围且模型未变化时直接恢复会话，保证用户再次打开面板仍能继续追问。
        if (existingSession != null && normalizedModel.equals(existingSession.requestedModel())) {
            return toStartResponse(existingSession, existingSession.turnCount() > 0);
        }

        long nowMillis = System.currentTimeMillis();
        AgentConversationSession session = conversationMemoryStore.create(
                uid,
                normalizedScopeType,
                normalizedMachineIds,
                normalizedLogId,
                normalizedModel,
                nowMillis
        );
        archiveSessionSafely(session);
        return toStartResponse(session, false);
    }

    /**
     * 针对指定会话继续追问。
     * 同一会话内串行执行，避免历史消息在并发请求下出现交叉。
     */
    public void streamChat(String conversationId, String userMessage, AgentConversationStreamObserver observer) {
        String normalizedConversationId = normalizeText(conversationId);
        if (normalizedConversationId == null) {
            observer.onFailed();
            return;
        }
        String normalizedUserMessage = normalizeText(userMessage);
        if (normalizedUserMessage == null) {
            observer.onFailed();
            return;
        }
        try (AgentConversationMemoryStore.ConversationLock ignored =
                     conversationMemoryStore.tryLock(normalizedConversationId)) {
            if (ignored == null) {
                observer.onFailed();
                return;
            }
            // 加锁后重新读取，避免并发请求使用过期的会话轮次和摘要写回覆盖。
            AgentConversationSession session =
                    conversationMemoryStore.findByConversationId(normalizedConversationId);
            if (session == null) {
                observer.onFailed();
                return;
            }
            AgentTraceContext traceContext = AgentTraceContext.forConversation(session);
            logAgentChatStarted(traceContext, session, normalizedUserMessage);
            observer.onStage("建立追踪上下文", "completed", "TraceID=" + traceContext.traceId());
            if (NODE_SCOPE.equals(session.scopeType())) {
                streamNodeConversation(session, normalizedUserMessage, traceContext, observer);
                return;
            }
            streamLogConversation(session, normalizedUserMessage, traceContext, observer);
        } catch (Exception e) {
            observer.onFailed();
        }
    }

    private void streamNodeConversation(
            AgentConversationSession session,
            String userMessage,
            AgentTraceContext traceContext,
            AgentConversationStreamObserver observer
    ) {
        AgentModelLease lease;
        try {
            lease = autoModelRouter.acquire(session.requestedModel());
        } catch (Exception e) {
            logAgentFailed(traceContext, "AGENT_MODEL_LEASE_FAILED", e);
            observer.onFailed();
            return;
        }
        log.Info(traceContext.logContext().put("modelName", lease.model().name()), "AGENT_MODEL_LEASE_ACQUIRED");
        observer.onModel(lease.model().name());
        StringBuilder assistantReply = new StringBuilder();
        AtomicLong firstTokenAtMillis = new AtomicLong();
        try {
            observer.onStage("读取节点上下文", "running", buildNodeStageDetail(session));
            List<AgentChatMessage> requestMessages = buildNodeMessages(session, userMessage);
            observer.onStage("读取节点上下文", "completed", "已读取节点指标、最近错误日志和规则预分析。");
            observer.onStage("生成分析结论", "running", "使用 " + lease.model().name() + " 流式生成回复。");
            String reply = agentRunner.runStream(lease.model(), requestMessages, codeViewTools(),
                    new AgentToolRequest(null, traceContext), traceContext, chunk -> {
                firstTokenAtMillis.compareAndSet(0, System.currentTimeMillis());
                assistantReply.append(chunk);
                observer.onChunk(chunk);
            });
            if (!hasText(reply)) {
                markFailed(lease);
                log.Warn(traceContext.logContext().put("modelName", lease.model().name()),
                        "AGENT_LLM_EMPTY_RESPONSE");
                observer.onFailed();
                return;
            }
            autoModelRouter.complete(lease, firstTokenAtMillis.get());
            observer.onStage("生成分析结论", "completed", "已完成流式回复。");
            persistTurn(session, userMessage, assistantReply.toString(), lease.model().name(), traceContext);
            log.Info(traceContext.logContext()
                            .put("modelName", lease.model().name())
                            .put("replyLength", assistantReply.length()),
                    "AGENT_CHAT_COMPLETED");
            observer.onCompleted();
        } catch (Exception e) {
            markFailed(lease);
            observer.onStage("生成分析结论", "failed", "服务分析失败。");
            logAgentFailed(traceContext, "AGENT_CHAT_FAILED", e);
            observer.onFailed();
        }
    }

    private void streamLogConversation(
            AgentConversationSession session,
            String userMessage,
            AgentTraceContext traceContext,
            AgentConversationStreamObserver observer
    ) {
        AgentModelLease lease;
        try {
            lease = autoModelRouter.acquire(session.requestedModel());
        } catch (Exception e) {
            logAgentFailed(traceContext, "AGENT_MODEL_LEASE_FAILED", e);
            observer.onFailed();
            return;
        }
        log.Info(traceContext.logContext().put("modelName", lease.model().name()), "AGENT_MODEL_LEASE_ACQUIRED");
        observer.onModel(lease.model().name());
        StringBuilder assistantReply = new StringBuilder();
        AtomicLong firstTokenAtMillis = new AtomicLong();
        try {
            observer.onStage("查询日志链路", "running", "调用工具 query_push_logs_by_log_id，LogID=" + session.logId());
            List<AgentChatMessage> requestMessages = buildLogMessages(session, userMessage, traceContext);
            observer.onStage("查询日志链路", "completed", "已读取真实 push_log 日志。");
            observer.onStage("生成分析结论", "running", "使用 " + lease.model().name() + " 流式生成回复。");
            String reply = agentRunner.runStream(lease.model(), requestMessages, codeViewTools(),
                    new AgentToolRequest(session.logId(), traceContext), traceContext, chunk -> {
                firstTokenAtMillis.compareAndSet(0, System.currentTimeMillis());
                assistantReply.append(chunk);
                observer.onChunk(chunk);
            });
            if (!hasText(reply)) {
                markFailed(lease);
                log.Warn(traceContext.logContext().put("modelName", lease.model().name()),
                        "AGENT_LLM_EMPTY_RESPONSE");
                observer.onFailed();
                return;
            }
            autoModelRouter.complete(lease, firstTokenAtMillis.get());
            observer.onStage("生成分析结论", "completed", "已完成流式回复。");
            persistTurn(session, userMessage, assistantReply.toString(), lease.model().name(), traceContext);
            log.Info(traceContext.logContext()
                            .put("modelName", lease.model().name())
                            .put("replyLength", assistantReply.length()),
                    "AGENT_CHAT_COMPLETED");
            observer.onCompleted();
        } catch (Exception e) {
            markFailed(lease);
            observer.onStage("生成分析结论", "failed", "服务分析失败。");
            logAgentFailed(traceContext, "AGENT_CHAT_FAILED", e);
            observer.onFailed();
        }
    }

    private List<AgentChatMessage> buildNodeMessages(AgentConversationSession session, String userMessage) {
        NodeDetail nodeDetail = NodeMetrics.getInstance().snapshot();
        List<PushLogRecord> logs = queryRecentLogs(nodeDetail.getMachineId());
        AgentAnalysisResult ruleResult;
        try {
            ruleResult = ruleAnalyzer.analyze(nodeDetail, logs);
        } catch (Exception e) {
            ruleResult = AgentAnalysisResult.serviceAnalyzeFailed();
        }
        List<AgentChatMessage> messages = new ArrayList<>();
        messages.add(AgentChatMessage.system(NODE_SYSTEM_PROMPT));
        messages.add(AgentChatMessage.system(buildNodeScopeMessage(session)));
        messages.add(AgentChatMessage.system(buildNodeContextMessage(nodeDetail, logs, ruleResult)));
        appendMemoryMessages(messages, session);
        messages.add(AgentChatMessage.user(userMessage));
        return messages;
    }

    private List<AgentChatMessage> buildLogMessages(
            AgentConversationSession session,
            String userMessage,
            AgentTraceContext traceContext
    ) {
        String logQueryResult = queryLogScope(session.logId(), traceContext);
        List<AgentChatMessage> messages = new ArrayList<>();
        messages.add(AgentChatMessage.system(LOG_SYSTEM_PROMPT));
        messages.add(AgentChatMessage.system("当前固定分析范围 LogID=" + session.logId()));
        messages.add(AgentChatMessage.system("""
                以下是当前 LogID 的真实日志结果，请始终基于这批日志继续分析：
                %s
                """.formatted(logQueryResult)));
        appendMemoryMessages(messages, session);
        messages.add(AgentChatMessage.user(userMessage));
        return messages;
    }

    /**
     * 旧轮次摘要与最近十轮完整对话共同构成短期记忆。
     * 系统上下文不入 Redis，每轮都会重新拉取，避免模型使用失效的节点指标或日志证据。
     */
    private void appendMemoryMessages(List<AgentChatMessage> messages, AgentConversationSession session) {
        if (hasText(session.summary())) {
            messages.add(AgentChatMessage.system("以下为更早历史对话的压缩摘要：\n" + session.summary()));
        }
        for (AgentConversationTurn turn : session.turns()) {
            messages.add(AgentChatMessage.user(turn.userMessage()));
            messages.add(AgentChatMessage.assistant(turn.assistantMessage(), List.of()));
        }
    }

    private String buildNodeScopeMessage(AgentConversationSession session) {
        if (session.machineIds().isEmpty()) {
            return "当前排障范围：当前实例节点。";
        }
        return "当前排障范围 machineId=" + session.machineIds();
    }

    private String buildNodeStageDetail(AgentConversationSession session) {
        if (session.machineIds().isEmpty()) {
            return "读取当前实例节点指标和最近错误日志。";
        }
        return "读取 machineId=" + session.machineIds() + " 的节点指标和最近错误日志。";
    }

    private String buildNodeContextMessage(
            NodeDetail nodeDetail,
            List<PushLogRecord> logs,
            AgentAnalysisResult ruleResult
    ) {
        return """
                当前节点实时上下文如下。
                节点详情：
                %s

                最近 ERROR 日志：
                %s

                阈值说明：
                %s

                规则预分析：
                %s
                """.formatted(
                JsonUtil.toJson(nodeDetail),
                JsonUtil.toJson(logs),
                JsonUtil.toJson(thresholds()),
                JsonUtil.toJson(ruleResult)
        );
    }

    /**
     * 会话型排障的日志与节点上下文都已由服务端预取完成，这里只追加代码查看工具即可。
     * 这样既能让模型顺着 sourceFilePath/sourceLine 看实现，又能避免重复查询日志。
     */
    private List<AgentToolDefinition> codeViewTools() {
        return agentToolRegistry.definitions(QueryProjectCodeTool.NAME);
    }

    private String queryLogScope(String logId, AgentTraceContext traceContext) {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("logId", logId);
        arguments.addProperty("limit", 100);
        return agentToolRegistry.execute(
                new AgentToolCall("conversation-log-query-" + System.currentTimeMillis(),
                        QueryPushLogsByLogIdTool.NAME,
                        arguments.toString()),
                new AgentToolRequest(logId, traceContext)
        );
    }

    private List<PushLogRecord> queryRecentLogs(Integer machineId) {
        if (machineId == null) {
            return Collections.emptyList();
        }
        try {
            // 节点日志继续复用 push_log 自身的查询参数对象，避免跨表复用查询定义。
            return pushLogRepository.queryByMachineId(new PushLogSearchParam()
                    .setMachineId(machineId)
                    .setLevel(LogLevel.ERROR)
                    .setLimit(50)
                    .setOffset(0));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private AgentThresholdView thresholds() {
        return new AgentThresholdView(
                AgentThresholdConfig.CPU_WARNING,
                AgentThresholdConfig.CPU_CRITICAL,
                AgentThresholdConfig.HEAP_WARNING,
                AgentThresholdConfig.HEAP_CRITICAL,
                AgentThresholdConfig.HEARTBEAT_WARNING_SECONDS,
                AgentThresholdConfig.HEARTBEAT_CRITICAL_SECONDS
        );
    }

    /**
     * Redis 写入成功后再归档 MySQL。归档失败不影响在线排障，Redis TTL 内仍可继续会话。
     */
    private void persistTurn(
            AgentConversationSession session,
            String userMessage,
            String assistantMessage,
            String actualModel,
            AgentTraceContext traceContext
    ) {
        AgentConversationSession updatedSession = conversationMemoryStore.appendTurn(
                session,
                userMessage,
                assistantMessage,
                actualModel,
                System.currentTimeMillis()
        );
        AgentConversationTurn latestTurn = updatedSession.turns().stream()
                .max(java.util.Comparator.comparingInt(AgentConversationTurn::turnNo))
                .orElseThrow(() -> new IllegalStateException("agent conversation turn is missing"));
        try {
            conversationArchiveRepository.archiveTurn(updatedSession, latestTurn);
        } catch (Exception e) {
            System.err.println("[AgentConversationArchive] archive turn failed: " + e.getMessage());
        }
        log.Info(traceContext.logContext()
                        .put("turnNo", latestTurn.turnNo())
                        .put("recentTurnCount", updatedSession.turns().size())
                        .put("totalTurnCount", updatedSession.turnCount()),
                "AGENT_MEMORY_PERSISTED");
    }

    private void logAgentChatStarted(
            AgentTraceContext traceContext,
            AgentConversationSession session,
            String userMessage
    ) {
        com.GinElmaC.log.LogContext context = traceContext.logContext()
                .put("requestedModel", session.requestedModel())
                .put("historyTurnCount", session.turnCount())
                .put("recentTurnCount", session.turns().size());
        traceContext.putPayload(context, "userMessage", userMessage);
        log.Info(context, "AGENT_CHAT_STARTED");
    }

    private void logAgentFailed(AgentTraceContext traceContext, String eventName, Exception error) {
        log.Error(traceContext.logContext()
                        .put("errorType", error.getClass().getSimpleName()),
                eventName,
                error);
    }

    private void archiveSessionSafely(AgentConversationSession session) {
        try {
            conversationArchiveRepository.archiveSession(session);
        } catch (Exception e) {
            System.err.println("[AgentConversationArchive] archive session failed: " + e.getMessage());
        }
    }

    private AgentConversationStartResponse toStartResponse(
            AgentConversationSession session,
            boolean resumed
    ) {
        return new AgentConversationStartResponse(
                session.conversationId(),
                session.scopeType(),
                session.machineIds(),
                session.logId(),
                session.requestedModel(),
                resumed,
                session.turns()
        );
    }

    private void markFailed(AgentModelLease lease) {
        try {
            autoModelRouter.fail(lease);
        } catch (Exception ignored) {
        }
    }

    private String normalizeScopeType(String scopeType) {
        String normalizedScopeType = normalizeText(scopeType);
        if (LOG_SCOPE.equalsIgnoreCase(normalizedScopeType)) {
            return LOG_SCOPE;
        }
        return NODE_SCOPE;
    }

    private String normalizeRequestedModel(String requestedModel) {
        String normalizedRequestedModel = normalizeText(requestedModel);
        return normalizedRequestedModel == null ? AgentModelRegistry.AUTO : normalizedRequestedModel;
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalizedValue = value.trim();
        return normalizedValue.isEmpty() ? null : normalizedValue;
    }

    private boolean hasText(String value) {
        return normalizeText(value) != null;
    }

    private List<Integer> normalizeMachineIds(List<Integer> machineIds) {
        if (machineIds == null || machineIds.isEmpty()) {
            return List.of();
        }
        return machineIds.stream()
                .filter(machineId -> machineId != null && machineId >= 0)
                .distinct()
                .toList();
    }

    /**
     * 会话开始响应。
     * 前端用 conversationId 继续发起后续追问。
     */
    public record AgentConversationStartResponse(
            String conversationId,
            String scopeType,
            List<Integer> machineIds,
            String logId,
            String model,
            boolean resumed,
            List<AgentConversationTurn> turns
    ) {
    }

    /**
     * SSE 流式回调。
     * 先返回模型名，再逐块返回内容，失败时只吐出固定失败事件。
     */
    public interface AgentConversationStreamObserver {
        void onModel(String modelName);

        void onStage(String title, String status, String detail);

        void onChunk(String chunk);

        void onFailed();

        void onCompleted();
    }

    private record AgentThresholdView(
            double cpuWarning,
            double cpuCritical,
            double heapWarning,
            double heapCritical,
            long heartbeatWarningSeconds,
            long heartbeatCriticalSeconds
    ) {
    }

}
