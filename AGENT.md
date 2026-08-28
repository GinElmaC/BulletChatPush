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

1. Agent 只向模型发送 LogID 和 `query_push_logs_by_log_id` 工具定义。
2. 第一轮模型被要求调用该工具。
3. 工具固定通过 `PushLogRepository.queryByLogId(...)` 查询 `push_log.trace_id`。
4. 工具结果以 `role=tool` 回填给模型。
5. 第二轮模型仅输出日志分析结论。

工具不接收 SQL、表名或任意 Redis Key。模型传入的 LogID 必须与前端请求的 LogID 一致，实际查询也始终使用该请求值。

当前工具注册表在 `AgentToolRegistry` 中；后续需要提供 MCP Server 时，可直接将 `AgentToolDefinition` 映射为 MCP `tools/list`，并将 `AgentTool#execute` 映射为 `tools/call`。

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
