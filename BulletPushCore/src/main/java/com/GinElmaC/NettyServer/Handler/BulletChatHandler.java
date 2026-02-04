package com.GinElmaC.NettyServer.Handler;

import com.GinElmaC.NettyServer.Factory.MessageServiceFactory;
import com.GinElmaC.NettyServer.Service.AbstractMessageService;
import com.GinElmaC.domain.enums.MessageType;
import com.GinElmaC.domain.model.CompleteMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.util.ReferenceCountUtil;

/**
 * 读取完整的信息并进行处理
 */
public class BulletChatHandler extends SimpleChannelInboundHandler<CompleteMessage> {
    @Override
    protected void channelRead0(ChannelHandlerContext channelHandlerContext, CompleteMessage completeMessage) throws Exception {
        MessageType messageType = MessageType.fromType((short)completeMessage.getPacketHeader().getMessageType());
        if(messageType == null){
             //说明在读的中途停止了传输，这时候就需要手动释放内存，防止内存泄漏
            ReferenceCountUtil.release(completeMessage);
            return;
        }
        //获取适配的处理器
        AbstractMessageService<CompleteMessage> handler = MessageServiceFactory.getService(messageType);
        //处理信息
        handler.doMessage(completeMessage);
    }
}
