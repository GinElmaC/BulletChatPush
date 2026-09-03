# Push Agent

## 第一版能力

当前 Agent 采用“规则预分析 + LLM 总结”的方式：

1. `NodeMetrics.snapshot()` 采集当前节点详情。
2. `PushLogRepository.queryByMachineId(...)` 查询最近 ERROR 日志。
3. `RuleAnalyzer` 根据阈值产出稳定判断。
4. `LlmAnalyzeClient` 在模型配置齐全时调用 OpenAI-compatible Chat Completions 接口生成中文建议。

LLM 未配置、Redis 不可用、模型路由失败或模型调用失败时，Agent 只返回 `服务分析失败`，不会返回规则结论、节点指标、日志、模型信息或底层异常。

## 入口

```java
PushAgentAnalyzer analyzer = new PushAgentAnalyzer();
AgentAnalysisResult result = analyzer.analyzeCurrentNode();
```

也可以传入指定节点快照和日志：

```java
AgentAnalysisResult result = analyzer.analyze(nodeDetail, recentLogs);
```

流式输出：

```java
analyzer.analyzeCurrentNodeStream(chunk -> {
    // 这里后续可以写入 HTTP SSE / WebSocket
    System.out.print(chunk);
});
```

配置了 LLM 时，`analyzeCurrentNodeStream` 会走模型 `stream=true`；失败时应使用 `AgentStreamObserver#onFailed()` 清空已渲染草稿并展示 `服务分析失败`。

## LLM 配置

支持 JVM 参数：

```bash
-Dpush.agent.model.baseUrl=https://example.com
-Dpush.agent.model.apiKey=your_api_key
-Dpush.agent.model.name=your_model_name
```

也支持环境变量：

```bash
PUSH_AGENT_MODEL_BASE_URL=https://example.com
PUSH_AGENT_MODEL_API_KEY=your_api_key
PUSH_AGENT_MODEL_NAME=deepseek-flash
```

`baseUrl` 可以填网关根地址，也可以直接填 `/v1/chat/completions` 地址。

## Auto 模型路由

当前注册模型为 `deepseek-flash`。前端可选择 `Auto` 或显式选择 `deepseek-flash`：

- `Auto`：按 Redis 中的实时负载、近三分钟错误率、平均 TTFT、平均总耗时选择可用模型。
- 显式模型：只尝试指定模型；并发已满或熔断时直接拒绝，不降级到其他模型。

每个请求会执行以下 Redis 操作：

1. 调用前通过 Lua 原子获取模型并发租约。
2. 首 Token 到达时记录 TTFT。
3. 完成或失败时释放租约，并写入分钟桶指标。
4. 同一模型连续失败 3 次时熔断 30 秒。

运行态 key：

```text
push:agent:model:{modelName}:runtime
push:agent:model:{modelName}:leases
push:agent:model:{modelName}:metric:{minute}
```

相关配置：

```bash
PUSH_AGENT_DEEPSEEK_FLASH_MAX_CONCURRENCY=8
PUSH_AGENT_MODEL_REQUEST_LEASE_SECONDS=90
```

## LogID 日志分析工具

`LogAnalysisAgent` 实现了第一版“模型发现工具并查询日志”的能力：

```java
LogAnalysisAgent agent = new LogAnalysisAgent();
LogAnalysisResult result = agent.analyze(logId);
```

处理流程：

1. Agent 至少向模型发送 `query_push_logs_by_log_id` 工具定义；如果开启 MCP，也会追加远端 MCP 工具。
2. 第一轮模型被要求调用工具，且必须包含 `query_push_logs_by_log_id`。
3. 工具固定通过 `PushLogRepository.queryByLogId(...)` 查询 `push_log.trace_id`。
4. 本地工具和 MCP 工具结果都以 `role=tool` 回填给模型。
5. 第二轮模型仅输出日志分析结论。

工具不接收 SQL、表名或任意 Redis Key。模型传入的 LogID 必须与前端请求的 LogID 一致，实际查询也始终使用该请求值。

当前工具注册表在 `AgentToolRegistry` 中，统一通过 `AgentToolGateway` 执行。

## 流式 Agent Runner

会话型排障助手使用 `BulletAgentRunner` 收敛流式模型轮次：

1. 每轮通过 OpenAI-compatible SSE 调用模型。
2. `LlmAnalyzeClient` 聚合本轮 `delta.content` 与分片 `delta.tool_calls`。
3. 如果本轮包含工具调用，Runner 先执行 `AgentToolRegistry` 中的工具，把 `role=tool` 结果回填后继续下一轮。
4. 如果本轮没有工具调用，Runner 将该轮完整文本作为最终回答下发并结束。
5. 超过 5 轮工具循环直接失败，避免模型重复调用工具导致会话卡死。

会话流的业务结束条件是“模型 SSE 一轮结束，且这一轮没有聚合出工具调用”。底层 `[DONE]` 只代表当前模型请求的 SSE 结束；如果这一轮包含工具调用，Runner 会继续发起下一轮模型请求。

## MCP 配置

当前已支持两类 MCP 能力：

1. 当前节点通过 `/mcp` 暴露自己的只读诊断工具，支持 JSON-RPC `initialize`、`ping`、`tools/list` 和 `tools/call`。
2. LogID 分析 Agent 可加载外部 HTTP JSON-RPC MCP Server 的 `tools/list` 结果，并通过 `tools/call` 调用远端工具。

本节点工具发现示例：

```bash
curl -s http://127.0.0.1:9090/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}'
```

调用本地 LogID 工具示例：

```bash
curl -s http://127.0.0.1:9090/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"query_push_logs_by_log_id","arguments":{"logId":"System_20260902200530068","limit":20}}}'
```

外部 MCP 默认关闭，按需在 `config/local.properties` 中启用：

```properties
agent.mcp.enabled=true
agent.mcp.servers=diagnosis
agent.mcp.diagnosis.transport=http-jsonrpc
agent.mcp.diagnosis.endpoint=http://127.0.0.1:7001/mcp
agent.mcp.diagnosis.timeout.ms=15000
agent.mcp.result.max.length=16000
```

也支持对应环境变量：

```bash
PUSH_AGENT_MCP_ENABLED=true
PUSH_AGENT_MCP_SERVERS=diagnosis
PUSH_AGENT_MCP_DIAGNOSIS_ENDPOINT=http://127.0.0.1:7001/mcp
```

远端工具会以 `mcp_{serverName}_{toolName}` 的函数名暴露给模型，避免和本地工具重名。第一版只支持 HTTP JSON-RPC 形态；stdio 以及标准 SSE 长连接传输尚未接入。

## 阈值配置

支持 JVM 参数和环境变量覆盖默认值：

```bash
PUSH_AGENT_CPU_WARNING=70
PUSH_AGENT_CPU_CRITICAL=85
PUSH_AGENT_HEAP_WARNING=70
PUSH_AGENT_HEAP_CRITICAL=85
PUSH_AGENT_HEARTBEAT_WARNING_SECONDS=15
PUSH_AGENT_HEARTBEAT_CRITICAL_SECONDS=30
```

对应 JVM 参数：

```bash
-Dpush.agent.threshold.cpu.warning=70
-Dpush.agent.threshold.cpu.critical=85
-Dpush.agent.threshold.heap.warning=70
-Dpush.agent.threshold.heap.critical=85
-Dpush.agent.threshold.heartbeat.warning=15
-Dpush.agent.threshold.heartbeat.critical=30
```
