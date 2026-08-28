package com.GinElmaC.NettyServer.Agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 分析结果。
 * 模型调用成功后返回完整分析；服务异常时仅返回固定失败文案。
 */
public class AgentAnalysisResult {
    public static final String SERVICE_ANALYZE_FAILED = "服务分析失败";
    // 节点健康等级：HEALTHY、WARNING、CRITICAL。
    private String healthLevel;
    // 基于规则或模型得出的简要结论。
    private String conclusion;
    // 支撑结论的指标与日志证据。
    private List<String> evidence = new ArrayList<>();
    // 根据当前证据推断出的可能原因。
    private List<String> possibleReasons = new ArrayList<>();
    // 提供给运维人员的后续操作建议。
    private List<String> suggestions = new ArrayList<>();
    // LLM 的自然语言总结。
    private String llmSummary;
    // 本次实际调用的模型名称。
    private String modelName;
    // 预留的内部错误字段，不向外暴露底层异常信息。
    private String modelError;

    /**
     * 生成不包含节点、日志、模型或异常细节的固定失败结果。
     */
    public static AgentAnalysisResult serviceAnalyzeFailed() {
        AgentAnalysisResult result = new AgentAnalysisResult();
        result.setConclusion(SERVICE_ANALYZE_FAILED);
        // 失败响应不携带空证据、空原因或空建议，序列化后仅保留失败文案。
        result.setEvidence(null);
        result.setPossibleReasons(null);
        result.setSuggestions(null);
        return result;
    }

    public String getHealthLevel() {
        return healthLevel;
    }

    public void setHealthLevel(String healthLevel) {
        this.healthLevel = healthLevel;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence;
    }

    public List<String> getPossibleReasons() {
        return possibleReasons;
    }

    public void setPossibleReasons(List<String> possibleReasons) {
        this.possibleReasons = possibleReasons;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public String getLlmSummary() {
        return llmSummary;
    }

    public void setLlmSummary(String llmSummary) {
        this.llmSummary = llmSummary;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getModelError() {
        return modelError;
    }

    public void setModelError(String modelError) {
        this.modelError = modelError;
    }
}
