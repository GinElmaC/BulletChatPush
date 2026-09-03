package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 最小 MCP JSON-RPC 客户端。
 * 该实现只负责调用远端 tools/list 和 tools/call，不缓存连接、不执行本机进程。
 */
public class McpJsonRpcClient {
    private static final Log log = LogFactory.getLog(McpJsonRpcClient.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public List<McpRemoteTool> listTools(McpServerConfig config) {
        ensureHttpJsonRpc(config);
        JsonObject response = post(config, "tools/list", new JsonObject());
        JsonObject result = result(response);
        JsonArray tools = result.getAsJsonArray("tools");
        if (tools == null || tools.size() == 0) {
            return List.of();
        }
        List<McpRemoteTool> remoteTools = new ArrayList<>();
        for (JsonElement element : tools) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject tool = element.getAsJsonObject();
            String remoteName = readText(tool, "name");
            if (!hasText(remoteName)) {
                continue;
            }
            String exposedName = "mcp_" + normalizeName(config.name()) + "_" + normalizeName(remoteName);
            String description = readText(tool, "description");
            JsonObject inputSchema = inputSchema(tool);
            remoteTools.add(new McpRemoteTool(
                    config,
                    remoteName,
                    exposedName,
                    hasText(description) ? description : "MCP remote tool " + remoteName,
                    inputSchema.toString()
            ));
        }
        return remoteTools;
    }

    public String callTool(McpRemoteTool tool, JsonObject arguments) {
        return callTool(tool, arguments, null);
    }

    /**
     * 远端工具调用必须沿用发起 Agent 请求的 Trace，便于关联网关、网络调用和远端工具执行日志。
     */
    public String callTool(
            McpRemoteTool tool,
            JsonObject arguments,
            AgentTraceContext traceContext
    ) {
        ensureHttpJsonRpc(tool.serverConfig());
        JsonObject params = new JsonObject();
        params.addProperty("name", tool.remoteName());
        params.add("arguments", arguments == null ? new JsonObject() : arguments);
        JsonObject response = post(tool.serverConfig(), "tools/call", params, traceContext);
        JsonObject result = result(response);
        if (result.has("isError") && result.get("isError").getAsBoolean()) {
            throw new IllegalStateException("mcp tool returned error");
        }
        return truncate(renderResult(result), AgentMcpConfig.RESULT_MAX_LENGTH);
    }

    private JsonObject post(McpServerConfig config, String method, JsonObject params) {
        return post(config, method, params, null);
    }

    /**
     * MCP 请求和响应单独记录，避免仅看到工具网关成功但无法判断远端 HTTP 是否成功。
     * 请求体仅包含 JSON-RPC 参数，不包含任何 Authorization 等敏感请求头。
     */
    private JsonObject post(
            McpServerConfig config,
            String method,
            JsonObject params,
            AgentTraceContext traceContext
    ) {
        long startedAtMillis = System.currentTimeMillis();
        try {
            JsonObject body = new JsonObject();
            body.addProperty("jsonrpc", "2.0");
            body.addProperty("id", UUID.randomUUID().toString());
            body.addProperty("method", method);
            body.add("params", params == null ? new JsonObject() : params);
            logMcpRequest(traceContext, config, method, body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(config.endpoint()))
                    .timeout(Duration.ofMillis(Math.max(1_000, config.timeoutMs())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
            if (traceContext != null) {
                // 下游 MCP Server 可使用该 Header 让自身的工具内部日志归属同一 Agent Trace。
                requestBuilder.header("X-Agent-Trace-Id", traceContext.traceId());
            }
            HttpRequest request = requestBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logMcpResponse(traceContext, config, method, response.statusCode(), response.body(), startedAtMillis);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("mcp http status:" + response.statusCode());
            }
            JsonObject responseBody = JsonParser.parseString(response.body()).getAsJsonObject();
            return responseBody;
        } catch (Exception e) {
            logMcpFailed(traceContext, config, method, startedAtMillis, e);
            throw new IllegalStateException("mcp json-rpc request failed:" + method, e);
        }
    }

    private void logMcpRequest(
            AgentTraceContext traceContext,
            McpServerConfig config,
            String method,
            JsonObject requestBody
    ) {
        if (traceContext == null) {
            return;
        }
        LogContext context = traceContext.logContext()
                .put("mcpServerName", config.name())
                .put("mcpEndpoint", config.endpoint())
                .put("mcpMethod", method);
        traceContext.putPayload(context, "mcpRequest", requestBody.toString());
        log.Info(context, "AGENT_MCP_REQUEST");
    }

    private void logMcpResponse(
            AgentTraceContext traceContext,
            McpServerConfig config,
            String method,
            int statusCode,
            String responseBody,
            long startedAtMillis
    ) {
        if (traceContext == null) {
            return;
        }
        LogContext context = traceContext.logContext()
                .put("mcpServerName", config.name())
                .put("mcpEndpoint", config.endpoint())
                .put("mcpMethod", method)
                .put("mcpStatusCode", statusCode)
                .put("mcpDurationMs", System.currentTimeMillis() - startedAtMillis);
        traceContext.putPayload(context, "mcpResponse", responseBody);
        log.Info(context, "AGENT_MCP_RESPONSE");
    }

    private void logMcpFailed(
            AgentTraceContext traceContext,
            McpServerConfig config,
            String method,
            long startedAtMillis,
            Exception error
    ) {
        if (traceContext == null) {
            return;
        }
        log.Error(traceContext.logContext()
                        .put("mcpServerName", config.name())
                        .put("mcpEndpoint", config.endpoint())
                        .put("mcpMethod", method)
                        .put("mcpDurationMs", System.currentTimeMillis() - startedAtMillis)
                        .put("errorType", error.getClass().getSimpleName()),
                "AGENT_MCP_FAILED",
                error);
    }

    private JsonObject result(JsonObject response) {
        if (response == null) {
            throw new IllegalStateException("mcp response is empty");
        }
        if (response.has("error") && !response.get("error").isJsonNull()) {
            throw new IllegalStateException("mcp response error");
        }
        JsonObject result = response.getAsJsonObject("result");
        if (result == null) {
            throw new IllegalStateException("mcp response result is empty");
        }
        return result;
    }

    private String renderResult(JsonObject result) {
        JsonArray content = result.getAsJsonArray("content");
        if (content != null && content.size() > 0) {
            List<String> parts = new ArrayList<>();
            for (JsonElement element : content) {
                if (element == null || !element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                if ("text".equals(readText(item, "type")) && item.has("text")) {
                    parts.add(item.get("text").getAsString());
                } else {
                    parts.add(item.toString());
                }
            }
            if (!parts.isEmpty()) {
                return String.join("\n", parts);
            }
        }
        if (result.has("structuredContent")) {
            return result.get("structuredContent").toString();
        }
        return result.toString();
    }

    private void ensureHttpJsonRpc(McpServerConfig config) {
        String transport = config.transport() == null ? "" : config.transport().trim();
        if (!transport.equalsIgnoreCase("http-jsonrpc") && !transport.equalsIgnoreCase("http")) {
            throw new IllegalStateException("unsupported mcp transport:" + config.transport());
        }
    }

    private JsonObject emptySchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    private JsonObject inputSchema(JsonObject tool) {
        if (tool.has("inputSchema") && tool.get("inputSchema").isJsonObject()) {
            return tool.getAsJsonObject("inputSchema");
        }
        if (tool.has("input_schema") && tool.get("input_schema").isJsonObject()) {
            return tool.getAsJsonObject("input_schema");
        }
        return emptySchema();
    }

    private String readText(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        String value = jsonObject.get(key).getAsString();
        return hasText(value) ? value.trim() : null;
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^a-zA-Z0-9_]", "_");
        normalized = normalized.replaceAll("_+", "_");
        if (normalized.isBlank()) {
            normalized = "tool";
        }
        if (!Character.isLetter(normalized.charAt(0)) && normalized.charAt(0) != '_') {
            normalized = "tool_" + normalized;
        }
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...[truncated]";
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
