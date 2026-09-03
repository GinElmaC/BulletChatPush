package com.GinElmaC.NettyServer.Agent;

/**
 * Agent 会话审计表专属 SQL。
 * 不复用 push_log 的查询定义，避免不同表之间产生隐式耦合。
 */
public final class AgentConversationArchiveQuery {
    public static final String UPSERT_SESSION = """
            INSERT INTO agent_conversation (
                conversation_id, uid, scope_type, scope_value, machine_ids_json, log_id,
                model, summary, turn_count, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                uid = VALUES(uid),
                scope_type = VALUES(scope_type),
                scope_value = VALUES(scope_value),
                machine_ids_json = VALUES(machine_ids_json),
                log_id = VALUES(log_id),
                model = VALUES(model),
                summary = VALUES(summary),
                turn_count = VALUES(turn_count),
                updated_at = VALUES(updated_at)
            """;

    public static final String UPSERT_MESSAGE = """
            INSERT INTO agent_conversation_message (
                conversation_id, uid, turn_no, role, content, model, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                content = VALUES(content),
                model = VALUES(model),
                created_at = VALUES(created_at)
            """;

    private AgentConversationArchiveQuery() {
    }
}
