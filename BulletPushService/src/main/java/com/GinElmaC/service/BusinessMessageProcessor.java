package com.GinElmaC.service;

import com.GinElmaC.log.LogContext;

/**
 * 业务消息处理扩展点。
 * 返回值会作为 message_out 的消息体，因此必须保留原消息中的 logId、roomId 等路由字段。
 */
public interface BusinessMessageProcessor {
    String process(String messageKey, String payload, LogContext logContext);
}
