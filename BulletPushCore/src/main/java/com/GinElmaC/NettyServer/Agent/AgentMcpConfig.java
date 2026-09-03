package com.GinElmaC.NettyServer.Agent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Agent MCP 配置。
 * 配置优先级：JVM 参数 > 环境变量 > 本地忽略配置文件 > 默认值。
 */
public class AgentMcpConfig {
    private static final Path LOCAL_CONFIG_PATH = Path.of(
            System.getProperty("push.local.config", "config/local.properties")
    );
    private static final Properties LOCAL_PROPERTIES = loadLocalProperties();

    public static final boolean ENABLED = readBoolean("agent.mcp.enabled", "PUSH_AGENT_MCP_ENABLED", false);
    public static final int RESULT_MAX_LENGTH = readInt("agent.mcp.result.max.length",
            "PUSH_AGENT_MCP_RESULT_MAX_LENGTH", 16_000);

    private AgentMcpConfig() {
    }

    public static List<McpServerConfig> servers() {
        if (!ENABLED) {
            return List.of();
        }
        String names = readConfig("agent.mcp.servers", "PUSH_AGENT_MCP_SERVERS", "");
        if (!hasText(names)) {
            return List.of();
        }
        return Arrays.stream(names.split(","))
                .map(String::trim)
                .filter(AgentMcpConfig::hasText)
                .map(AgentMcpConfig::server)
                .filter(McpServerConfig::enabled)
                .filter(config -> hasText(config.endpoint()))
                .toList();
    }

    private static McpServerConfig server(String name) {
        String prefix = "agent.mcp." + name + ".";
        return new McpServerConfig(
                name,
                readConfig(prefix + "transport", envName(name, "TRANSPORT"), "http-jsonrpc"),
                readConfig(prefix + "endpoint", envName(name, "ENDPOINT"), null),
                readInt(prefix + "timeout.ms", envName(name, "TIMEOUT_MS"), 15_000),
                readBoolean(prefix + "enabled", envName(name, "ENABLED"), true)
        );
    }

    private static String envName(String serverName, String suffix) {
        return "PUSH_AGENT_MCP_" + serverName.toUpperCase().replaceAll("[^A-Z0-9]", "_") + "_" + suffix;
    }

    private static String readConfig(String propertyName, String envName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (hasText(value)) {
            return value;
        }
        value = System.getenv(envName);
        if (hasText(value)) {
            return value;
        }
        value = LOCAL_PROPERTIES.getProperty(propertyName);
        return hasText(value) ? value : defaultValue;
    }

    private static int readInt(String propertyName, String envName, int defaultValue) {
        String value = readConfig(propertyName, envName, null);
        if (!hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean readBoolean(String propertyName, String envName, boolean defaultValue) {
        String value = readConfig(propertyName, envName, null);
        return hasText(value) ? Boolean.parseBoolean(value) : defaultValue;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static Properties loadLocalProperties() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(LOCAL_CONFIG_PATH)) {
            return properties;
        }
        try (InputStream inputStream = Files.newInputStream(LOCAL_CONFIG_PATH)) {
            properties.load(inputStream);
            return properties;
        } catch (Exception e) {
            throw new IllegalStateException("load local mcp config failed", e);
        }
    }
}
