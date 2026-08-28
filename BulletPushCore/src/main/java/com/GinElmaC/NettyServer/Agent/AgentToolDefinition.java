package com.GinElmaC.NettyServer.Agent;

/**
 * 提供给支持 Function Calling 的模型的工具定义。
 * parametersJson 必须是 JSON Schema，对应后续 MCP tools/list 中的 inputSchema。
 */
public record AgentToolDefinition(String name, String description, String parametersJson) {
}
