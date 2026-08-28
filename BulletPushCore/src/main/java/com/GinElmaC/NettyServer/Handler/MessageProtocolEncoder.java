package com.GinElmaC.NettyServer.Handler;

import com.GinElmaC.constant.ProtoConstant;
import com.GinElmaC.domain.model.CompleteMessage;
import com.GinElmaC.domain.model.MessageBody;
import com.GinElmaC.domain.protobuf.PacketBody;
import com.GinElmaC.domain.protobuf.PacketHeader;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.utils.JsonUtil;
import com.GinElmaC.utils.ProtoUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

public class MessageProtocolEncoder extends MessageToByteEncoder<CompleteMessage> {
    private static final Log log = LogFactory.getLog(MessageProtocolEncoder.class);

    @Override
    protected void encode(ChannelHandlerContext channelHandlerContext, CompleteMessage completeMessage, ByteBuf out) throws Exception {
        LogContext logContext = completeMessage.createLogContext()
                .put("channelId", channelHandlerContext.channel().id().asShortText())
                .put("remoteAddress", String.valueOf(channelHandlerContext.channel().remoteAddress()));
        log.Info(logContext, "MESSAGE_OUTBOUND_ENCODE_STARTED");

        //包边界处理
        out.writeShort(ProtoConstant.MAGIC);
        out.writeShort(ProtoConstant.VERSION);

        //从完整的message中拆出 包头和包体
        PacketHeader packetHeader = completeMessage.getPacketHeader();
        MessageBody messageBody = completeMessage.getMessageBody();
        // 将当前链路 LogID 写入 JSON 消息体，确保节点间转发后仍可按同一个 LogID 查询。
        messageBody.setLogId(completeMessage.getOrCreateLogId());

        byte[] heardBytes = packetHeader.toByteArray();;

        //总体的转化方式：message对象->json字符串->proto对象->二进制数组
        String dataJson = JsonUtil.toJson(messageBody);
        PacketBody packetBody = PacketBody.newBuilder().setData(dataJson).build();
        byte[] dataBytes = packetBody.toByteArray();

        //序列化包头长度
        out.writeInt(heardBytes.length);

        //判断是否有压缩加密
        byte compression = (byte)packetHeader.getCompression();
        if(compression == 1){
            //使用gzip压缩
            dataBytes = ProtoUtil.compress(dataBytes);
        }
        byte encryption = (byte)packetHeader.getEncryption();
        if(encryption == 1){
            //使用aes加密
            dataBytes = ProtoUtil.doAES(dataBytes,ProtoConstant.DEFAULT_SECRETKEY.getBytes()).getBytes();
        }

        //序列化包体长度
        out.writeInt(dataBytes.length);

        //序列化包头
        out.writeBytes(heardBytes);
        out.writeBytes(dataBytes);
        log.Info(logContext
                        .put("headerLength", heardBytes.length)
                        .put("dataLength", dataBytes.length)
                        .put("compression", compression)
                        .put("encryption", encryption),
                "MESSAGE_OUTBOUND_ENCODED");
    }
}
