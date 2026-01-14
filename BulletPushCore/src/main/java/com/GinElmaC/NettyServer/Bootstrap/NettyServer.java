package com.GinElmaC.NettyServer.Bootstrap;

import com.GinElmaC.NettyServer.Config.LinkConfig;
import com.GinElmaC.NettyServer.Config.NettyConfig;
import com.GinElmaC.NettyServer.Config.ServerLifeCycle;
import com.GinElmaC.NettyServer.Handler.MessageProtocolDecoder;
import com.GinElmaC.constant.LinkConfigConstant;
import com.GinElmaC.redis.RedisClient;
import com.GinElmaC.utils.SystemUtils;
import com.google.rpc.Help;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty服务端启动
 */
@Slf4j
public class NettyServer implements ServerLifeCycle {
    private static final Logger log = LoggerFactory.getLogger(NettyServer.class);
    private ServerBootstrap serverBootstrap;
    private EventLoopGroup bossEventLoopGroup;
    private EventLoopGroup workEventLoopGroup;
    private static Class<? extends ServerChannel>[] channelClasse = new Class[]{NioServerSocketChannel.class, EpollServerSocketChannel.class, IOUringServerSocketChannel.class};
    //标识服务是否已经启动
    private final AtomicBoolean started = new AtomicBoolean(false);


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
        //配置Netty服务器参数
        serverBootstrap.group(bossEventLoopGroup,workEventLoopGroup)
                .channel(channelClasse[SystemUtils.ChargeMode()]).childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        socketChannel.pipeline()
                                //心跳检测
                                .addLast(new IdleStateHandler(60,0,0, TimeUnit.SECONDS));
                                //TODO 自定义解码器，自定义编码器，自定义业务处理器
                    }
                })
                // bootstrap 还可以设置tcp参数，根据需要可以分别设置主线程池和从线程池参数，来优化性能
                // 主线程池使用 option方法来设置，从线程池使用 childOption方法设置
                // boss线程只负责接收新连接，当TCP三次握手完成后：
                // 若boss线程处理速度不够快，未及时处理的连接会堆积在操作系统的全连接队列中
                // 该队列长度由SO_BACKLOG参数控制
                // 客户端请求 -> 操作系统SYN队列 -> 完成握手后进入ACCEPT队列（长度由SO_BACKLOG控制）
                //           -> boss线程逐个取出ACCEPT队列中的连接进行后续处理
                .option(ChannelOption.SO_BACKLOG,1024)
                // 表示连接保活，相当于心跳机制，默认7200s，TCP协议栈实现，os内核自动发送心跳包
                .childOption(ChannelOption.SO_KEEPALIVE,true);
        //启动Server服务器
        try {
            ChannelFuture channelFuture = serverBootstrap.bind(LinkConfigConstant.LISTENING_PORT).sync();
            this.started.compareAndSet(false,true);
            log.info("Server has [init],Listened Port:{}",LinkConfigConstant.LISTENING_PORT);
            //等待服务端口关闭
            channelFuture.channel().closeFuture().addListener(f->{
                shutdown();
                log.info("Server has shutdown");
            });
        } catch (InterruptedException e) {
            log.info("NettyServer may has error with 109l");
            log.error("NettyServer has error,message:{}",e.getMessage());
        }
    }

    @Override
    public void shutdown() {
        //未启动处理
        if(!started.get()){
            log.info("Server shutdown may error!Server is not started!");
            return;
        }
        //关闭服务器
        if(bossEventLoopGroup != null){
            //shutdownGracefully是Netty优雅关闭的核心，会确保所有资源都被释放，以及处理完所有请求
            bossEventLoopGroup.shutdownGracefully();
        }
        if(workEventLoopGroup != null){
            workEventLoopGroup.shutdownGracefully();
        }
    }

    @Override
    public boolean isStarted() {
        return this.started.get();
    }
}
