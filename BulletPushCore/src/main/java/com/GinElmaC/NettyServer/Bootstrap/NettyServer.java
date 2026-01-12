package com.GinElmaC.NettyServer.Bootstrap;

import com.GinElmaC.NettyServer.Config.LinkConfig;
import com.GinElmaC.NettyServer.Config.NettyConfig;
import com.GinElmaC.NettyServer.Config.ServerLifeCycle;
import com.GinElmaC.redis.RedisClient;
import com.GinElmaC.utils.SystemUtils;
import com.google.rpc.Help;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty服务端启动
 */
@Slf4j
public class NettyServer implements ServerLifeCycle {
    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);
    private ServerBootstrap serverBootstrap;
    private EventLoopGroup bossEventLoopGroup;
    private EventLoopGroup workEventLoopGroup;


    @Override
    public void init() {
        //初始化分配机器id
        LinkConfig.MACHINE_ID = RedisClient.initRedisServerId();
        //初始化选择Netty模式
        log.info("OS_NAME:", SystemUtils.getOsName());
        serverBootstrap = new ServerBootstrap();
        switch(SystemUtils.ChargeMode()){
            case 0:
                bossEventLoopGroup = new NioEventLoopGroup(NettyConfig.bossEventLoopGroupNum,
                        new DefaultThreadFactory("default-netty-boss-nio"));
                workEventLoopGroup = new NioEventLoopGroup(NettyConfig.workerEventLoopGroupNum,
                        new DefaultThreadFactory("default-netty-worker-nio"));
                break;
            case 1:
                bossEventLoopGroup = new EpollEventLoopGroup(NettyConfig.bossEventLoopGroupNum,
                        new DefaultThreadFactory("default-netty-boss-epoll"));
                workEventLoopGroup = new EpollEventLoopGroup(NettyConfig.workerEventLoopGroupNum,
                        new DefaultThreadFactory("default-netty-worker-epoll"));
                break;
            case 2:
                bossEventLoopGroup = new IOUringEventLoopGroup(NettyConfig.bossEventLoopGroupNum,
                        new DefaultThreadFactory("default-netty-worker-IOuring"));
                workEventLoopGroup = new IOUringEventLoopGroup(NettyConfig.workerEventLoopGroupNum,
                        new DefaultThreadFactory("default-netty-worker-IOuring"));
                break;
        }
        log.info("EventLoopGroup has inited,mod Num is:",SystemUtils.ChargeMode());
    }

    @Override
    public void start() {
        //初始化
        init();
    }

    @Override
    public void shutdown() {

    }

    @Override
    public boolean isStarted() {
        return false;
    }
}
