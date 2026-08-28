package com.GinElmaC.NettyServer.Agent;

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

/**
 * OpenAI-compatible LLM 调用客户端。
 * 同时支持普通响应和 SSE 流式响应，模型选择与指标统计由上层 Agent 路由处理。
 */
public class LlmAnalyzeClient {
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
        if (!model.enabled()) {
            return null;
        }
        try {
            // 单次分析限制 20 秒，超时由上层记录为模型失败。
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl(model)))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + model.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(model, userPrompt, false)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("llm request failed,status:" + response.statusCode());
            }
            return parseContent(response.body());
        } catch (Exception e) {
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
        if (!model.enabled()) {
            return;
        }
        try {
            // 流式分析允许更长时间，逐行读取服务端发送的 SSE data 数据。
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl(model)))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + model.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(model, userPrompt, true)))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("llm stream request failed,status:" + response.statusCode());
            }
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> handleStreamLine(line, chunkConsumer));
            }
        } catch (Exception e) {
            throw new IllegalStateException("llm stream analyze failed", e);
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
        if (!model.enabled()) {
            throw new IllegalStateException("agent model is unavailable");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(chatCompletionsUrl(model)))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + model.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(buildToolRequestBody(model, messages, tools)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("llm tool request failed,status:" + response.statusCode());
            }
            return parseToolResponse(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("llm tool chat failed", e);
        }
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
            List<AgentToolDefinition> tools
    ) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model.name());
        body.addProperty("temperature", 0.2);

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
            // 日志分析第一轮必须先查询真实日志，禁止模型跳过工具直接编造结论。
            body.addProperty("tool_choice", "required");
        }
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
}
