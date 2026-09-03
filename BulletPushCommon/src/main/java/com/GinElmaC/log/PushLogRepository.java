package com.GinElmaC.log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class PushLogRepository {
    public List<PushLogRecord> queryByMachineId(PushLogSearchParam param) {
        return query(param);
    }

    public List<PushLogRecord> queryByLogId(PushLogSearchParam param) {
        return query(param);
    }

    /**
     * push_log 专属查询入口。
     * LogID 映射 trace_id，machineId 与 LogID 只能二选一，避免生成含义不明确的查询条件。
     */
    public List<PushLogRecord> query(PushLogSearchParam param) {
        if (param == null || (param.getMachineId() == null && !hasText(param.getLogId()))
                || (param.getMachineId() != null && hasText(param.getLogId()))) {
            throw new IllegalArgumentException("exactly one of machineId or logId is required");
        }
        if (!LogMysqlConfig.enabled()) {
            throw new IllegalStateException("push log mysql config is empty");
        }

        boolean queryByLogId = hasText(param.getLogId());
        StringBuilder sql = new StringBuilder(queryByLogId
                ? PushLogQuery.SELECT_BY_LOG_ID_BASE
                : PushLogQuery.SELECT_BY_MACHINE_ID_BASE);
        if (param.getLevel() != null) {
            sql.append(PushLogQuery.AND_LEVEL);
        }
        if (hasText(param.getKeyword())) {
            sql.append(PushLogQuery.AND_KEYWORD);
        }
        sql.append(PushLogQuery.ORDER_BY_TIME_LIMIT);

        try (Connection connection = DriverManager.getConnection(
                LogMysqlConfig.MYSQL_URL,
                LogMysqlConfig.MYSQL_USER,
                LogMysqlConfig.MYSQL_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (queryByLogId) {
                statement.setString(index++, param.getLogId().trim());
            } else {
                statement.setInt(index++, param.getMachineId());
            }
            if (param.getLevel() != null) {
                statement.setInt(index++, param.getLevel().getCode());
            }
            if (hasText(param.getKeyword())) {
                statement.setString(index++, "%" + param.getKeyword().trim() + "%");
            }
            statement.setInt(index++, safeLimit(param.getLimit()));
            statement.setInt(index, Math.max(param.getOffset(), 0));

            try (ResultSet resultSet = statement.executeQuery()) {
                List<PushLogRecord> records = new ArrayList<>();
                while (resultSet.next()) {
                    records.add(mapRecord(resultSet));
                }
                return records;
            }
        } catch (Exception e) {
            throw new IllegalStateException("query push_log failed", e);
        }
    }

    private PushLogRecord mapRecord(ResultSet resultSet) throws Exception {
        PushLogRecord record = new PushLogRecord();
        record.setId(resultSet.getLong("id"));
        record.setLogTime(toLocalDateTime(resultSet.getTimestamp("log_time")));
        record.setLevel(resultSet.getInt("level"));
        record.setLevelName(resultSet.getString("level_name"));
        record.setServerName(resultSet.getString("server_name"));
        record.setMachineId(resultSet.getObject("machine_id", Integer.class));
        record.setNodeName(resultSet.getString("node_name"));
        record.setHostIp(resultSet.getString("host_ip"));
        record.setLoggerName(resultSet.getString("logger_name"));
        record.setThreadName(resultSet.getString("thread_name"));
        record.setSourceFilePath(resultSet.getString("source_file_path"));
        record.setSourceLine(resultSet.getObject("source_line", Integer.class));
        record.setTraceId(resultSet.getString("trace_id"));
        record.setMsgId(resultSet.getString("msg_id"));
        record.setUid(resultSet.getObject("uid", Long.class));
        record.setRoomId(resultSet.getObject("room_id", Long.class));
        record.setMessage(resultSet.getString("message"));
        record.setThrowable(resultSet.getString("throwable"));
        record.setContextJson(resultSet.getString("context_json"));
        record.setCreatedAt(toLocalDateTime(resultSet.getTimestamp("created_at")));
        return record;
    }

    private java.time.LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return 100;
        }
        return Math.min(limit, 500);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
