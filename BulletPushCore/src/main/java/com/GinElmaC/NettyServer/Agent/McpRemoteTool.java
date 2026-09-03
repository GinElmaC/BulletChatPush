package com.GinElmaC.NettyServer.Agent;

/**
 * 从远端 MCP tools/list 发现到的工具快照。
 */
public record McpRemoteTool(
        McpServerConfig serverConfig,
        String remoteName,
        String exposedName,
        String description,
        String parametersJson
) {
}
