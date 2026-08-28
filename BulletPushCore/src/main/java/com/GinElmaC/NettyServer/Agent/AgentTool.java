package com.GinElmaC.NettyServer.Agent;

import com.google.gson.JsonObject;

/**
 * Agent 内部工具协议。
 * 后续 MCP Server 只需将 definition 映射为 tools/list，并将 execute 映射为 tools/call。
 */
public interface AgentTool {
    AgentToolDefinition definition();

    String execute(JsonObject arguments, AgentToolRequest request);
}
