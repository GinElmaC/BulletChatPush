package com.GinElmaC;

import com.GinElmaC.NettyServer.Bootstrap.NettyServer;
import com.GinElmaC.NettyServer.Bootstrap.WebSocketGatewayServer;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogFactory;

/**
 * 推送服务启动
 */
public class BulletChatPushStart {
    private static final Log log = LogFactory.getLog(BulletChatPushStart.class);

    public static void main(String[] args) {
        log.Info("BulletChatPush is starting.....");
        //启动Netty服务
        NettyServer nettyServer = new NettyServer();
        WebSocketGatewayServer webSocketGatewayServer = new WebSocketGatewayServer();
        AdminManagementServer adminManagementServer = new AdminManagementServer();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            adminManagementServer.stop();
            webSocketGatewayServer.stop();
            nettyServer.shutdown();
        }));
        nettyServer.start();
        // 启动浏览器 WebSocket Gateway，接收用户上行消息并写入 Kafka。
        webSocketGatewayServer.start();
        // 启动管理接口，为前端提供真实节点指标、push_log 查询和日志智能分析。
        adminManagementServer.start();
        //初始化注册中心
        //启动grpc

        log.Info("BulletChatPush has started.....");
    }
}
