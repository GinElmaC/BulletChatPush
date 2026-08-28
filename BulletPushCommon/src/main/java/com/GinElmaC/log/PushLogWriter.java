package com.GinElmaC.log;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class PushLogWriter {
    private static final int QUEUE_SIZE = 10000;
    private static final int BATCH_SIZE = 100;
    private static final BlockingQueue<PushLogEvent> QUEUE = new ArrayBlockingQueue<>(QUEUE_SIZE);
    private static final PushLogWriter INSTANCE = new PushLogWriter();

    private final Thread worker;

    private PushLogWriter() {
        worker = new Thread(this::run, "push-log-writer");
        worker.setDaemon(true);
        worker.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    public static PushLogWriter getInstance() {
        return INSTANCE;
    }

    public void append(PushLogEvent event) {
        if (!LogMysqlConfig.enabled()) {
            return;
        }
        QUEUE.offer(event);
    }

    private void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                List<PushLogEvent> batch = takeBatch();
                if (!batch.isEmpty()) {
                    insertBatch(batch);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[PushLogWriter] write push_log error: " + e.getMessage());
            }
        }
    }

    private List<PushLogEvent> takeBatch() throws InterruptedException {
        List<PushLogEvent> batch = new ArrayList<>(BATCH_SIZE);
        PushLogEvent first = QUEUE.poll(1, TimeUnit.SECONDS);
        if (first == null) {
            return batch;
        }
        batch.add(first);
        QUEUE.drainTo(batch, BATCH_SIZE - 1);
        return batch;
    }

    private void insertBatch(List<PushLogEvent> batch) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                LogMysqlConfig.MYSQL_URL,
                LogMysqlConfig.MYSQL_USER,
                LogMysqlConfig.MYSQL_PASSWORD);
             PreparedStatement statement = connection.prepareStatement(PushLogQuery.INSERT)) {
            for (PushLogEvent event : batch) {
                bind(statement, event);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void bind(PreparedStatement statement, PushLogEvent event) throws Exception {
        statement.setTimestamp(1, Timestamp.valueOf(event.getLogTime()));
        statement.setInt(2, event.getLevel().getCode());
        statement.setString(3, event.getLevel().getName());
        statement.setString(4, event.getServerName());
        if (event.getMachineId() == null) {
            statement.setObject(5, null);
        } else {
            statement.setInt(5, event.getMachineId());
        }
        statement.setString(6, event.getNodeName());
        statement.setString(7, event.getHostIp());
        statement.setString(8, event.getLoggerName());
        statement.setString(9, event.getThreadName());
        statement.setString(10, event.getTraceId());
        statement.setString(11, event.getMsgId());
        if (event.getUid() == null) {
            statement.setObject(12, null);
        } else {
            statement.setLong(12, event.getUid());
        }
        if (event.getRoomId() == null) {
            statement.setObject(13, null);
        } else {
            statement.setLong(13, event.getRoomId());
        }
        statement.setString(14, event.getMessage());
        statement.setString(15, event.getThrowable());
        statement.setString(16, event.getContextJson());
    }

    private void shutdown() {
        worker.interrupt();
        List<PushLogEvent> batch = new ArrayList<>(QUEUE_SIZE);
        QUEUE.drainTo(batch);
        if (!batch.isEmpty()) {
            try {
                insertBatch(batch);
            } catch (Exception e) {
                System.err.println("[PushLogWriter] flush push_log error: " + e.getMessage());
            }
        }
    }
}
