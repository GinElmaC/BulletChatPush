package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.NettyServer.Monitor.NodeDetail;
import com.GinElmaC.log.PushLogRecord;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 节点健康规则分析器。
 * 规则分析不依赖 LLM，用于为模型提供稳定的基础判断和结构化上下文。
 */
public class RuleAnalyzer {
    /**
     * 根据资源指标、心跳和近期日志累计风险分数，再映射为健康等级和操作建议。
     */
    public AgentAnalysisResult analyze(NodeDetail nodeDetail, List<PushLogRecord> logs) {
        AgentAnalysisResult result = new AgentAnalysisResult();
        int score = 0;

        if (nodeDetail.getCpuUsage() != null) {
            if (nodeDetail.getCpuUsage() >= AgentThresholdConfig.CPU_CRITICAL) {
                score += 3;
                result.getEvidence().add("CPU使用率达到" + format(nodeDetail.getCpuUsage()) + "%，超过严重阈值");
                result.getPossibleReasons().add("节点可能存在消息处理堆积或热点连接");
            } else if (nodeDetail.getCpuUsage() >= AgentThresholdConfig.CPU_WARNING) {
                score += 1;
                result.getEvidence().add("CPU使用率达到" + format(nodeDetail.getCpuUsage()) + "%，超过告警阈值");
            }
        }

        // 堆内存以使用率判断，避免不同堆大小的节点无法横向比较。
        double heapRatio = heapRatio(nodeDetail);
        if (heapRatio >= AgentThresholdConfig.HEAP_CRITICAL) {
            score += 3;
            result.getEvidence().add("堆内存使用率达到" + format(heapRatio) + "%，超过严重阈值");
            result.getPossibleReasons().add("节点可能存在对象堆积、连接未释放或消息缓存过大");
        } else if (heapRatio >= AgentThresholdConfig.HEAP_WARNING) {
            score += 1;
            result.getEvidence().add("堆内存使用率达到" + format(heapRatio) + "%，超过告警阈值");
        }

        // 心跳距离当前时间越久，说明事件循环、网络或节点进程可能出现异常。
        long heartbeatDelay = heartbeatDelaySeconds(nodeDetail);
        if (heartbeatDelay >= AgentThresholdConfig.HEARTBEAT_CRITICAL_SECONDS) {
            score += 3;
            result.getEvidence().add("最近心跳延迟" + heartbeatDelay + "秒，超过严重阈值");
            result.getPossibleReasons().add("节点可能已阻塞、网络不稳定或事件循环处理不及时");
        } else if (heartbeatDelay >= AgentThresholdConfig.HEARTBEAT_WARNING_SECONDS) {
            score += 1;
            result.getEvidence().add("最近心跳延迟" + heartbeatDelay + "秒，超过告警阈值");
        }

        // ERROR 按最多 3 分计入风险，避免大量重复日志无限放大单一故障。
        long errorCount = logs == null ? 0 : logs.stream().filter(log -> "ERROR".equals(log.getLevelName())).count();
        long warnCount = logs == null ? 0 : logs.stream().filter(log -> "WARN".equals(log.getLevelName())).count();
        if (errorCount > 0) {
            score += Math.min(errorCount, 3);
            result.getEvidence().add("最近日志存在" + errorCount + "条ERROR");
            result.getPossibleReasons().add("业务处理或网络链路存在失败，需要结合错误日志定位");
        }
        if (warnCount > 0) {
            result.getEvidence().add("最近日志存在" + warnCount + "条WARN");
        }
        if (nodeDetail.getLastErrorMessage() != null && !"-".equals(nodeDetail.getLastErrorMessage())) {
            score += 2;
            result.getEvidence().add("最近错误：" + nodeDetail.getLastErrorMessage());
        }

        fillResult(result, score);
        return result;
    }

    private void fillResult(AgentAnalysisResult result, int score) {
        // 风险等级同时决定默认建议，LLM 仅负责补充解释而不覆盖这个基础判断。
        if (score >= 5) {
            result.setHealthLevel("CRITICAL");
            result.setConclusion("节点存在明显异常风险，需要尽快处理。");
            result.getSuggestions().add("优先查看最近ERROR日志，确认失败是否集中在连接、推送或路由链路。");
            result.getSuggestions().add("如果CPU、内存或心跳异常持续存在，建议先摘流再执行重启。");
            return;
        }
        if (score >= 2) {
            result.setHealthLevel("WARNING");
            result.setConclusion("节点存在轻中度风险，需要持续观察。");
            result.getSuggestions().add("关注CPU、堆内存和心跳延迟是否继续上升。");
            result.getSuggestions().add("查看WARN/ERROR日志，确认是否有同类错误持续出现。");
            return;
        }
        result.setHealthLevel("HEALTHY");
        result.setConclusion("节点当前指标未触发明显风险。");
        result.getSuggestions().add("保持观察，按固定周期刷新节点详情和日志。");
    }

    private double heapRatio(NodeDetail nodeDetail) {
        if (nodeDetail.getHeapUsed() == null || nodeDetail.getHeapMax() == null || nodeDetail.getHeapMax() <= 0) {
            return 0;
        }
        return nodeDetail.getHeapUsed() * 100.0 / nodeDetail.getHeapMax();
    }

    private long heartbeatDelaySeconds(NodeDetail nodeDetail) {
        if (nodeDetail.getLastHeartbeatTime() == null) {
            return 0;
        }
        return Duration.between(nodeDetail.getLastHeartbeatTime(), LocalDateTime.now()).getSeconds();
    }

    private String format(double value) {
        return String.format("%.1f", value);
    }
}
