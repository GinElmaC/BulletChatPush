package com.GinElmaC.NettyServer.Config;

import com.GinElmaC.constant.LinkConfigConstant;

public class NettyConfig {
    public static int bossEventLoopGroupNum = 1;
    //工作线程数量，获取当前 Java 虚拟机可用的处理器（CPU 核心）数量
    public static int workerEventLoopGroupNum = Runtime.getRuntime().availableProcessors();
    //内容最大容量
    public static int maxContentSize = 64*1024*1024;

    //监听端口
    public static int port = LinkConfigConstant.LISTENING_PORT;
}
