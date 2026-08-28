package com.GinElmaC;

import com.GinElmaC.NettyServer.Agent.LogAnalysisAgent;
import com.GinElmaC.NettyServer.Agent.LogAnalysisResult;
import com.GinElmaC.NettyServer.Agent.PushAgentAnalyzer;
import com.GinElmaC.NettyServer.Config.WebSocketGatewayConfig;
import com.GinElmaC.NettyServer.Monitor.NodeDetail;
import com.GinElmaC.NettyServer.Monitor.NodeMetrics;
import com.GinElmaC.log.LogLevel;
import com.GinElmaC.log.PushLogRecord;
import com.GinElmaC.log.PushLogRepository;
import com.GinElmaC.log.PushLogSearchParam;
import com.GinElmaC.utils.JsonUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 推送中台管理接口。
 * 只提供当前实例的真实 NodeMetrics、push_log 查询和 LogID 智能分析，不构造虚拟节点或日志。
 */
public class AdminManagementServer {
    private static final int DEFAULT_PORT = 9090;

    private final PushLogRepository pushLogRepository = new PushLogRepository();
    private final LogAnalysisAgent logAnalysisAgent = new LogAnalysisAgent();
    private final PushAgentAnalyzer pushAgentAnalyzer = new PushAgentAnalyzer();
    // 管理接口与 Netty 业务线程池隔离，避免慢日志分析占用消息处理线程。
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private HttpServer server;

    public void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(managementPort()), 0);
            server.createContext("/admin/api/nodes", this::handleNodes);
            server.createContext("/admin/api/logs", this::handleLogs);
            server.createContext("/admin/api/log-analysis", this::handleLogAnalysis);
            server.createContext("/admin/api/node-analysis", this::handleNodeAnalysis);
            server.createContext("/admin/api/client-nodes", this::handleClientNodes);
            server.setExecutor(executor);
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException("start admin management server failed", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        executor.shutdownNow();
    }

    private void handleNodes(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        NodeDetail nodeDetail = NodeMetrics.getInstance().snapshot();
        writeJson(exchange, 200, List.of(toNodeResponse(nodeDetail)));
    }

    private void handleLogs(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            Map<String, String> query = queryParams(exchange);
            PushLogSearchParam param = new PushLogSearchParam()
                    .setLevel(parseLevel(query.get("level")))
                    .setKeyword(query.get("keyword"))
                    .setLimit(parseInt(query.get("limit"), 100))
                    .setOffset(parseInt(query.get("offset"), 0));
            List<PushLogRecord> records;
            if ("logId".equals(query.get("mode"))) {
                param.setLogId(required(query, "logId"));
                records = pushLogRepository.queryByLogId(param);
            } else if ("machineId".equals(query.get("mode"))) {
                param.setMachineId(parseInt(required(query, "machineId"), -1));
                if (param.getMachineId() < 0) {
                    throw new IllegalArgumentException("machineId is invalid");
                }
                records = pushLogRepository.queryByMachineId(param);
            } else {
                throw new IllegalArgumentException("mode must be logId or machineId");
            }
            writeJson(exchange, 200, records.stream().map(this::toLogResponse).toList());
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("message", "query logs failed"));
        }
    }

    private void handleLogAnalysis(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            Map<String, String> query = queryParams(exchange);
            LogAnalysisResult result = logAnalysisAgent.analyze(required(query, "logId"), query.get("model"));
            writeJson(exchange, 200, result);
        } catch (Exception e) {
            writeJson(exchange, 200, LogAnalysisResult.serviceAnalyzeFailed());
        }
    }

    private void handleNodeAnalysis(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            Map<String, String> query = queryParams(exchange);
            writeJson(exchange, 200, pushAgentAnalyzer.analyzeCurrentNode(query.get("model")));
        } catch (Exception e) {
            writeJson(exchange, 200, Map.of("conclusion", "服务分析失败"));
        }
    }

    /**
     * 返回显式配置的 WebSocket 节点列表。
     * 未配置时返回空列表，客户端不会猜测节点地址。
     */
    private void handleClientNodes(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        List<WebSocketNodeResponse> nodes = WebSocketGatewayConfig.publicNodeEndpoints().stream()
                .map(endpoint -> new WebSocketNodeResponse(endpoint))
                .toList();
        writeJson(exchange, 200, nodes);
    }

    private NodeResponse toNodeResponse(NodeDetail detail) {
        return new NodeResponse(
                detail.getMachineId(),
                detail.getServerName(),
                detail.getHostIp(),
                detail.getPort(),
                detail.getNettyMode(),
                detail.getBossThreadCount(),
                detail.getWorkerThreadCount(),
                detail.getStatus(),
                toText(detail.getStartTime()),
                detail.getUptimeSeconds(),
                detail.getConnectionCount(),
                detail.getTotalMessageCount(),
                toText(detail.getLastHeartbeatTime()),
                detail.getLastErrorMessage(),
                detail.getCpuUsage(),
                detail.getHeapUsed(),
                detail.getHeapMax(),
                detail.getThreadCount(),
                detail.getGcCount(),
                detail.getGcTimeMs()
        );
    }

    private LogResponse toLogResponse(PushLogRecord record) {
        return new LogResponse(
                record.getId(),
                toText(record.getLogTime()),
                record.getLevelName(),
                record.getMachineId(),
                record.getHostIp(),
                record.getLoggerName(),
                record.getTraceId(),
                record.getMessage(),
                record.getThrowable()
        );
    }

    private Map<String, String> queryParams(HttpExchange exchange) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(rawQuery.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(java.util.stream.Collectors.toMap(
                        pair -> decode(pair[0]),
                        pair -> pair.length == 2 ? decode(pair[1]) : "",
                        (left, right) -> right
                ));
    }

    private String required(Map<String, String> query, String key) {
        String value = query.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private LogLevel parseLevel(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) {
            return null;
        }
        return LogLevel.valueOf(value.toUpperCase());
    }

    private int parseInt(String value, int defaultValue) {
        try {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String toText(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private int managementPort() {
        return parseInt(System.getProperty("push.admin.port", System.getenv("PUSH_ADMIN_PORT")), DEFAULT_PORT);
    }

    private void writeJson(HttpExchange exchange, int status, Object response) throws IOException {
        byte[] body = JsonUtil.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private record NodeResponse(
            Integer machineId,
            String serverName,
            String hostIp,
            Integer port,
            String nettyMode,
            Integer bossThreadCount,
            Integer workerThreadCount,
            String status,
            String startTime,
            Long uptimeSeconds,
            Integer connectionCount,
            Long totalMessageCount,
            String lastHeartbeatTime,
            String lastErrorMessage,
            Double cpuUsage,
            Long heapUsed,
            Long heapMax,
            Integer threadCount,
            Long gcCount,
            Long gcTimeMs
    ) {
    }

    private record LogResponse(
            Long id,
            String time,
            String level,
            Integer machineId,
            String hostIp,
            String logger,
            String logId,
            String message,
            String throwable
    ) {
    }

    private record WebSocketNodeResponse(String endpoint) {
    }
}
