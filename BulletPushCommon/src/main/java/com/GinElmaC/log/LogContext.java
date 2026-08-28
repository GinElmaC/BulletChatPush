package com.GinElmaC.log;

import java.util.HashMap;
import java.util.Map;

public class LogContext {
    private String traceId;
    private String msgId;
    private Long uid;
    private Long roomId;
    private final Map<String, Object> extra = new HashMap<>();

    public static LogContext create() {
        return new LogContext();
    }

    public LogContext traceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    public LogContext msgId(String msgId) {
        this.msgId = msgId;
        return this;
    }

    public LogContext uid(Long uid) {
        this.uid = uid;
        return this;
    }

    public LogContext roomId(Long roomId) {
        this.roomId = roomId;
        return this;
    }

    public LogContext put(String key, Object value) {
        this.extra.put(key, value);
        return this;
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

    public Map<String, Object> getExtra() {
        return extra;
    }
}
