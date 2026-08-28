package com.GinElmaC.log;

public class PushLogSearchParam {
    private Integer machineId;
    // 日志中心的 LogID 对应 push_log.trace_id。
    private String logId;
    private LogLevel level;
    private String keyword;
    private int limit = 100;
    private int offset = 0;

    public Integer getMachineId() {
        return machineId;
    }

    public PushLogSearchParam setMachineId(Integer machineId) {
        this.machineId = machineId;
        return this;
    }

    public String getLogId() {
        return logId;
    }

    public PushLogSearchParam setLogId(String logId) {
        this.logId = logId;
        return this;
    }

    public LogLevel getLevel() {
        return level;
    }

    public PushLogSearchParam setLevel(LogLevel level) {
        this.level = level;
        return this;
    }

    public String getKeyword() {
        return keyword;
    }

    public PushLogSearchParam setKeyword(String keyword) {
        this.keyword = keyword;
        return this;
    }

    public int getLimit() {
        return limit;
    }

    public PushLogSearchParam setLimit(int limit) {
        this.limit = limit;
        return this;
    }

    public int getOffset() {
        return offset;
    }

    public PushLogSearchParam setOffset(int offset) {
        this.offset = offset;
        return this;
    }
}
