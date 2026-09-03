package com.GinElmaC.log;

public class PushLogQuery {
    public static final String INSERT = """
            INSERT INTO push_log (
                log_time, level, level_name, server_name, machine_id, node_name, host_ip,
                logger_name, thread_name, source_file_path, source_line, trace_id, msg_id, uid, room_id,
                message, throwable, context_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    public static final String SELECT_BY_MACHINE_ID_BASE = """
            SELECT
                id, log_time, level, level_name, server_name, machine_id, node_name, host_ip,
                logger_name, thread_name, source_file_path, source_line, trace_id, msg_id, uid, room_id,
                message, throwable, context_json, created_at
            FROM push_log
            WHERE machine_id = ?
            """;

    /**
     * 日志中心的 LogID 统一映射到 trace_id，使用 trace_id 索引查询同一条链路日志。
     */
    public static final String SELECT_BY_LOG_ID_BASE = """
            SELECT
                id, log_time, level, level_name, server_name, machine_id, node_name, host_ip,
                logger_name, thread_name, source_file_path, source_line, trace_id, msg_id, uid, room_id,
                message, throwable, context_json, created_at
            FROM push_log
            WHERE trace_id = ?
            """;

    public static final String AND_LEVEL = " AND level = ?";

    public static final String AND_KEYWORD = " AND message LIKE ?";

    public static final String ORDER_BY_TIME_LIMIT = " ORDER BY log_time DESC, id DESC LIMIT ? OFFSET ?";
}
