package com.GinElmaC.NettyServer.Monitor;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class NodeDetail {
    private Integer machineId;
    private String serverName;
    private String hostIp;
    private Integer port;
    private String nettyMode;
    private Integer bossThreadCount;
    private Integer workerThreadCount;
    private String status;
    private LocalDateTime startTime;
    private Long uptimeSeconds;
    private Integer connectionCount;
    private Long totalMessageCount;
    private LocalDateTime lastHeartbeatTime;
    private String lastErrorMessage;
    private Double cpuUsage;
    private Long heapUsed;
    private Long heapMax;
    private Integer threadCount;
    private Long gcCount;
    private Long gcTimeMs;
}
