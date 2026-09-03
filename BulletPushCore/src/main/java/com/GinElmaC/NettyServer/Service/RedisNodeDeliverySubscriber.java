package com.GinElmaC.NettyServer.Service;

import com.GinElmaC.NettyServer.Config.LinkConfig;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.redis.RedisClient;
import redis.clients.jedis.JedisPubSub;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 订阅本机专属 Redis 下行频道。
 * 只有目标推送节点会收到远程转发消息，避免集群所有节点重复向房间广播。
 */
public class RedisNodeDeliverySubscriber {
    private static final Log log = LogFactory.getLog(RedisNodeDeliverySubscriber.class);

    private final RoomMessageDeliveryService deliveryService;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile JedisPubSub subscriber;
    private Thread worker;

    public RedisNodeDeliverySubscriber(RoomMessageDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        int machineId = currentMachineId();
        subscriber = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                try {
                    deliveryService.deliverFromNodeChannel(message);
                } catch (Exception e) {
                    log.Error("REDIS_NODE_DELIVERY_FAILED, channel:{}", channel, e);
                }
            }
        };
        worker = new Thread(() -> subscribe(machineId), "redis-node-delivery-subscriber");
        worker.setDaemon(true);
        worker.start();
    }

    private void subscribe(int machineId) {
        while (running.get()) {
            try {
                RedisClient.subscribeNodeDelivery(machineId, subscriber);
            } catch (Exception e) {
                if (running.get()) {
                    log.Error("REDIS_NODE_DELIVERY_SUBSCRIBE_FAILED, machineId:{}", machineId, e);
                    sleepBeforeRetry();
                }
            }
        }
    }

    public synchronized void stop() {
        running.set(false);
        if (subscriber != null) {
            subscriber.unsubscribe();
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int currentMachineId() {
        if (LinkConfig.MACHINE_ID == null) {
            throw new IllegalStateException("push machine id is unavailable");
        }
        return LinkConfig.MACHINE_ID;
    }
}
