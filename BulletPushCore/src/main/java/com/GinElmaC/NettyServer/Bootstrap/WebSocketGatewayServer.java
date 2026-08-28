package com.GinElmaC.NettyServer.Bootstrap;

import com.GinElmaC.NettyServer.Config.WebSocketGatewayConfig;
import com.GinElmaC.NettyServer.Handler.WebSocketGatewayHandler;
import com.GinElmaC.NettyServer.Service.KafkaUpstreamMessageProducer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * 浏览器 WebSocket Gateway。
 * 与现有自定义 TCP 协议服务独立监听，浏览器只能通过 /ws 接入。
 */
public class WebSocketGatewayServer {
    private final KafkaUpstreamMessageProducer kafkaProducer = new KafkaUpstreamMessageProducer();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public synchronized void start() {
        if (serverChannel != null && serverChannel.isOpen()) {
            return;
        }
        bossGroup = new NioEventLoopGroup(1, new DefaultThreadFactory("websocket-gateway-boss"));
        workerGroup = new NioEventLoopGroup(0, new DefaultThreadFactory("websocket-gateway-worker"));
        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            serverChannel = bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(64 * 1024))
                                    .addLast(new WebSocketServerProtocolHandler("/ws", null, true))
                                    .addLast(new WebSocketGatewayHandler(kafkaProducer));
                        }
                    })
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .bind(WebSocketGatewayConfig.PORT)
                    .sync()
                    .channel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop();
            throw new IllegalStateException("start websocket gateway interrupted", e);
        } catch (Exception e) {
            stop();
            throw new IllegalStateException("start websocket gateway failed", e);
        }
    }

    public synchronized void stop() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().awaitUninterruptibly();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().awaitUninterruptibly();
            workerGroup = null;
        }
        kafkaProducer.close();
    }
}
