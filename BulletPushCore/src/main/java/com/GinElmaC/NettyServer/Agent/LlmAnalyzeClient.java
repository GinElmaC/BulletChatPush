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
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * OpenAI-compatible LLM 调用客户端。
 * 同时支持普通响应和 SSE 流式响应，模型选择与指标统计由上层 Agent 路由处理。
 */
public class LlmAnalyzeClient {
    private static final Log log = LogFactory.getLog(LlmAnalyzeClient.class);
    // 所有模型共用的运维分析系统提示词。
    private static final String SYSTEM_PROMPT = """
            你是推送中台的智能运维分析助手。
            你的职责是根据节点运行指标和最近日志，判断节点是否健康，并给出可执行的排查建议。
            要求：
            1. 只基于输入数据分析，不要编造不存在的指标。
            2. 输出健康等级：HEALTHY、WARNING、CRITICAL。
            3. 指出主要风险点、证据、可能原因、建议动作。
            4. 不允许直接建议无条件重启，只有在CPU、内存、GC、心跳、错误日志等证据同时指向异常时，才建议摘流后重启。
            5. 如果数据不足，明确说明缺少哪些数据。
            6. 输出中文，面向后端研发和运维人员。
            """;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 兼容旧调用方式，使用当前默认模型发起非流式请求。
     */
    public String analyze(String userPrompt) {
        return analyze(new AgentModel(
                AgentModelConfig.MODEL_NAME,
                AgentModelConfig.BASE_URL,
                AgentModelConfig.API_KEY,
                AgentModelConfig.DEEPSEEK_FLASH_MAX_CONCURRENCY
        ), userPrompt);
    }

    public String analyze(AgentModel model, String userPrompt) {
        return analyze(model, userPrompt, null);
    }

    public String analyze(AgentModel model, String userPrompt, AgentTraceContext traceContext) {
        if (!model.enabled()) {
            return null;
        }
        long startedAtMillis = System.currentTimeMillis();
        try {
            // 单次分析限制 20 秒，超时由上层记录为模型失败。
            String requestBody = buildRequestBody(model, userPrompt, false);
            logLlmRequest(traceContext, "analyze", model, requestBody, false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl(model)))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + model.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logLlmResponse(traceContext, "analyze", model, response.statusCode(), response.body(), startedAtMillis);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("llm request failed,status:" + response.statusCode());
            }
            String content = parseContent(response.body());
            return content;
        } catch (Exception e) {
            logLlmFailed(traceContext, "analyze", model, startedAtMillis, e);
            throw new IllegalStateException("llm analyze failed", e);
        }
    }

    public void analyzeStream(String userPrompt, Consumer<String> chunkConsumer) {
        analyzeStream(new AgentModel(
                AgentModelConfig.MODEL_NAME,
                AgentModelConfig.BASE_URL,
                AgentModelConfig.API_KEY,
                AgentModelConfig.DEEPSEEK_FLASH_MAX_CONCURRENCY
        ), userPrompt, chunkConsumer);
    }

    public void analyzeStream(AgentModel model, String userPrompt, Consumer<String> chunkConsumer) {
        analyzeStream(model, userPrompt, null, chunkConsumer);
    }

    public void analyzeStream(
            AgentModel model,
            String userPrompt,
            AgentTraceContext traceContext,
            Consumer<String> chunkConsumer
    ) {
        if (!model.enabled()) {
            return;
        }
        long startedAtMillis = System.currentTimeMillis();
        try {
            // 流式分析允许更长时间，逐行读取服务端发送的 SSE data 数据。
            String requestBody = buildRequestBody(model, userPrompt, true);
            logLlmRequest(traceContext, "analyze_stream", model, requestBody, true);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl(model)))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + model.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logLlmResponse(traceContext, "analyze_stream", model, response.statusCode(),
                        readStreamBody(response.body()), startedAtMillis);
                throw new IllegalStateException("llm stream request failed,status:" + response.statusCode());
            }
            StringBuilder output = new StringBuilder();
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> handleStreamLine(line, chunk -> {
                    output.append(chunk);
                    chunkConsumer.accept(chunk);
                }));
            }
            logLlmResponse(traceContext, "analyze_stream", model, response.statusCode(), output.toString(), startedAtMillis);
        } catch (Exception e) {
            logLlmFailed(traceContext, "analyze_stream", model, startedAtMillis, e);
            throw new IllegalStateException("llm stream analyze failed", e);
        }
    }

    /**
     * 基于完整历史消息发起流式对话。
     * 会话型排障助手通过该方法复用已有聊天上下文，而不是每轮只传一段新的用户 prompt。
     */
    public void chatStream(AgentModel model, List<AgentChatMessage> messages, Consumer<String> chunkConsumer) {
        chatStream(model, messages, null, chunkConsumer);
    }

    public void chatStream(
            AgentModel model,
            List<AgentChatMessage> messages,
            AgentTraceContext traceContext,
            Consumer<String> chunkConsumer
    ) {
        if (!model.enabled()) {
            return;
        }
        long startedAtMillis = System.currentTimeMillis();
        try {
            String requestBody = buildChatRequestBody(model, messages, true);
            logLlmRequest(traceContext, "chat_stream", model, requestBody, true);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl(model)))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + model.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logLlmResponse(traceContext, "chat_stream", model, response.statusCode(),
                        readStreamBody(response.body()), startedAtMillis);
                throw new IllegalStateException("llm chat stream request failed,status:" + response.statusCode());
            }
            StringBuilder output = new StringBuilder();
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> handleStreamLine(line, chunk -> {
                    output.append(chunk);
                    chunkConsumer.accept(chunk);
                }));
            }
            logLlmResponse(traceContext, "chat_stream", model, response.statusCode(), output.toString(), startedAtMillis);
        } catch (Exception e) {
            logLlmFailed(traceContext, "chat_stream", model, startedAtMillis, e);
            throw new IllegalStateException("llm chat stream failed", e);
        }
    }

    /**
     * 发起带工具定义的非流式对话。
     * 第一轮通常返回 tool_calls，工具结果回填后第二轮返回最终日志分析文本。
     */
    public AgentLlmResponse chatWithTools(
            AgentModel model,
            List<AgentChatMessage> messages,
            List<AgentToolDefinition> tools
    ) {
        return chatWithTools(model, messages, tools, null);
    }

    public AgentLlmResponse chatWithTools(
            AgentModel model,
            List<AgentChatMessage> messages,
            List<AgentToolDefinition> tools,
            AgentTraceContext traceContext
    ) {
        if (!model.enabled()) {
            throw new IllegalStateException("agent model is unavailable");
        }
        long startedAtMillis = System.currentTimeMillis();
        try {
            String requestBody = buildToolRequestBody(model, messages, tools, false, true);
            logLlmRequest(traceContext, "chat_with_tools", model, requestBody, false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl(model)))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + model.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logLlmResponse(traceContext, "chat_with_tools", model, response.statusCode(), response.body(), startedAtMillis);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("llm tool request failed,status:" + response.statusCode());
            }
            AgentLlmResponse result = parseToolResponse(response.body());
            return result;
        } catch (Exception e) {
            logLlmFailed(traceContext, "chat_with_tools", model, startedAtMillis, e);
            throw new IllegalStateException("llm tool chat failed", e);
        }
    }

    /**
     * 发起带工具定义的流式对话，并在一轮 SSE 结束后返回本轮完整响应。
     * content 增量会同步传给 chunkConsumer；tool_calls 会在流结束后聚合为完整函数调用。
     */
    public AgentLlmResponse chatStreamWithTools(
            AgentModel model,
            List<AgentChatMessage> messages,
            List<AgentToolDefinition> tools,
            AgentTraceContext traceContext,
            Consumer<String> chunkConsumer
    ) {
        if (!model.enabled()) {
            throw new IllegalStateException("agent model is unavailable");
        }
        long startedAtMillis = System.currentTimeMillis();
        try {
            String requestBody = tools == null || tools.isEmpty()
                    ? buildChatRequestBody(model, messages, true)
                    : buildToolRequestBody(model, messages, tools, true, false);
            logLlmRequest(traceContext, "chat_stream_with_tools", model, requestBody, true);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl(model)))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + model.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                logLlmResponse(traceContext, "chat_stream_with_tools", model, response.statusCode(),
                        readStreamBody(response.body()), startedAtMillis);
                throw new IllegalStateException("llm tool stream request failed,status:" + response.statusCode());
            }
            AgentStreamResponseAccumulator accumulator = new AgentStreamResponseAccumulator();
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> handleToolStreamLine(line, accumulator, chunkConsumer));
            }
            AgentLlmResponse result = accumulator.response();
            logLlmResponse(traceContext, "chat_stream_with_tools", model, response.statusCode(),
                    toolResponsePayload(result), startedAtMillis);
            return result;
        } catch (Exception e) {
            logLlmFailed(traceContext, "chat_stream_with_tools", model, startedAtMillis, e);
            throw new IllegalStateException("llm tool stream chat failed", e);
        }
    }

    /**
     * 记录发送给模型的请求体，不记录 Authorization 请求头或 API Key。
     */
    private void logLlmRequest(
            AgentTraceContext traceContext,
            String operation,
            AgentModel model,
            String requestBody,
            boolean stream
    ) {
        if (traceContext == null) {
            return;
        }
        LogContext context = traceContext.logContext()
                .put("llmOperation", operation)
                .put("llmModel", model.name())
                .put("llmStream", stream)
                .put("llmEndpoint", chatCompletionsUrl(model));
        traceContext.putPayload(context, "llmInput", requestBody);
        log.Info(context, "AGENT_LLM_REQUEST");
    }

    private void logLlmResponse(
            AgentTraceContext traceContext,
            String operation,
            AgentModel model,
            int statusCode,
            String output,
            long startedAtMillis
    ) {
        if (traceContext == null) {
            return;
        }
        LogContext context = traceContext.logContext()
                .put("llmOperation", operation)
                .put("llmModel", model.name())
                .put("llmStatusCode", statusCode)
                .put("llmDurationMs", System.currentTimeMillis() - startedAtMillis);
        traceContext.putPayload(context, "llmOutput", output);
        log.Info(context, "AGENT_LLM_RESPONSE");
    }

    private void logLlmFailed(
            AgentTraceContext traceContext,
            String operation,
            AgentModel model,
            long startedAtMillis,
            Exception error
    ) {
        if (traceContext == null) {
            return;
        }
        log.Error(traceContext.logContext()
                        .put("llmOperation", operation)
                        .put("llmModel", model.name())
                        .put("llmDurationMs", System.currentTimeMillis() - startedAtMillis)
                        .put("errorType", error.getClass().getSimpleName()),
                "AGENT_LLM_FAILED",
                error);
    }

    private String chatCompletionsUrl(AgentModel model) {
        // 允许配置网关根地址或完整的 chat/completions 地址。
        String baseUrl = model.baseUrl().endsWith("/")
                ? model.baseUrl().substring(0, model.baseUrl().length() - 1)
                : model.baseUrl();
        if (baseUrl.endsWith("/v1/chat/completions")) {
            return baseUrl;
        }
        return baseUrl + "/v1/chat/completions";
    }

    private String buildRequestBody(AgentModel model, String userPrompt, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.name());
        body.addProperty("temperature", 0.2);
        body.addProperty("stream", stream);

        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", userPrompt));
        body.add("messages", messages);
        return body.toString();
    }

    private String buildToolRequestBody(
            AgentModel model,
            List<AgentChatMessage> messages,
            List<AgentToolDefinition> tools,
            boolean stream,
            boolean requiredToolChoice
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.name());
        body.addProperty("temperature", 0.2);
        body.addProperty("stream", stream);

        JsonArray requestMessages = new JsonArray();
        for (AgentChatMessage message : messages) {
            requestMessages.add(toJsonMessage(message));
        }
        body.add("messages", requestMessages);

        if (tools != null && !tools.isEmpty()) {
            JsonArray requestTools = new JsonArray();
            for (AgentToolDefinition tool : tools) {
                JsonObject function = new JsonObject();
                function.addProperty("name", tool.name());
                function.addProperty("description", tool.description());
                function.add("parameters", JsonParser.parseString(tool.parametersJson()).getAsJsonObject());

                JsonObject toolObject = new JsonObject();
                toolObject.addProperty("type", "function");
                toolObject.add("function", function);
                requestTools.add(toolObject);
            }
            body.add("tools", requestTools);
            body.addProperty("tool_choice", requiredToolChoice ? "required" : "auto");
        }
        return body.toString();
    }

    private String buildChatRequestBody(AgentModel model, List<AgentChatMessage> messages, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.name());
        body.addProperty("temperature", 0.2);
        body.addProperty("stream", stream);

        JsonArray requestMessages = new JsonArray();
        for (AgentChatMessage message : messages) {
            requestMessages.add(toJsonMessage(message));
        }
        body.add("messages", requestMessages);
        return body.toString();
    }

    private JsonObject toJsonMessage(AgentChatMessage message) {
        JsonObject jsonMessage = new JsonObject();
        jsonMessage.addProperty("role", message.role());
        if (message.content() != null) {
            jsonMessage.addProperty("content", message.content());
        }
        if (message.toolCallId() != null) {
            jsonMessage.addProperty("tool_call_id", message.toolCallId());
        }
        if (message.assistantToolCalls() != null && !message.assistantToolCalls().isEmpty()) {
            JsonArray toolCalls = new JsonArray();
            for (AgentToolCall toolCall : message.assistantToolCalls()) {
                JsonObject function = new JsonObject();
                function.addProperty("name", toolCall.name());
                function.addProperty("arguments", toolCall.argumentsJson());

                JsonObject toolCallObject = new JsonObject();
                toolCallObject.addProperty("id", toolCall.id());
                toolCallObject.addProperty("type", "function");
                toolCallObject.add("function", function);
                toolCalls.add(toolCallObject);
            }
            jsonMessage.add("tool_calls", toolCalls);
        }
        return jsonMessage;
    }

    private AgentLlmResponse parseToolResponse(String body) {
        JsonObject jsonObject = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = jsonObject.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new IllegalStateException("llm response has no choices");
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        if (message == null) {
            throw new IllegalStateException("llm response has no message");
        }
        String content = message.has("content") && !message.get("content").isJsonNull()
                ? message.get("content").getAsString()
                : "";
        List<AgentToolCall> toolCalls = new ArrayList<>();
        JsonArray responseToolCalls = message.getAsJsonArray("tool_calls");
        if (responseToolCalls != null) {
            for (JsonElement element : responseToolCalls) {
                JsonObject toolCall = element.getAsJsonObject();
                JsonObject function = toolCall.getAsJsonObject("function");
                if (function == null) {
                    continue;
                }
                toolCalls.add(new AgentToolCall(
                        toolCall.get("id").getAsString(),
                        function.get("name").getAsString(),
                        function.get("arguments").getAsString()
                ));
            }
        }
        return new AgentLlmResponse(content, toolCalls);
    }

    /**
     * 流式工具调用的输出除文本外还包含模型决定的工具名和完整参数，必须一并保留用于审计。
     */
    private String toolResponsePayload(AgentLlmResponse response) {
        JsonObject payload = new JsonObject();
        payload.addProperty("content", response.content());
        JsonArray toolCalls = new JsonArray();
        for (AgentToolCall toolCall : response.toolCalls()) {
            JsonObject toolCallJson = new JsonObject();
            toolCallJson.addProperty("id", toolCall.id());
            toolCallJson.addProperty("name", toolCall.name());
            toolCallJson.addProperty("arguments", toolCall.argumentsJson());
            toolCalls.add(toolCallJson);
        }
        payload.add("toolCalls", toolCalls);
        return payload.toString();
    }

    /**
     * 解析 OpenAI-compatible SSE 行，只将增量 content 片段交给上层。
     */
    private void handleStreamLine(String line, Consumer<String> chunkConsumer) {
        if (line == null || line.isBlank() || !line.startsWith("data:")) {
            return;
        }
        String data = line.substring("data:".length()).trim();
        if ("[DONE]".equals(data)) {
            return;
        }
        JsonObject jsonObject = JsonParser.parseString(data).getAsJsonObject();
        JsonArray choices = jsonObject.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            return;
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject delta = choice.getAsJsonObject("delta");
        if (delta == null) {
            return;
        }
        JsonElement content = delta.get("content");
        if (content != null && !content.isJsonNull()) {
            chunkConsumer.accept(content.getAsString());
        }
    }

    /**
     * 解析带工具调用的 OpenAI-compatible SSE 行。
     * 工具调用参数经常被拆成多个 delta，需要按 index 聚合到完整 JSON 后再执行。
     */
    private void handleToolStreamLine(
            String line,
            AgentStreamResponseAccumulator accumulator,
            Consumer<String> chunkConsumer
    ) {
        if (line == null || line.isBlank() || !line.startsWith("data:")) {
            return;
        }
        String data = line.substring("data:".length()).trim();
        if ("[DONE]".equals(data)) {
            return;
        }
        JsonObject jsonObject = JsonParser.parseString(data).getAsJsonObject();
        JsonArray choices = jsonObject.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            return;
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        JsonObject delta = choice.getAsJsonObject("delta");
        if (delta == null) {
            return;
        }
        JsonElement content = delta.get("content");
        if (content != null && !content.isJsonNull()) {
            String chunk = content.getAsString();
            accumulator.appendContent(chunk);
            chunkConsumer.accept(chunk);
        }
        JsonArray toolCalls = delta.getAsJsonArray("tool_calls");
        if (toolCalls != null) {
            for (JsonElement element : toolCalls) {
                accumulator.appendToolCall(element.getAsJsonObject());
            }
        }
    }

    private JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    private String parseContent(String body) {
        JsonObject jsonObject = JsonParser.parseString(body).getAsJsonObject();
        JsonArray choices = jsonObject.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            return null;
        }
        JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
        return message == null ? null : message.get("content").getAsString();
    }

    private String readStreamBody(Stream<String> body) {
        if (body == null) {
            return "";
        }
        try (Stream<String> lines = body) {
            return lines.collect(Collectors.joining("\n"));
        }
    }

    private static final class AgentStreamResponseAccumulator {
        private final StringBuilder content = new StringBuilder();
        private final List<ToolCallDraft> toolCallDrafts = new ArrayList<>();

        private void appendContent(String chunk) {
            if (chunk != null) {
                content.append(chunk);
            }
        }

        private void appendToolCall(JsonObject toolCall) {
            if (toolCall == null) {
                return;
            }
            int index = toolCall.has("index") && !toolCall.get("index").isJsonNull()
                    ? toolCall.get("index").getAsInt()
                    : toolCallDrafts.size();
            while (toolCallDrafts.size() <= index) {
                toolCallDrafts.add(new ToolCallDraft());
            }
            ToolCallDraft draft = toolCallDrafts.get(index);
            if (toolCall.has("id") && !toolCall.get("id").isJsonNull()) {
                draft.id.append(toolCall.get("id").getAsString());
            }
            JsonObject function = toolCall.getAsJsonObject("function");
            if (function == null) {
                return;
            }
            if (function.has("name") && !function.get("name").isJsonNull()) {
                draft.name.append(function.get("name").getAsString());
            }
            if (function.has("arguments") && !function.get("arguments").isJsonNull()) {
                draft.arguments.append(function.get("arguments").getAsString());
            }
        }

        private AgentLlmResponse response() {
            List<AgentToolCall> toolCalls = new ArrayList<>();
            for (int index = 0; index < toolCallDrafts.size(); index++) {
                ToolCallDraft draft = toolCallDrafts.get(index);
                if (draft.name.isEmpty()) {
                    continue;
                }
                String id = draft.id.isEmpty()
                        ? "stream-tool-call-" + index + "-" + System.currentTimeMillis()
                        : draft.id.toString();
                String arguments = draft.arguments.isEmpty() ? "{}" : draft.arguments.toString();
                toolCalls.add(new AgentToolCall(id, draft.name.toString(), arguments));
            }
            return new AgentLlmResponse(content.toString(), toolCalls);
        }
    }

    private static final class ToolCallDraft {
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
