package com.GinElmaC.NettyServer.Handler;

import com.GinElmaC.constant.ProtoConstant;
import com.GinElmaC.domain.model.CompleteMessage;
import com.GinElmaC.domain.model.MessageBody;
import com.GinElmaC.domain.protobuf.PacketBody;
import com.GinElmaC.domain.protobuf.PacketHeader;
import com.GinElmaC.utils.JsonUtil;
import com.GinElmaC.utils.ProtoUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 自定义Netty解码器,通过继承Netty自带的ByteToMessageDecoder来实现
 */
public class MessageProtocolDecoder extends ByteToMessageDecoder {

    private static final Logger log = LoggerFactory.getLogger(MessageProtocolDecoder.class);

    /**
     * 这个方法的触发时机可以看我的博客
     * 方法签名
     * @param channelHandlerContext 当前channelhandler所在pipeline的上下文，可以通过他获取channel、EventLoop以及其他Handler登，也可以出发一些事件
     * @param in 数据缓冲区
     * @param list 用于输出解码后的消息对象
     */
    @Override
    protected void decode(ChannelHandlerContext channelHandlerContext, ByteBuf in, List<Object> list) throws Exception {
        //判断包边界，如果小于包边界，就不用读了
        if(in.readableBytes()< ProtoConstant.BASE_PACKET_SIZE){
            return;
        }
        //记录当前读指针位置
        in.markReaderIndex();

        //读取固定字段
        short magic = in.readShort();
        short version = in.readShort();
        int headerLength = in.readInt();
        int dataLength = in.readInt();

        //校验
        if(magic != ProtoConstant.MAGIC){
            channelHandlerContext.close();
            return;
        }

        //校验版本
        if(version != ProtoConstant.VERSION){
            channelHandlerContext.close();
            return;
        }

        //数据判断，如果不足包头包体返回
        if(in.readableBytes() < headerLength+dataLength){
            in.resetReaderIndex();
            return;
        }

        //读取包头和包体
        ByteBuf headerBuf = in.readBytes(headerLength);
        ByteBuf dataBuf = in.readBytes(dataLength);

        //解析包头和包体，构造byte数组
        byte[] headerBytes = new byte[headerLength];
        byte[] dataBytes = new byte[dataLength];

        //从缓冲区读数据到数组
        headerBuf.readBytes(headerBytes);
        dataBuf.readBytes(dataBytes);

        //反序列化，将字节数组转化为java对象，parseFrom方法是由proto自带的
        PacketHeader packetHeader = PacketHeader.parseFrom(headerBytes);
        log.info("[MessageProtocolDecoder]解析包头：{}",packetHeader);

        //对dataBytes解密
        if(packetHeader.getEncryption() == 1){
            //bytes先变为string，然后变为bytes
            String dataString = dataBytes.toString();
            dataBytes = ProtoUtil.deAES(dataString,ProtoConstant.DEFAULT_SECRETKEY.getBytes());
        }
        //解压缩
        if(packetHeader.getCompression() == 1){
            dataBytes = ProtoUtil.decompress(dataBytes);
        }

        //处理data二进制数组 二进制数组->protobuf对象->json字符串->MessageBody
        PacketBody packetBody = PacketBody.parseFrom(dataBytes);
        String dataJson = packetBody.getData();

        //异常处理
        MessageBody messageBody = null;
        try {
            messageBody = JsonUtil.fromJson(dataJson, MessageBody.class);
        } catch (Exception e) {
            channelHandlerContext.writeAndFlush("Invalid Json fromat");
        }

        list.add(new CompleteMessage(packetHeader,messageBody));
    }

}
