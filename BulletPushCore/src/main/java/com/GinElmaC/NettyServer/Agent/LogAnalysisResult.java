package com.GinElmaC.NettyServer.Agent;

/**
 * 按 LogID 分析日志后的最终结果。
 * 服务失败时只保留 analysis 为固定失败文案，不回传 LogID、模型名或内部错误。
 */
public class LogAnalysisResult {
    private String logId;
    private String modelName;
    private String analysis;

    public static LogAnalysisResult serviceAnalyzeFailed() {
        LogAnalysisResult result = new LogAnalysisResult();
        result.setAnalysis(AgentAnalysisResult.SERVICE_ANALYZE_FAILED);
        return result;
    }

    public String getLogId() {
        return logId;
    }

    public void setLogId(String logId) {
        this.logId = logId;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }
}
