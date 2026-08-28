package com.GinElmaC.NettyServer.Agent;

/**
 * 模型并发租约。
 * requestId 对应 Redis 中的租约成员；startedAtMillis 用于统计总耗时和首 Token 延迟。
 */
public record AgentModelLease(String requestId, AgentModel model, long startedAtMillis) {
}
