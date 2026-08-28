package com.GinElmaC.NettyServer.Service.Impl;

import com.GinElmaC.NettyServer.Service.AbstractMessageService;
import com.GinElmaC.domain.model.CompleteMessage;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogFactory;

/**
 * 应用层心跳处理。
 * 节点指标在 Handler 更新，这里记录消息链路日志，后续可扩展心跳应答和超时摘流。
 */
public class HeartBeatService extends AbstractMessageService<CompleteMessage> {
    private static final HeartBeatService INSTANCE = new HeartBeatService();
    private static final Log log = LogFactory.getLog(HeartBeatService.class);

    private HeartBeatService() {
    }

    public static HeartBeatService getInstance() {
        return INSTANCE;
    }

    @Override
    public void doMessage(CompleteMessage message) {
        log.Info(message.createLogContext(), "HEARTBEAT_SERVICE_HANDLED");
    }
}
