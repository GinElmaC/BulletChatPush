package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.NettyServer.Monitor.NodeDetail;
import com.GinElmaC.NettyServer.Monitor.NodeMetrics;
import com.GinElmaC.log.LogLevel;
import com.GinElmaC.log.PushLogRecord;
import com.GinElmaC.log.PushLogRepository;
import com.GinElmaC.log.PushLogSearchParam;
import com.GinElmaC.utils.JsonUtil;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 推送节点 Agent 分析入口。
 * 负责收集节点详情和日志、执行规则预分析、路由模型，并维护模型请求的完整生命周期。
 */
public class PushAgentAnalyzer {
    private final RuleAnalyzer ruleAnalyzer = new RuleAnalyzer();
    private final LlmAnalyzeClient llmAnalyzeClient = new LlmAnalyzeClient();
    private final PushLogRepository pushLogRepository = new PushLogRepository();
    private final AutoModelRouter autoModelRouter = new AutoModelRouter();

    /**
     * 分析当前进程所属节点，默认使用 Auto 模式。
     */
    public AgentAnalysisResult analyzeCurrentNode() {
        return analyzeCurrentNode(AgentModelRegistry.AUTO);
    }

    /**
     * 分析当前进程所属节点，并使用前端请求的模型模式。
     */
    public AgentAnalysisResult analyzeCurrentNode(String requestedModel) {
        try {
            NodeDetail nodeDetail = NodeMetrics.getInstance().snapshot();
            List<PushLogRecord> logs = queryRecentLogs(nodeDetail.getMachineId());
            return analyze(nodeDetail, logs, requestedModel);
        } catch (Exception e) {
            return AgentAnalysisResult.serviceAnalyzeFailed();
        }
    }

    public AgentAnalysisResult analyze(NodeDetail nodeDetail, List<PushLogRecord> logs) {
        return analyze(nodeDetail, logs, AgentModelRegistry.AUTO);
    }

    public AgentAnalysisResult analyze(NodeDetail nodeDetail, List<PushLogRecord> logs, String requestedModel) {
        AgentAnalysisResult result;
        try {
            result = ruleAnalyzer.analyze(nodeDetail, logs);
        } catch (Exception e) {
            return AgentAnalysisResult.serviceAnalyzeFailed();
        }
        AgentModelLease lease;
        try {
            lease = autoModelRouter.acquire(requestedModel);
        } catch (Exception e) {
            return AgentAnalysisResult.serviceAnalyzeFailed();
        }
        try {
            result.setModelName(lease.model().name());
            String summary = llmAnalyzeClient.analyze(lease.model(), buildPrompt(nodeDetail, logs, result));
            if (summary == null || summary.isBlank()) {
                markFailed(lease);
                return AgentAnalysisResult.serviceAnalyzeFailed();
            }
            result.setLlmSummary(summary);
            // 非流式请求没有单独的首 Token 事件，以响应完成时间作为保守估算。
            autoModelRouter.complete(lease, System.currentTimeMillis());
        } catch (Exception e) {
            markFailed(lease);
            return AgentAnalysisResult.serviceAnalyzeFailed();
        }
        return result;
    }

    public void analyzeCurrentNodeStream(Consumer<String> chunkConsumer) {
        analyzeCurrentNodeStream(new AgentStreamObserver() {
            @Override
            public void onChunk(String chunk) {
                chunkConsumer.accept(chunk);
            }

            @Override
            public void onFailed() {
                chunkConsumer.accept(AgentAnalysisResult.SERVICE_ANALYZE_FAILED);
            }
        });
    }

    /**
     * 当前节点流式分析入口，供 SSE/WebSocket 接入层使用。
     */
    public void analyzeCurrentNodeStream(AgentStreamObserver observer) {
        try {
            NodeDetail nodeDetail = NodeMetrics.getInstance().snapshot();
            List<PushLogRecord> logs = queryRecentLogs(nodeDetail.getMachineId());
            analyzeStream(nodeDetail, logs, AgentModelRegistry.AUTO, observer);
        } catch (Exception e) {
            observer.onFailed();
        }
    }

    public void analyzeStream(NodeDetail nodeDetail, List<PushLogRecord> logs, Consumer<String> chunkConsumer) {
        analyzeStream(nodeDetail, logs, AgentModelRegistry.AUTO, new AgentStreamObserver() {
            @Override
            public void onChunk(String chunk) {
                chunkConsumer.accept(chunk);
            }

            @Override
            public void onFailed() {
                chunkConsumer.accept(AgentAnalysisResult.SERVICE_ANALYZE_FAILED);
            }
        });
    }

    /**
     * 兼容使用 Consumer 的调用方；无法撤回已发送内容时，仅追加固定失败文案。
     */
    public void analyzeStream(NodeDetail nodeDetail, List<PushLogRecord> logs, String requestedModel, Consumer<String> chunkConsumer) {
        analyzeStream(nodeDetail, logs, requestedModel, new AgentStreamObserver() {
            @Override
            public void onChunk(String chunk) {
                chunkConsumer.accept(chunk);
            }

            @Override
            public void onFailed() {
                chunkConsumer.accept(AgentAnalysisResult.SERVICE_ANALYZE_FAILED);
            }
        });
    }

    /**
     * 通过显式失败事件支持 SSE/WebSocket 清空已发送草稿，避免服务异常时展示分析内容。
     */
    public void analyzeStream(NodeDetail nodeDetail, List<PushLogRecord> logs, String requestedModel, AgentStreamObserver observer) {
        AgentAnalysisResult result;
        try {
            result = ruleAnalyzer.analyze(nodeDetail, logs);
        } catch (Exception e) {
            observer.onFailed();
            return;
        }
        AgentModelLease lease;
        try {
            lease = autoModelRouter.acquire(requestedModel);
        } catch (Exception e) {
            observer.onFailed();
            return;
        }
        result.setModelName(lease.model().name());
        AtomicLong firstTokenAtMillis = new AtomicLong();
        try {
            llmAnalyzeClient.analyzeStream(lease.model(), buildPrompt(nodeDetail, logs, result), chunk -> {
                // 首 Token 延迟从模型实际输出第一个内容片段开始计算，而不是 HTTP 连接建立时间。
                firstTokenAtMillis.compareAndSet(0, System.currentTimeMillis());
                observer.onChunk(chunk);
            });
            if (firstTokenAtMillis.get() == 0) {
                markFailed(lease);
                observer.onFailed();
                return;
            }
            autoModelRouter.complete(lease, firstTokenAtMillis.get());
        } catch (Exception e) {
            markFailed(lease);
            observer.onFailed();
        }
    }

    /**
     * 指标上报失败不能覆盖原始失败结果，也不能向调用方暴露 Redis 异常。
     */
    private void markFailed(AgentModelLease lease) {
        try {
            autoModelRouter.fail(lease);
        } catch (Exception ignored) {
        }
    }

    private List<PushLogRecord> queryRecentLogs(Integer machineId) {
        if (machineId == null) {
            return Collections.emptyList();
        }
        try {
            // 节点日志只通过 push_log 专属 Repository 查询，避免与其他表的查询逻辑耦合。
            return pushLogRepository.queryByMachineId(new PushLogSearchParam()
                    .setMachineId(machineId)
                    .setLevel(LogLevel.ERROR)
                    .setLimit(50)
                    .setOffset(0));
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String buildPrompt(NodeDetail nodeDetail, List<PushLogRecord> logs, AgentAnalysisResult ruleResult) {
        // 向模型传递结构化上下文和规则结论，减少模型自行猜测指标含义的情况。
        return """
                请分析以下推送节点状态。

                节点详情：
                %s

                最近ERROR日志：
                %s

                阈值说明：
                %s

                规则预分析：
                %s

                请输出：
                - 健康等级
                - 核心结论
                - 证据
                - 可能原因
                - 建议操作
                """.formatted(
                JsonUtil.toJson(nodeDetail),
                JsonUtil.toJson(logs),
                JsonUtil.toJson(thresholds()),
                JsonUtil.toJson(ruleResult)
        );
    }

    private AgentThresholdView thresholds() {
        return new AgentThresholdView(
                AgentThresholdConfig.CPU_WARNING,
                AgentThresholdConfig.CPU_CRITICAL,
                AgentThresholdConfig.HEAP_WARNING,
                AgentThresholdConfig.HEAP_CRITICAL,
                AgentThresholdConfig.HEARTBEAT_WARNING_SECONDS,
                AgentThresholdConfig.HEARTBEAT_CRITICAL_SECONDS
        );
    }

    private record AgentThresholdView(double cpuWarning, double cpuCritical, double heapWarning, double heapCritical,
                                      long heartbeatWarningSeconds, long heartbeatCriticalSeconds) {
    }
}
