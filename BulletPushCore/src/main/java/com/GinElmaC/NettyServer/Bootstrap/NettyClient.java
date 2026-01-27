package com.GinElmaC.NettyServer.Bootstrap;

import com.GinElmaC.NettyServer.Config.NettyConfig;
import com.GinElmaC.utils.SystemUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.incubator.channel.uring.IOUringEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * netty客户端
 */
public class NettyClient {

    private final String host;
    private final int port;

    private Bootstrap bootstrap;
    private EventLoopGroup eventExecutors;
    private Channel channel;

    public NettyClient(String host,int port){
        this.host = host;
        this.port = port;
    }

    /**
     * 初始化方法
     */
    private void init(){
        this.bootstrap = new Bootstrap();
        switch (SystemUtils.ChargeMode()){
            case 0:
                eventExecutors = new NioEventLoopGroup(new DefaultThreadFactory("nio-nettyClient"));
                break;
            case 1:
                eventExecutors = new EpollEventLoopGroup(new DefaultThreadFactory("epoll-nettyClient"));
                break;
            case 2:
                eventExecutors = new IOUringEventLoopGroup(new DefaultThreadFactory("iouring-nettyClient"));
        }
    }

    /**
     * Client与服务器建立连接
     * @return
     */
    //TODO 心跳检测、断线重连
    public Channel connect(){

        return null;
    }

    public void send(){

    }
}
