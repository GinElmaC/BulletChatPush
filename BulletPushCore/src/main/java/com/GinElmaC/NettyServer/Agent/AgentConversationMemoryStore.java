package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.constant.LinkConfigConstant;
import com.GinElmaC.redis.RedisClient;
import com.GinElmaC.utils.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Agent 短期记忆 Redis 存储。
 * Hash 保存会话元信息和旧轮次摘要，List 保存最近十轮完整原始对话。
 */
public class AgentConversationMemoryStore {
    private static final String SCOPE_SESSION_PREFIX =
            LinkConfigConstant.REDISKEY_PERFER + "Agent_Scope_Session:";
    private static final String SESSION_PREFIX =
            LinkConfigConstant.REDISKEY_PERFER + "Agent_Session:";
    private static final String META_SUFFIX = ":meta";
    private static final String TURNS_SUFFIX = ":turns";
    private static final String LOCK_SUFFIX = ":lock";

    /**
     * 按固定分析范围读取当前有效会话。
     * scope 索引保存 uid 与 LogID/节点范围的映射，不直接暴露原始 LogID。
     */
    public AgentConversationSession findByScope(
            long uid,
            String scopeType,
            List<Integer> machineIds,
            String logId
    ) {
        String conversationId = RedisClient.get(scopeSessionKey(uid, scopeType, machineIds, logId));
        return hasText(conversationId) ? findByConversationId(conversationId) : null;
    }

    /**
     * 读取会话元信息和最近十轮原始消息。
     */
    public AgentConversationSession findByConversationId(String conversationId) {
        if (!hasText(conversationId)) {
            return null;
        }
        Map<String, String> meta = RedisClient.hgetAll(metaKey(conversationId));
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        try {
            return new AgentConversationSession(
                    conversationId,
                    parseLong(meta.get("uid"), 0L),
                    meta.get("scope_type"),
                    parseMachineIds(meta.get("machine_ids_json")),
                    emptyToNull(meta.get("log_id")),
                    meta.get("model"),
                    emptyToNull(meta.get("summary")),
                    parseInt(meta.get("turn_count"), 0),
                    parseLong(meta.get("created_at"), 0L),
                    parseLong(meta.get("updated_at"), 0L),
                    readTurns(conversationId)
            );
        } catch (Exception e) {
            throw new IllegalStateException("read agent conversation memory failed", e);
        }
    }

    /**
     * 创建会话元信息、scope 索引和最近轮次 List。
     */
    public AgentConversationSession create(
            long uid,
            String scopeType,
            List<Integer> machineIds,
            String logId,
            String requestedModel,
            long nowMillis
    ) {
        String conversationId = UUID.randomUUID().toString();
        List<Integer> normalizedMachineIds = machineIds == null ? List.of() : List.copyOf(machineIds);
        Map<String, String> meta = new HashMap<>();
        meta.put("uid", String.valueOf(uid));
        meta.put("scope_type", scopeType);
        meta.put("machine_ids_json", JsonUtil.toJson(normalizedMachineIds));
        meta.put("log_id", logId == null ? "" : logId);
        meta.put("model", requestedModel);
        meta.put("summary", "");
        meta.put("turn_count", "0");
        meta.put("created_at", String.valueOf(nowMillis));
        meta.put("updated_at", String.valueOf(nowMillis));

        RedisClient.hmset(metaKey(conversationId), meta);
        RedisClient.expire(metaKey(conversationId), AgentConversationConfig.SESSION_TTL_SECONDS);
        RedisClient.setex(
                scopeSessionKey(uid, scopeType, normalizedMachineIds, logId),
                AgentConversationConfig.SESSION_TTL_SECONDS,
                conversationId
        );
        return new AgentConversationSession(
                conversationId,
                uid,
                scopeType,
                normalizedMachineIds,
                logId,
                requestedModel,
                null,
                0,
                nowMillis,
                nowMillis,
                List.of()
        );
    }

    /**
     * 追加一轮原始对话；超出十轮的最早消息会合并到 summary 后从 List 中裁剪。
     * 历史压缩不额外调用模型，避免一次追问引入第二次模型调用和额外故障点。
     */
    public AgentConversationSession appendTurn(
            AgentConversationSession session,
            String userMessage,
            String assistantMessage,
            String actualModel,
            long nowMillis
    ) {
        AgentConversationTurn turn = new AgentConversationTurn(
                session.turnCount() + 1,
                userMessage,
                assistantMessage,
                actualModel,
                nowMillis
        );
        String turnsKey = turnsKey(session.conversationId());
        RedisClient.rpush(turnsKey, JsonUtil.toJson(turn));

        List<AgentConversationTurn> turns = readTurns(session.conversationId());
        String summary = session.summary();
        if (turns.size() > AgentConversationConfig.MAX_RECENT_TURNS) {
            int overflowCount = turns.size() - AgentConversationConfig.MAX_RECENT_TURNS;
            summary = compressSummary(summary, turns.subList(0, overflowCount));
            RedisClient.ltrim(turnsKey, overflowCount, -1);
            turns = readTurns(session.conversationId());
        }

        Map<String, String> update = Map.of(
                "summary", summary == null ? "" : summary,
                "turn_count", String.valueOf(turn.turnNo()),
                "updated_at", String.valueOf(nowMillis)
        );
        RedisClient.hmset(metaKey(session.conversationId()), update);
        refreshTtl(session);
        return new AgentConversationSession(
                session.conversationId(),
                session.uid(),
                session.scopeType(),
                session.machineIds(),
                session.logId(),
                session.requestedModel(),
                summary,
                turn.turnNo(),
                session.createdAtMillis(),
                nowMillis,
                turns
        );
    }

    /**
     * 获取会话级分布式锁，保证同一会话的历史写入与摘要裁剪严格串行。
     */
    public ConversationLock tryLock(String conversationId) {
        String lockValue = UUID.randomUUID().toString();
        boolean locked = RedisClient.tryLock(
                lockKey(conversationId),
                lockValue,
                Math.max(AgentConversationConfig.SESSION_TTL_SECONDS * 1000L, 60_000L)
        );
        return locked ? new ConversationLock(lockKey(conversationId), lockValue) : null;
    }

    private void refreshTtl(AgentConversationSession session) {
        RedisClient.expire(metaKey(session.conversationId()), AgentConversationConfig.SESSION_TTL_SECONDS);
        RedisClient.expire(turnsKey(session.conversationId()), AgentConversationConfig.SESSION_TTL_SECONDS);
        RedisClient.setex(
                scopeSessionKey(session.uid(), session.scopeType(), session.machineIds(), session.logId()),
                AgentConversationConfig.SESSION_TTL_SECONDS,
                session.conversationId()
        );
    }

    private List<AgentConversationTurn> readTurns(String conversationId) {
        List<AgentConversationTurn> turns = new ArrayList<>();
        for (String rawTurn : RedisClient.lrange(turnsKey(conversationId), 0, -1)) {
            try {
                AgentConversationTurn turn = JsonUtil.fromJson(rawTurn, AgentConversationTurn.class);
                if (turn != null) {
                    turns.add(turn);
                }
            } catch (Exception ignored) {
                // 单条损坏缓存不应阻断整个排障会话，其余有效轮次仍可继续使用。
            }
        }
        return turns;
    }

    private String compressSummary(String previousSummary, List<AgentConversationTurn> turnsToCompress) {
        StringBuilder summary = new StringBuilder();
        if (hasText(previousSummary)) {
            summary.append(previousSummary.trim()).append('\n');
        }
        for (AgentConversationTurn turn : turnsToCompress) {
            summary.append("第").append(turn.turnNo()).append("轮：用户=")
                    .append(abbreviate(turn.userMessage(), 600))
                    .append("；助手=")
                    .append(abbreviate(turn.assistantMessage(), 1200))
                    .append('\n');
        }
        int maxLength = AgentConversationConfig.MAX_SUMMARY_LENGTH;
        if (summary.length() <= maxLength) {
            return summary.toString().trim();
        }
        return "较早会话已进一步压缩，以下保留最近的历史摘要：\n"
                + summary.substring(summary.length() - maxLength).trim();
    }

    private List<Integer> parseMachineIds(String machineIdsJson) {
        if (!hasText(machineIdsJson)) {
            return List.of();
        }
        Integer[] values = JsonUtil.fromJson(machineIdsJson, Integer[].class);
        if (values == null || values.length == 0) {
            return List.of();
        }
        List<Integer> machineIds = new ArrayList<>();
        for (Integer value : values) {
            if (value != null) {
                machineIds.add(value);
            }
        }
        return machineIds;
    }

    private String scopeSessionKey(long uid, String scopeType, List<Integer> machineIds, String logId) {
        String scopeValue = AgentConversationService.LOG_SCOPE.equals(scopeType)
                ? "log:" + (logId == null ? "" : logId.trim())
                : "node:" + (machineIds == null ? List.of() : machineIds.stream().sorted().toList());
        return SCOPE_SESSION_PREFIX + uid + ":" + scopeType + ":" + sha256(scopeValue);
    }

    private String metaKey(String conversationId) {
        return SESSION_PREFIX + conversationId + META_SUFFIX;
    }

    private String turnsKey(String conversationId) {
        return SESSION_PREFIX + conversationId + TURNS_SUFFIX;
    }

    private String lockKey(String conversationId) {
        return SESSION_PREFIX + conversationId + LOCK_SUFFIX;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("create agent scope digest failed", e);
        }
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private long parseLong(String value, long defaultValue) {
        try {
            return value == null ? defaultValue : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return value == null ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String emptyToNull(String value) {
        return hasText(value) ? value : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 锁释放由 AutoCloseable 保证，即使模型调用异常也不会遗留长时间锁。
     */
    public static final class ConversationLock implements AutoCloseable {
        private final String key;
        private final String value;

        private ConversationLock(String key, String value) {
            this.key = key;
            this.value = value;
        }

        @Override
        public void close() {
            RedisClient.unlock(key, value);
        }
    }
}
