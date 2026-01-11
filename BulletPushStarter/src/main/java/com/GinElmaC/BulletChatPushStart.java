package com.GinElmaC;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 推送服务启动
 */
@Slf4j
public class BulletChatPushStart {
    private static final Logger log = LoggerFactory.getLogger(BulletChatPushStart.class);

    public static void main(String[] args) {
        log.info("BulletChatPush is starting.....");
        //启动Netty服务
        //初始化注册中心
        //启动grpc

        log.info("BulletChatPush has started.....");
    }
}
