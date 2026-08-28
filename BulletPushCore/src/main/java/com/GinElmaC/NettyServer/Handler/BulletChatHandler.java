package com.GinElmaC.NettyServer.Handler;

import com.GinElmaC.NettyServer.Factory.MessageServiceFactory;
import com.GinElmaC.NettyServer.Monitor.NodeMetrics;
import com.GinElmaC.NettyServer.Service.AbstractMessageService;
import com.GinElmaC.domain.enums.MessageType;
import com.GinElmaC.domain.model.CompleteMessage;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.log.LogIdGenerator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

/**
 * 读取完整的信息并进行处理
 */
public class BulletChatHandler extends SimpleChannelInboundHandler<CompleteMessage> {
    private static final Log log = LogFactory.getLog(BulletChatHandler.class);

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        NodeMetrics.getInstance().channelActive();
        log.Info(connectionLogContext(ctx), "CHANNEL_ACTIVE");
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NodeMetrics.getInstance().channelInactive();
        log.Info(connectionLogContext(ctx), "CHANNEL_INACTIVE");
        super.channelInactive(ctx);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, CompleteMessage completeMessage) throws Exception {
        NodeMetrics.getInstance().messageReceived();
        MessageType messageType = MessageType.fromType((short)completeMessage.getPacketHeader().getMessageType());
        LogContext logContext = completeMessage.createLogContext()
                .put("channelId", channelHandlerContext.channel().id().asShortText())
                .put("remoteAddress", String.valueOf(channelHandlerContext.channel().remoteAddress()))
                .put("messageTypeName", messageType == null ? "UNKNOWN" : messageType.name());
        log.Info(logContext, "MESSAGE_INBOUND_RECEIVED");

        if(messageType == null){
            log.Warn(logContext, "MESSAGE_TYPE_UNSUPPORTED");
            //说明在读的中途停止了传输，这时候就需要手动释放内存，防止内存泄漏
            ReferenceCountUtil.release(completeMessage);
            return;
        }
        if(messageType == MessageType.HEARTBEAT_MESSAGE){
            NodeMetrics.getInstance().heartbeat();
            log.Info(logContext, "HEARTBEAT_RECEIVED");
        }
        try {
            //获取适配的处理器
            AbstractMessageService<CompleteMessage> handler = MessageServiceFactory.getService(messageType);
            log.Info(logContext.put("service", handler.getClass().getSimpleName()), "MESSAGE_SERVICE_DISPATCHED");
            //处理信息
            handler.doMessage(completeMessage);
            log.Info(logContext, "MESSAGE_SERVICE_HANDLED");
        } catch (Exception e) {
            log.Error(logContext, "MESSAGE_SERVICE_HANDLE_FAILED", e);
            throw e;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        NodeMetrics.getInstance().recordError(cause);
        log.Error(connectionLogContext(ctx), "CHANNEL_EXCEPTION", cause);
        super.exceptionCaught(ctx, cause);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idleStateEvent && idleStateEvent.state() == IdleState.READER_IDLE) {
            log.Warn(connectionLogContext(ctx), "HEARTBEAT_TIMEOUT");
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * 连接级事件没有业务消息可继承，因此单独生成 LogID。
     */
    private LogContext connectionLogContext(ChannelHandlerContext ctx) {
        return LogContext.create()
                .traceId(LogIdGenerator.next())
                .put("channelId", ctx.channel().id().asShortText())
                .put("remoteAddress", String.valueOf(ctx.channel().remoteAddress()));
    }
}
