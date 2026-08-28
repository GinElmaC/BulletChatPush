package com.GinElmaC.NettyServer.Factory;

import com.GinElmaC.NettyServer.Service.AbstractMessageService;
import com.GinElmaC.NettyServer.Service.Impl.HeartBeatService;
import com.GinElmaC.NettyServer.Service.Impl.LoginService;
import com.GinElmaC.domain.enums.MessageType;
import com.GinElmaC.domain.model.CompleteMessage;

public class MessageServiceFactory {
    public static AbstractMessageService<CompleteMessage> getService(MessageType messageType){
        return switch (messageType){
            case LOGIN_MESSAGE -> LoginService.getInstance();
            case HEARTBEAT_MESSAGE -> HeartBeatService.getInstance();
            default -> throw new IllegalArgumentException("不支持的消息类型");
        };
    }
}
