package com.GinElmaC.log;

import java.util.UUID;

/**
 * 生成单条消息链路的唯一 LogID。
 * LogID 写入 push_log.trace_id，用于跨节点和跨服务检索同一条消息链路。
 */
public class LogIdGenerator {
    private LogIdGenerator() {
    }

    public static String next() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
