package com.GinElmaC.NettyServer.Config;

/**
 * 代码规范,定义server类的方法限制
 */
public interface ServerLifeCycle {
    /**
     * 初始化
     */
    void init();
    /**
     * 开启
     */
    void start();
    /**
     * 关闭
     */
    void shutdown();
    /**
     * 判断是否启动
     * @return
     */
    boolean isStarted();
}
