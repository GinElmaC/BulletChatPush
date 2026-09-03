package com.GinElmaC.NettyServer.Agent;

/**
 * 单个 MCP Server 的连接配置。
 * 第一版只接入 HTTP JSON-RPC 形态，后续可在保持字段不变的前提下补充 SSE/stdio 传输。
 */
public record McpServerConfig(
        String name,
        String transport,
        String endpoint,
        int timeoutMs,
        boolean enabled
) {
}
