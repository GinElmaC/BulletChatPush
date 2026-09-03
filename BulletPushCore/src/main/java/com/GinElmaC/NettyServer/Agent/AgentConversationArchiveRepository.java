package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.LogMysqlConfig;
import com.GinElmaC.utils.JsonUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;

/**
 * Agent 会话 MySQL 归档与审计仓库。
 * Redis 负责短期记忆，MySQL 保留完整会话和每一轮原始消息，方便后续检索与审计。
 */
public class AgentConversationArchiveRepository {
    public void archiveSession(AgentConversationSession session) {
        if (!LogMysqlConfig.enabled()) {
            return;
        }
        try (Connection connection = openConnection()) {
            upsertSession(connection, session);
        } catch (Exception e) {
            throw new IllegalStateException("archive agent conversation session failed", e);
        }
    }

    /**
     * 一个事务内更新会话摘要状态，并分别归档用户问题与助手回复。
     */
    public void archiveTurn(AgentConversationSession session, AgentConversationTurn turn) {
        if (!LogMysqlConfig.enabled()) {
            return;
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                upsertSession(connection, session);
                insertMessage(connection, session, turn, "user", turn.userMessage());
                insertMessage(connection, session, turn, "assistant", turn.assistantMessage());
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("archive agent conversation turn failed", e);
        }
    }

    private Connection openConnection() throws Exception {
        return DriverManager.getConnection(
                LogMysqlConfig.MYSQL_URL,
                LogMysqlConfig.MYSQL_USER,
                LogMysqlConfig.MYSQL_PASSWORD
        );
    }

    private void upsertSession(Connection connection, AgentConversationSession session) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(AgentConversationArchiveQuery.UPSERT_SESSION)) {
            statement.setString(1, session.conversationId());
            statement.setLong(2, session.uid());
            statement.setString(3, session.scopeType());
            statement.setString(4, scopeValue(session));
            statement.setString(5, JsonUtil.toJson(session.machineIds()));
            if (session.logId() == null) {
                statement.setNull(6, Types.VARCHAR);
            } else {
                statement.setString(6, session.logId());
            }
            statement.setString(7, session.requestedModel());
            if (session.summary() == null) {
                statement.setNull(8, Types.LONGVARCHAR);
            } else {
                statement.setString(8, session.summary());
            }
            statement.setInt(9, session.turnCount());
            statement.setTimestamp(10, new Timestamp(session.createdAtMillis()));
            statement.setTimestamp(11, new Timestamp(session.updatedAtMillis()));
            statement.executeUpdate();
        }
    }

    private void insertMessage(
            Connection connection,
            AgentConversationSession session,
            AgentConversationTurn turn,
            String role,
            String content
    ) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(AgentConversationArchiveQuery.UPSERT_MESSAGE)) {
            statement.setString(1, session.conversationId());
            statement.setLong(2, session.uid());
            statement.setInt(3, turn.turnNo());
            statement.setString(4, role);
            statement.setString(5, content);
            statement.setString(6, turn.model());
            statement.setTimestamp(7, new Timestamp(turn.createdAtMillis()));
            statement.executeUpdate();
        }
    }

    private String scopeValue(AgentConversationSession session) {
        if (AgentConversationService.LOG_SCOPE.equals(session.scopeType())) {
            return session.logId();
        }
        return "machineIds=" + session.machineIds();
    }
}
