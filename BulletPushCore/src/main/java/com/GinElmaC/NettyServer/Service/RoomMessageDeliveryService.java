package com.GinElmaC.NettyServer.Service;

import com.GinElmaC.NettyServer.Config.LinkConfig;
import com.GinElmaC.NettyServer.Config.WebSocketGatewayConfig;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.log.LogIdGenerator;
import com.GinElmaC.redis.RedisClient;
import com.GinElmaC.utils.JsonUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.Set;

/**
 * 下行消息路由服务。
 * message_out 由任意一个推送节点消费后，通过 Redis 房间成员路由定位本机或其他持有连接的节点。
 */
public class RoomMessageDeliveryService {
    private static final Log log = LogFactory.getLog(RoomMessageDeliveryService.class);

    private final RoomChannelRegistry roomChannelRegistry;

    public RoomMessageDeliveryService(RoomChannelRegistry roomChannelRegistry) {
        this.roomChannelRegistry = roomChannelRegistry;
    }

    /**
     * 用户首次向某房间发送消息时，将该 WebSocket 连接登记为房间成员并创建节点租约。
     */
    public void registerClientRoom(long roomId, Channel channel) {
        roomChannelRegistry.register(roomId, channel);
        RedisClient.registerRoomNode(roomId, currentMachineId(), WebSocketGatewayConfig.ROOM_ROUTE_LEASE_SECONDS);
    }

    /**
     * 连接断开后仅移除本机已空房间的 Redis 节点成员，不影响同房间其他节点。
     */
    public void unregisterClient(Channel channel) {
        for (Long roomId : roomChannelRegistry.unregister(channel)) {
            RedisClient.unregisterRoomNode(roomId, currentMachineId());
            log.Info(LogContext.create()
                            .traceId(LogIdGenerator.next())
                            .roomId(roomId)
                            .put("machineId", currentMachineId()),
                    "ROOM_NODE_ROUTE_REMOVED");
        }
    }

    /**
     * 定时刷新本机所有活跃房间的成员租约，防止长连接在没有新上行消息时被错误过期。
     */
    public void refreshLocalRoomRoutes() {
        for (Long roomId : roomChannelRegistry.activeRoomIds()) {
            RedisClient.registerRoomNode(roomId, currentMachineId(), WebSocketGatewayConfig.ROOM_ROUTE_LEASE_SECONDS);
        }
    }

    /**
     * 处理 message_out 记录。当前消费节点只负责一次路由决策：
     * 本机目标直接广播，远程目标发布到该机器独占 Redis 频道。
     */
    public void deliverFromKafka(String messageKey, String sourcePayload) {
        DownstreamMessage message = parseDownstreamMessage(sourcePayload);
        LogContext logContext = createLogContext(message, messageKey);
        Set<Integer> targetNodes = RedisClient.findActiveRoomNodes(message.roomId());
        if (targetNodes.isEmpty()) {
            log.Warn(logContext, "ROOM_ROUTE_NOT_FOUND");
            return;
        }

        String deliveryPayload = JsonUtil.toJson(message);
        for (Integer targetMachineId : targetNodes) {
            if (targetMachineId == currentMachineId()) {
                int channelCount = broadcastLocal(message);
                log.Info(logContext.put("targetMachineId", targetMachineId).put("channelCount", channelCount),
                        "MESSAGE_OUT_LOCAL_DELIVERED");
                continue;
            }

            RedisClient.publishNodeDelivery(targetMachineId, deliveryPayload);
            log.Info(logContext.put("targetMachineId", targetMachineId),
                    "MESSAGE_OUT_REMOTE_FORWARDED");
        }
    }

    /**
     * 目标节点收到 Redis 专属频道消息后，仅广播给本机对应房间连接。
     */
    public void deliverFromNodeChannel(String deliveryPayload) {
        DownstreamMessage message = JsonUtil.fromJson(deliveryPayload, DownstreamMessage.class);
        LogContext logContext = createLogContext(message, null)
                .put("machineId", currentMachineId());
        int channelCount = broadcastLocal(message);
        log.Info(logContext.put("channelCount", channelCount), "MESSAGE_OUT_REMOTE_DELIVERED");
    }

    private int broadcastLocal(DownstreamMessage message) {
        return roomChannelRegistry.broadcast(
                message.roomId(),
                new TextWebSocketFrame(JsonUtil.toJson(message))
        );
    }

    private DownstreamMessage parseDownstreamMessage(String sourcePayload) {
        try {
            JsonObject source = JsonParser.parseString(sourcePayload).getAsJsonObject();
            long roomId = requiredLong(source, "roomId");
            String logId = optionalString(source, "logId");
            String messageId = optionalString(source, "messageId");
            Long userId = optionalLong(source, "userId");
            String content = optionalString(source, "content");
            return new DownstreamMessage(
                    "MESSAGE_DELIVERED",
                    logId == null ? LogIdGenerator.next() : logId,
                    messageId,
                    roomId,
                    userId,
                    content,
                    "消息已处理并下发到房间"
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("message_out payload is invalid", e);
        }
    }

    private LogContext createLogContext(DownstreamMessage message, String messageKey) {
        LogContext context = LogContext.create()
                .traceId(message.logId())
                .msgId(message.messageId())
                .uid(message.userId())
                .roomId(message.roomId());
        if (messageKey != null) {
            context.put("messageKey", messageKey);
        }
        return context;
    }

    private int currentMachineId() {
        if (LinkConfig.MACHINE_ID == null) {
            throw new IllegalStateException("push machine id is unavailable");
        }
        return LinkConfig.MACHINE_ID;
    }

    private String optionalString(JsonObject source, String fieldName) {
        JsonElement value = source.get(fieldName);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private Long optionalLong(JsonObject source, String fieldName) {
        JsonElement value = source.get(fieldName);
        return value == null || value.isJsonNull() ? null : value.getAsLong();
    }

    private long requiredLong(JsonObject source, String fieldName) {
        Long value = optionalLong(source, fieldName);
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + " is invalid");
        }
        return value;
    }

    /**
     * 对浏览器的下行格式。只暴露业务消息与链路标识，不暴露节点或 Redis 路由内部信息。
     */
    private record DownstreamMessage(
            String type,
            String logId,
            String messageId,
            long roomId,
            Long userId,
            String content,
            String message
    ) {
    }
}
