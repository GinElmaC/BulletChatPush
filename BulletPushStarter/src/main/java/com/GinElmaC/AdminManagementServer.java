package com.GinElmaC;

import com.GinElmaC.NettyServer.Agent.AgentAnalysisResult;
import com.GinElmaC.NettyServer.Agent.AgentConversationService;
import com.GinElmaC.NettyServer.Agent.AgentToolCall;
import com.GinElmaC.NettyServer.Agent.AgentToolDefinition;
import com.GinElmaC.NettyServer.Agent.AgentToolRegistry;
import com.GinElmaC.NettyServer.Agent.AgentToolRequest;
import com.GinElmaC.NettyServer.Agent.AgentTraceContext;
import com.GinElmaC.NettyServer.Agent.LogAnalysisAgent;
import com.GinElmaC.NettyServer.Agent.LogAnalysisResult;
import com.GinElmaC.NettyServer.Agent.PushAgentAnalyzer;
import com.GinElmaC.NettyServer.Config.WebSocketGatewayConfig;
import com.GinElmaC.NettyServer.Monitor.NodeDetail;
import com.GinElmaC.log.LogLevel;
import com.GinElmaC.log.PushLogRecord;
import com.GinElmaC.log.PushLogRepository;
import com.GinElmaC.log.PushLogSearchParam;
import com.GinElmaC.redis.RedisClient;
import com.GinElmaC.utils.JsonUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 推送中台管理接口。
 * 节点列表从 Redis 注册中心读取；当前实例快照只用于节点自身定时上报。
 */
public class AdminManagementServer {
    private static final int DEFAULT_PORT = 9090;

    private final PushLogRepository pushLogRepository = new PushLogRepository();
    private final LogAnalysisAgent logAnalysisAgent = new LogAnalysisAgent();
    private final PushAgentAnalyzer pushAgentAnalyzer = new PushAgentAnalyzer();
    private final AgentConversationService agentConversationService = new AgentConversationService();
    private final AgentToolRegistry mcpToolRegistry = new AgentToolRegistry(false);
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
            server.createContext("/admin/api/agent/session/start", this::handleAgentSessionStart);
            server.createContext("/admin/api/agent/session/chat", this::handleAgentSessionChat);
            server.createContext("/mcp", this::handleMcp);
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
        try {
            writeJson(exchange, 200, queryRegisteredNodes());
        } catch (Exception e) {
            writeJson(exchange, 500, Map.of("message", "query nodes failed"));
        }
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

    /**
     * 创建新的排障会话。
     * 当前支持节点会话和 LogID 会话，后续继续追问都依赖 conversationId。
     */
    private void handleAgentSessionStart(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        try {
            JsonObject request = readRequestJson(exchange);
            writeJson(exchange, 200, agentConversationService.startSession(
                    readText(request, "scopeType"),
                    readIntegerList(request, "machineIds"),
                    readText(request, "logId"),
                    readText(request, "model")
            ));
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("message", "start agent session failed"));
        }
    }

    /**
     * 继续某个会话的追问，并通过 SSE 将模型回复流式返回给前端。
     */
    private void handleAgentSessionChat(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        JsonObject request;
        try {
            request = readRequestJson(exchange);
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("message", "agent chat failed"));
            return;
        }
        String conversationId = readText(request, "conversationId");
        String message = readText(request, "message");
        if (conversationId == null || message == null) {
            writeJson(exchange, 400, Map.of("message", "agent chat failed"));
            return;
        }
        AgentSessionSseWriter sseWriter = startSse(exchange);
        try {
            agentConversationService.streamChat(conversationId, message, sseWriter);
        } catch (Exception e) {
            sseWriter.onFailed();
        }
    }

    /**
     * 暴露当前推送节点自己的 MCP JSON-RPC 工具入口。
     * 第一版仅支持 tools/list 和 tools/call，并且只导出本地只读诊断工具。
     */
    private void handleMcp(HttpExchange exchange) throws IOException {
        if (handlePreflight(exchange)) {
            return;
        }
        if (!"POST".equals(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("message", "method not allowed"));
            return;
        }
        JsonObject request;
        try {
            request = readRequestJson(exchange);
        } catch (Exception e) {
            writeRawJson(exchange, 400, mcpError(JsonNull.INSTANCE, -32700, "parse error").toString());
            return;
        }
        JsonElement id = request.has("id") ? request.get("id") : JsonNull.INSTANCE;
        try {
            String method = readText(request, "method");
            if ("initialize".equals(method)) {
                writeRawJson(exchange, 200, mcpResult(id, mcpInitialize()).toString());
            } else if ("notifications/initialized".equals(method)) {
                writeRawJson(exchange, 200, mcpResult(id, new JsonObject()).toString());
            } else if ("ping".equals(method)) {
                writeRawJson(exchange, 200, mcpResult(id, new JsonObject()).toString());
            } else if ("tools/list".equals(method)) {
                writeRawJson(exchange, 200, mcpResult(id, mcpToolsList()).toString());
            } else if ("tools/call".equals(method)) {
                writeRawJson(exchange, 200, mcpResult(id, mcpToolCall(exchange, request)).toString());
            } else {
                writeRawJson(exchange, 200, mcpError(id, -32601, "method not found").toString());
            }
        } catch (Exception e) {
            writeRawJson(exchange, 200, mcpError(id, -32000, "mcp request failed").toString());
        }
    }

    private JsonObject mcpInitialize() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", "2024-11-05");
        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);
        result.add("capabilities", capabilities);
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "bullet-chat-push");
        serverInfo.addProperty("version", "1.0.0");
        result.add("serverInfo", serverInfo);
        return result;
    }

    private JsonObject mcpToolsList() {
        JsonObject result = new JsonObject();
        JsonArray tools = new JsonArray();
        for (AgentToolDefinition definition : mcpToolRegistry.definitions()) {
            JsonObject tool = new JsonObject();
            tool.addProperty("name", definition.name());
            tool.addProperty("description", definition.description());
            tool.add("inputSchema", JsonParser.parseString(definition.parametersJson()).getAsJsonObject());
            tools.add(tool);
        }
        result.add("tools", tools);
        return result;
    }

    private JsonObject mcpToolCall(HttpExchange exchange, JsonObject request) {
        JsonObject params = request.getAsJsonObject("params");
        if (params == null) {
            throw new IllegalArgumentException("params is required");
        }
        String toolName = readText(params, "name");
        JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();
        String logId = readText(arguments, "logId");
        // 同系统 MCP Client 会透传 Trace；缺失或非法时由上下文生成新的独立 Trace。
        AgentTraceContext traceContext = AgentTraceContext.forIncomingMcpTrace(
                exchange.getRequestHeaders().getFirst("X-Agent-Trace-Id"),
                logId
        );
        String result = mcpToolRegistry.execute(
                new AgentToolCall("mcp-call-" + System.currentTimeMillis(), toolName, arguments.toString()),
                new AgentToolRequest(logId, traceContext)
        );

        JsonObject response = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", result);
        content.add(text);
        response.add("content", content);
        return response;
    }

    private JsonObject mcpResult(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        response.add("result", result);
        return response;
    }

    private JsonObject mcpError(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id == null ? JsonNull.INSTANCE : id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        return response;
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

    private List<NodeResponse> queryRegisteredNodes() {
        List<NodeResponse> nodes = new ArrayList<>();
        for (Map.Entry<String, String> entry : RedisClient.listPushNodeSnapshots().entrySet()) {
            Integer machineId = parseInt(entry.getKey(), -1);
            if (machineId == null || machineId < 0) {
                continue;
            }
            String snapshotJson = entry.getValue();
            if (snapshotJson == null || snapshotJson.isBlank()) {
                nodes.add(offlineNode(machineId));
                continue;
            }
            try {
                nodes.add(toNodeResponse(JsonParser.parseString(snapshotJson).getAsJsonObject()));
            } catch (Exception e) {
                nodes.add(offlineNode(machineId));
            }
        }
        nodes.sort(Comparator.comparing(NodeResponse::machineId));
        return nodes;
    }

    private NodeResponse toNodeResponse(JsonObject detail) {
        return new NodeResponse(
                readInteger(detail, "machineId"),
                readText(detail, "serverName"),
                readText(detail, "hostIp"),
                readInteger(detail, "port"),
                readText(detail, "nettyMode"),
                readInteger(detail, "bossThreadCount"),
                readInteger(detail, "workerThreadCount"),
                readText(detail, "status"),
                readText(detail, "startTime"),
                readLong(detail, "uptimeSeconds"),
                readInteger(detail, "connectionCount"),
                readLong(detail, "totalMessageCount"),
                readText(detail, "lastHeartbeatTime"),
                readText(detail, "lastErrorMessage"),
                readDouble(detail, "cpuUsage"),
                readLong(detail, "heapUsed"),
                readLong(detail, "heapMax"),
                readInteger(detail, "threadCount"),
                readLong(detail, "gcCount"),
                readLong(detail, "gcTimeMs")
        );
    }

    private NodeResponse offlineNode(Integer machineId) {
        return new NodeResponse(
                machineId,
                null,
                null,
                null,
                null,
                null,
                null,
                "OFFLINE",
                null,
                0L,
                0,
                0L,
                null,
                "节点快照已过期",
                null,
                null,
                null,
                null,
                null,
                null
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
                record.getSourceFilePath(),
                record.getSourceLine(),
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

    private JsonObject readRequestJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new JsonObject();
        }
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private String readText(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        String value = jsonObject.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer readInteger(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        try {
            return jsonObject.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private Long readLong(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        try {
            return jsonObject.get(key).getAsLong();
        } catch (Exception e) {
            return null;
        }
    }

    private Double readDouble(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        try {
            return jsonObject.get(key).getAsDouble();
        } catch (Exception e) {
            return null;
        }
    }

    private List<Integer> readIntegerList(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return List.of();
        }
        JsonArray jsonArray = jsonObject.getAsJsonArray(key);
        if (jsonArray == null) {
            return List.of();
        }
        List<Integer> values = new java.util.ArrayList<>();
        for (JsonElement element : jsonArray) {
            if (element == null || element.isJsonNull()) {
                continue;
            }
            values.add(element.getAsInt());
        }
        return values;
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
        applyCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private void writeRawJson(HttpExchange exchange, int status, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        applyCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private boolean handlePreflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) {
            return false;
        }
        applyCorsHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
        return true;
    }

    private AgentSessionSseWriter startSse(HttpExchange exchange) throws IOException {
        applyCorsHeaders(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        return new AgentSessionSseWriter(exchange);
    }

    private void applyCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    }

    /**
     * SSE 事件写入器。
     * 失败时只回传固定失败文案，避免把内部异常细节透给前端。
     */
    private static final class AgentSessionSseWriter implements AgentConversationService.AgentConversationStreamObserver {
        private final HttpExchange exchange;
        private boolean closed;

        private AgentSessionSseWriter(HttpExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public synchronized void onModel(String modelName) {
            if (closed) {
                return;
            }
            writeEvent("meta", Map.of("modelName", modelName));
        }

        @Override
        public synchronized void onStage(String title, String status, String detail) {
            if (closed) {
                return;
            }
            writeEvent("stage", Map.of(
                    "title", title,
                    "status", status,
                    "detail", detail
            ));
        }

        @Override
        public synchronized void onChunk(String chunk) {
            if (closed) {
                return;
            }
            writeEvent("chunk", Map.of("content", chunk));
        }

        @Override
        public synchronized void onFailed() {
            if (closed) {
                return;
            }
            writeEvent("failed", Map.of("content", AgentAnalysisResult.SERVICE_ANALYZE_FAILED));
            close();
        }

        @Override
        public synchronized void onCompleted() {
            if (closed) {
                return;
            }
            writeEvent("done", Map.of("done", true));
            close();
        }

        private void writeEvent(String event, Object payload) {
            if (closed) {
                return;
            }
            try {
                String body = "event: " + event + "\n"
                        + "data: " + JsonUtil.toJson(payload) + "\n\n";
                exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (IOException e) {
                close();
            }
        }

        private void close() {
            if (closed) {
                return;
            }
            closed = true;
            exchange.close();
        }
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
            String sourceFilePath,
            Integer sourceLine,
            String logId,
            String message,
            String throwable
    ) {
    }

    private record WebSocketNodeResponse(String endpoint) {
    }
}
