package com.GinElmaC.NettyServer.Agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 工具注册表。
 * 工具名称白名单固定，模型不能调用未注册工具，也不能通过工具传入 SQL 或表名。
 */
public class AgentToolRegistry {
    private final Map<String, AgentTool> tools;
    private final AgentToolGateway toolGateway = new AgentToolGateway();
    private final boolean includeMcpTools;
    private volatile boolean mcpLoaded;

    public AgentToolRegistry() {
        this(true);
    }

    public AgentToolRegistry(boolean includeMcpTools) {
        this.includeMcpTools = includeMcpTools;
        Map<String, AgentTool> configuredTools = new LinkedHashMap<>();
        // 本地日志工具永远优先注册，确保 LogID 分析至少能查询 push_log。
        configuredTools.put(QueryPushLogsByLogIdTool.NAME, new QueryPushLogsByLogIdTool());
        // 代码查看工具只读访问项目源码目录，供日志排障时按文件/行号回看实现。
        configuredTools.put(QueryProjectCodeTool.NAME, new QueryProjectCodeTool());
        this.tools = configuredTools;
    }

    public List<AgentToolDefinition> definitions() {
        loadMcpToolsIfNecessary();
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public List<AgentToolDefinition> definitions(String... toolNames) {
        loadMcpToolsIfNecessary();
        if (toolNames == null || toolNames.length == 0) {
            return definitions();
        }
        List<AgentToolDefinition> definitions = new ArrayList<>();
        for (String toolName : toolNames) {
            AgentTool tool = tools.get(toolName);
            if (tool == null) {
                throw new IllegalArgumentException("agent tool is unavailable:" + toolName);
            }
            definitions.add(tool.definition());
        }
        return definitions;
    }

    public String execute(AgentToolCall toolCall, AgentToolRequest request) {
        loadMcpToolsIfNecessary();
        AgentTool tool = tools.get(toolCall.name());
        if (tool == null) {
            throw new IllegalArgumentException("agent tool is unavailable:" + toolCall.name());
        }
        return toolGateway.invoke(tool, toolCall, request);
    }

    private synchronized void loadMcpToolsIfNecessary() {
        if (!includeMcpTools || mcpLoaded) {
            return;
        }
        // MCP 工具由本地配置显式开启后加载；加载失败不会影响本地工具。
        tools.putAll(new McpToolProvider().loadTools());
        mcpLoaded = true;
    }
}
