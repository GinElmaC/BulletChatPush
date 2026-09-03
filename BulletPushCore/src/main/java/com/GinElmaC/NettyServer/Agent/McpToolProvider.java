package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从配置的 MCP Server 动态发现工具，并转换为 Agent 本地可执行工具。
 */
public class McpToolProvider {
    private static final Log log = LogFactory.getLog(McpToolProvider.class);
    private final McpJsonRpcClient client = new McpJsonRpcClient();

    public Map<String, AgentTool> loadTools() {
        Map<String, AgentTool> tools = new LinkedHashMap<>();
        for (McpServerConfig server : AgentMcpConfig.servers()) {
            try {
                List<McpRemoteTool> remoteTools = client.listTools(server);
                for (McpRemoteTool remoteTool : remoteTools) {
                    if (tools.containsKey(remoteTool.exposedName())) {
                        log.Warn(systemContext()
                                        .put("serverName", server.name())
                                        .put("toolName", remoteTool.exposedName()),
                                "AGENT_MCP_TOOL_DUPLICATE_SKIPPED");
                        continue;
                    }
                    tools.put(remoteTool.exposedName(), new McpAgentTool(remoteTool, client));
                }
                log.Info(systemContext()
                                .put("serverName", server.name())
                                .put("toolCount", remoteTools.size()),
                        "AGENT_MCP_TOOLS_LOADED");
            } catch (Exception e) {
                log.Warn(systemContext()
                                .put("serverName", server.name())
                                .put("error", e.getClass().getSimpleName()),
                        "AGENT_MCP_TOOLS_LOAD_FAILED");
            }
        }
        return tools;
    }

    private LogContext systemContext() {
        return LogContext.create();
    }
}
