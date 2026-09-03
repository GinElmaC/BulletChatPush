package com.GinElmaC.NettyServer.Agent;

import com.google.gson.JsonObject;

/**
 * 将远端 MCP 工具包装成本地 AgentTool。
 * 模型看到的是带 mcp_{server}_ 前缀的安全函数名，真正调用时再映射回远端原始工具名。
 */
public class McpAgentTool implements AgentTool {
    private final McpRemoteTool remoteTool;
    private final McpJsonRpcClient client;

    public McpAgentTool(McpRemoteTool remoteTool, McpJsonRpcClient client) {
        this.remoteTool = remoteTool;
        this.client = client;
    }

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                remoteTool.exposedName(),
                "[MCP:" + remoteTool.serverConfig().name() + "] " + remoteTool.description(),
                remoteTool.parametersJson()
        );
    }

    @Override
    public String execute(JsonObject arguments, AgentToolRequest request) {
        return client.callTool(
                remoteTool,
                arguments,
                request == null ? null : request.traceContext()
        );
    }
}
