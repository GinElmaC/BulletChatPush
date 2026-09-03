package com.GinElmaC.NettyServer.Handler;

import com.GinElmaC.NettyServer.Service.KafkaUpstreamMessageProducer;
import com.GinElmaC.NettyServer.Service.RoomMessageDeliveryService;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.log.LogIdGenerator;
import com.GinElmaC.utils.JsonUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.time.LocalDateTime;

/**
 * 浏览器 WebSocket 接入处理器。
 * 上行消息只在 Kafka 确认写入后返回 MESSAGE_ACCEPTED，业务处理结果由下行 Topic 链路负责。
 */
public class WebSocketGatewayHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    private static final Log log = LogFactory.getLog(WebSocketGatewayHandler.class);
    private final KafkaUpstreamMessageProducer kafkaProducer;
    private final RoomMessageDeliveryService deliveryService;

    public WebSocketGatewayHandler(
            KafkaUpstreamMessageProducer kafkaProducer,
            RoomMessageDeliveryService deliveryService
    ) {
        this.kafkaProducer = kafkaProducer;
        this.deliveryService = deliveryService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        try {
            JsonObject request = JsonParser.parseString(frame.text()).getAsJsonObject();
            String messageId = requiredText(request, "messageId");
            long roomId = requiredLong(request, "roomId");
            String content = requiredText(request, "content");
            String logId = LogIdGenerator.next();
            Long userId = optionalLong(request, "userId");
            LogContext logContext = LogContext.create()
                    .traceId(logId)
                    .msgId(messageId)
                    .uid(userId)
                    .roomId(roomId)
                    .put("channelId", ctx.channel().id().asShortText())
                    .put("remoteAddress", String.valueOf(ctx.channel().remoteAddress()))
                    .put("contentLength", content.length());
            log.Info(logContext, "WEBSOCKET_MESSAGE_RECEIVED");
            // 注册房间本机连接并刷新 Redis 节点租约，确保后续 message_out 可路由回该连接所在节点。
            deliveryService.registerClientRoom(roomId, ctx.channel());

            GatewayUpstreamMessage message = new GatewayUpstreamMessage(
                    logId,
                    messageId,
                    userId,
                    roomId,
                    content,
                    LocalDateTime.now().toString()
            );
            kafkaProducer.send(String.valueOf(roomId), JsonUtil.toJson(message), (metadata, throwable) -> ctx.executor().execute(() -> {
                if (throwable != null) {
                    log.Error(logContext, "KAFKA_UPSTREAM_PUBLISH_FAILED", throwable);
                    write(ctx, new GatewayResponse("MESSAGE_REJECTED", logId, messageId, "消息发送失败"));
                    return;
                }
                log.Info(logContext
                                .put("topic", metadata.topic())
                                .put("partition", metadata.partition())
                                .put("offset", metadata.offset()),
                        "KAFKA_UPSTREAM_PUBLISHED");
                write(ctx, new GatewayResponse("MESSAGE_ACCEPTED", logId, messageId, "消息已进入业务处理队列"));
            }));
        } catch (Exception e) {
            log.Warn(connectionLogContext(ctx), "WEBSOCKET_MESSAGE_REJECTED, reason:{}", e.getMessage());
            write(ctx, new GatewayResponse("MESSAGE_REJECTED", null, null, "消息格式错误或服务未就绪"));
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        log.Info(connectionLogContext(ctx), "WEBSOCKET_CHANNEL_ACTIVE");
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 本机房间已经没有连接时才从 Redis 移除本机成员，其他节点房间成员不受影响。
        deliveryService.unregisterClient(ctx.channel());
        log.Info(connectionLogContext(ctx), "WEBSOCKET_CHANNEL_INACTIVE");
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.Error(connectionLogContext(ctx), "WEBSOCKET_CHANNEL_EXCEPTION", cause);
        ctx.close();
    }

    private void write(ChannelHandlerContext ctx, GatewayResponse response) {
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(new TextWebSocketFrame(JsonUtil.toJson(response)));
        }
    }

    private String requiredText(JsonObject request, String key) {
        if (!request.has(key) || request.get(key).isJsonNull()) {
            throw new IllegalArgumentException(key + " is required");
        }
        String value = request.get(key).getAsString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " is empty");
        }
        return value;
    }

    private long requiredLong(JsonObject request, String key) {
        if (!request.has(key) || request.get(key).isJsonNull()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return request.get(key).getAsLong();
    }

    private Long optionalLong(JsonObject request, String key) {
        if (!request.has(key) || request.get(key).isJsonNull()) {
            return null;
        }
        return request.get(key).getAsLong();
    }

    private LogContext connectionLogContext(ChannelHandlerContext ctx) {
        return LogContext.create()
                .traceId(LogIdGenerator.next())
                .put("channelId", ctx.channel().id().asShortText())
                .put("remoteAddress", String.valueOf(ctx.channel().remoteAddress()));
    }

    private record GatewayUpstreamMessage(
            String logId,
            String messageId,
            Long userId,
            long roomId,
            String content,
            String sentAt
    ) {
    }

    private record GatewayResponse(String type, String logId, String messageId, String message) {
    }
}
