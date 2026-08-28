package com.GinElmaC.NettyServer.Service.Impl;

import com.GinElmaC.NettyServer.Service.AbstractMessageService;
import com.GinElmaC.domain.model.CompleteMessage;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogFactory;

public class LoginService extends AbstractMessageService<CompleteMessage> {
    private static final Log log = LogFactory.getLog(LoginService.class);
    //使用单例模式
    private LoginService(){}

    private static final LoginService INSTANCE = new LoginService();

    public static LoginService getInstance(){
        return INSTANCE;
    }


    @Override
    public void doMessage(CompleteMessage message) {
        // 登录绑定逻辑尚未实现，先保留可追踪的服务处理日志。
        log.Info(message.createLogContext(), "LOGIN_MESSAGE_RECEIVED");
    }
}
