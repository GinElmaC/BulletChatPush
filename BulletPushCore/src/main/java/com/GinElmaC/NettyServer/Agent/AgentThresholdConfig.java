package com.GinElmaC.NettyServer.Agent;

/**
 * 节点规则分析阈值。
 * 阈值优先从 JVM 参数读取，再读取环境变量，最后使用当前默认值。
 */
public class AgentThresholdConfig {
    // CPU 使用率阈值，单位百分比。
    public static final double CPU_WARNING = readDouble("push.agent.threshold.cpu.warning", "PUSH_AGENT_CPU_WARNING", 70);
    public static final double CPU_CRITICAL = readDouble("push.agent.threshold.cpu.critical", "PUSH_AGENT_CPU_CRITICAL", 85);
    public static final double HEAP_WARNING = readDouble("push.agent.threshold.heap.warning", "PUSH_AGENT_HEAP_WARNING", 70);
    public static final double HEAP_CRITICAL = readDouble("push.agent.threshold.heap.critical", "PUSH_AGENT_HEAP_CRITICAL", 85);
    // 最近心跳距离当前时间的阈值，单位秒。
    public static final long HEARTBEAT_WARNING_SECONDS = readLong("push.agent.threshold.heartbeat.warning", "PUSH_AGENT_HEARTBEAT_WARNING_SECONDS", 15);
    public static final long HEARTBEAT_CRITICAL_SECONDS = readLong("push.agent.threshold.heartbeat.critical", "PUSH_AGENT_HEARTBEAT_CRITICAL_SECONDS", 30);

    private static double readDouble(String propertyName, String envName, double defaultValue) {
        String value = readConfig(propertyName, envName);
        if (!hasText(value)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long readLong(String propertyName, String envName, long defaultValue) {
        String value = readConfig(propertyName, envName);
        if (!hasText(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String readConfig(String propertyName, String envName) {
        // JVM 参数便于启动时覆盖，环境变量便于容器化部署。
        String value = System.getProperty(propertyName);
        if (hasText(value)) {
            return value;
        }
        return System.getenv(envName);
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
