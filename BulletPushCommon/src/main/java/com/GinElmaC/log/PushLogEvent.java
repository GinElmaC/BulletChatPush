package com.GinElmaC.log;

import java.time.LocalDateTime;

public class PushLogEvent {
    private final LocalDateTime logTime;
    private final LogLevel level;
    private final String serverName;
    private final Integer machineId;
    private final String nodeName;
    private final String hostIp;
    private final String loggerName;
    private final String threadName;
    private final String traceId;
    private final String msgId;
    private final Long uid;
    private final Long roomId;
    private final String message;
    private final String throwable;
    private final String contextJson;

    public PushLogEvent(LocalDateTime logTime, LogLevel level, String serverName, Integer machineId,
                        String nodeName, String hostIp, String loggerName, String threadName,
                        String traceId, String msgId, Long uid, Long roomId, String message,
                        String throwable, String contextJson) {
        this.logTime = logTime;
        this.level = level;
        this.serverName = serverName;
        this.machineId = machineId;
        this.nodeName = nodeName;
        this.hostIp = hostIp;
        this.loggerName = loggerName;
        this.threadName = threadName;
        this.traceId = traceId;
        this.msgId = msgId;
        this.uid = uid;
        this.roomId = roomId;
        this.message = message;
        this.throwable = throwable;
        this.contextJson = contextJson;
    }

    public LocalDateTime getLogTime() {
        return logTime;
    }

    public LogLevel getLevel() {
        return level;
    }

    public String getServerName() {
        return serverName;
    }

    public Integer getMachineId() {
        return machineId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getHostIp() {
        return hostIp;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getThreadName() {
        return threadName;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getMsgId() {
        return msgId;
    }

    public Long getUid() {
        return uid;
    }

    public Long getRoomId() {
        return roomId;
    }

    public String getMessage() {
        return message;
    }

    public String getThrowable() {
        return throwable;
    }

    public String getContextJson() {
        return contextJson;
    }
}
