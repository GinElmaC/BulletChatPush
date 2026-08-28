package com.GinElmaC.NettyServer.Agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

/**
 * Agent 工具注册表。
 * 工具名称白名单固定，模型不能调用未注册工具，也不能通过工具传入 SQL 或表名。
 */
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools = Map.of(
            QueryPushLogsByLogIdTool.NAME, new QueryPushLogsByLogIdTool()
    );

    public List<AgentToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public String execute(AgentToolCall toolCall, AgentToolRequest request) {
        AgentTool tool = tools.get(toolCall.name());
        if (tool == null) {
            throw new IllegalArgumentException("agent tool is unavailable:" + toolCall.name());
        }
        JsonObject arguments = JsonParser.parseString(toolCall.argumentsJson()).getAsJsonObject();
        return tool.execute(arguments, request);
    }
}
