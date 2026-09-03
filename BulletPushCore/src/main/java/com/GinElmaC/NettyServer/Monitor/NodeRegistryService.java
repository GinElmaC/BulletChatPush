package com.GinElmaC.NettyServer.Monitor;

import com.GinElmaC.NettyServer.Config.LinkConfig;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.redis.RedisClient;
import com.google.gson.JsonObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推送节点注册中心上报器。
 * 节点快照写入 Redis 并设置 5 分钟 TTL，管理后台通过 Redis 判断节点是否仍然存活。
 */
public class NodeRegistryService {
    public static final int NODE_SNAPSHOT_TTL_SECONDS = 300;
    private static final int NODE_SNAPSHOT_REFRESH_SECONDS = 290;
    private static final Log log = LogFactory.getLog(NodeRegistryService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "push-node-registry-reporter");
            thread.setDaemon(true);
            return thread;
        });
        // 启动后立即注册，之后约 5 分钟刷新一次；刷新间隔略小于 TTL，避免调度抖动导致误判离线。
        scheduler.scheduleAtFixedRate(this::refreshSafely, 0, NODE_SNAPSHOT_REFRESH_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        refreshSafely();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void refreshSafely() {
        if (LinkConfig.MACHINE_ID == null) {
            return;
        }
        try {
            NodeDetail snapshot = NodeMetrics.getInstance().snapshot();
            RedisClient.refreshPushNodeSnapshot(
                    LinkConfig.MACHINE_ID,
                    snapshotJson(snapshot),
                    NODE_SNAPSHOT_TTL_SECONDS
            );
            log.Info(LogContext.create()
                            .put("machineId", LinkConfig.MACHINE_ID)
                            .put("nodeStatus", snapshot.getStatus())
                            .put("nodeSnapshotTtlSeconds", NODE_SNAPSHOT_TTL_SECONDS),
                    "PUSH_NODE_REGISTRY_REFRESHED");
        } catch (Exception e) {
            log.Warn(LogContext.create()
                            .put("machineId", LinkConfig.MACHINE_ID)
                            .put("errorType", e.getClass().getSimpleName()),
                    "PUSH_NODE_REGISTRY_REFRESH_FAILED");
        }
    }

    private String snapshotJson(NodeDetail detail) {
        JsonObject json = new JsonObject();
        json.addProperty("machineId", detail.getMachineId());
        json.addProperty("serverName", detail.getServerName());
        json.addProperty("hostIp", detail.getHostIp());
        json.addProperty("port", detail.getPort());
        json.addProperty("nettyMode", detail.getNettyMode());
        json.addProperty("bossThreadCount", detail.getBossThreadCount());
        json.addProperty("workerThreadCount", detail.getWorkerThreadCount());
        json.addProperty("status", detail.getStatus());
        json.addProperty("startTime", detail.getStartTime() == null ? null : detail.getStartTime().toString());
        json.addProperty("uptimeSeconds", detail.getUptimeSeconds());
        json.addProperty("connectionCount", detail.getConnectionCount());
        json.addProperty("totalMessageCount", detail.getTotalMessageCount());
        json.addProperty("lastHeartbeatTime",
                detail.getLastHeartbeatTime() == null ? null : detail.getLastHeartbeatTime().toString());
        json.addProperty("lastErrorMessage", detail.getLastErrorMessage());
        json.addProperty("cpuUsage", detail.getCpuUsage());
        json.addProperty("heapUsed", detail.getHeapUsed());
        json.addProperty("heapMax", detail.getHeapMax());
        json.addProperty("threadCount", detail.getThreadCount());
        json.addProperty("gcCount", detail.getGcCount());
        json.addProperty("gcTimeMs", detail.getGcTimeMs());
        return json.toString();
    }
}
