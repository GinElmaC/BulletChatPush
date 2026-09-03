package com.GinElmaC.NettyServer.Service;

import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 当前推送节点的房间连接注册表。
 * 仅保存本机 Channel；跨节点成员由 Redis 路由表维护，避免将 Netty Channel 放入分布式存储。
 */
public class RoomChannelRegistry {
    private final ConcurrentMap<Long, ChannelGroup> roomChannels = new ConcurrentHashMap<>();
    private final ConcurrentMap<ChannelId, Set<Long>> channelRooms = new ConcurrentHashMap<>();

    /**
     * 将 WebSocket Channel 加入房间。同一连接可登记多个房间，断连时会一次性清理。
     */
    public void register(long roomId, Channel channel) {
        ChannelGroup channels = roomChannels.computeIfAbsent(
                roomId,
                ignored -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE)
        );
        channels.add(channel);
        channelRooms.computeIfAbsent(channel.id(), ignored -> ConcurrentHashMap.newKeySet()).add(roomId);
    }

    /**
     * 移除连接持有的全部房间，返回因该连接断开而变为空的本机房间。
     */
    public Set<Long> unregister(Channel channel) {
        Set<Long> rooms = channelRooms.remove(channel.id());
        if (rooms == null || rooms.isEmpty()) {
            return Collections.emptySet();
        }

        Set<Long> emptyRooms = new HashSet<>();
        for (Long roomId : rooms) {
            ChannelGroup channels = roomChannels.get(roomId);
            if (channels == null) {
                continue;
            }
            channels.remove(channel);
            if (channels.isEmpty() && roomChannels.remove(roomId, channels)) {
                emptyRooms.add(roomId);
            }
        }
        return emptyRooms;
    }

    /**
     * 将已构造好的 TextWebSocketFrame 广播给本机房间内所有活跃连接。
     */
    public int broadcast(long roomId, Object message) {
        ChannelGroup channels = roomChannels.get(roomId);
        if (channels == null || channels.isEmpty()) {
            return 0;
        }
        channels.writeAndFlush(message);
        return channels.size();
    }

    /**
     * 返回需要续租的本机活跃房间快照，避免遍历期间受并发连接变化影响。
     */
    public Set<Long> activeRoomIds() {
        return Set.copyOf(roomChannels.keySet());
    }
}
