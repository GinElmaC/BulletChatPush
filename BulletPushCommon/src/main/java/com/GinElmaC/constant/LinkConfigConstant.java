package com.GinElmaC.constant;

/**
 * 弹幕推送服务常量值
 */
public class LinkConfigConstant {
    //服务监听端口
    public final static Integer LISTENING_PORT = 9999;
    //redis公共前缀
    public final static String REDISKEY_PERFER = "Bullet_Chat_Push_";
    //服务器id rediskey
    public final static String REDISKEY_SERVERID = REDISKEY_PERFER+"Server_id:";
    //服务名
    public final static String DEFAULT_SERVERNAME = "BulletChat_Push";
}
