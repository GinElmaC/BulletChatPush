package com.GinElmaC.NettyServer.Service.Impl;

import com.GinElmaC.NettyServer.Service.AbstractMessageService;
import com.GinElmaC.domain.model.CompleteMessage;

public class LoginService extends AbstractMessageService<CompleteMessage> {
    //使用单例模式
    private LoginService(){}

    private static final LoginService INSTANCE = new LoginService();

    public static LoginService getInstance(){
        return INSTANCE;
    }


    @Override
    protected void doMessage(CompleteMessage message) {

    }
}
