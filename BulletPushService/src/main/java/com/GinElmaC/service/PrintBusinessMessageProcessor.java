package com.GinElmaC.service;

import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;

/**
 * 当前的业务处理占位实现。
 * 仅打印消费到的消息，不修改消息体，确保后续 message_out 路由可继续使用原始 roomId 和 logId。
 */
public class PrintBusinessMessageProcessor implements BusinessMessageProcessor {
    private static final Log log = LogFactory.getLog(PrintBusinessMessageProcessor.class);

    @Override
    public String process(String messageKey, String payload, LogContext logContext) {
        log.Info(logContext.put("messageKey", messageKey), "BUSINESS_MESSAGE_PROCESS_SIMULATED");
        return payload;
    }
}
