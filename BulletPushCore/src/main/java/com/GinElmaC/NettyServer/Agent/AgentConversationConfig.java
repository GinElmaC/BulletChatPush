package com.GinElmaC.NettyServer.Agent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Agent 会话配置。
 * 当前管理后台为单用户模式，uid 仍由后端统一注入，为未来接入登录体系保留隔离边界。
 */
public final class AgentConversationConfig {
    private static final Path LOCAL_CONFIG_PATH = Path.of(
            System.getProperty("push.local.config", "config/local.properties")
    );
    private static final Properties LOCAL_PROPERTIES = loadLocalProperties();

    public static final long DEFAULT_UID = readLong(
            "push.agent.default.uid",
            "PUSH_AGENT_DEFAULT_UID",
            1L
    );
    public static final int SESSION_TTL_SECONDS = readInt(
            "push.agent.session.ttl.seconds",
            "PUSH_AGENT_SESSION_TTL_SECONDS",
            30 * 60
    );
    public static final int MAX_RECENT_TURNS = readInt(
            "push.agent.session.max.recent.turns",
            "PUSH_AGENT_SESSION_MAX_RECENT_TURNS",
            10
    );
    public static final int MAX_SUMMARY_LENGTH = readInt(
            "push.agent.session.max.summary.length",
            "PUSH_AGENT_SESSION_MAX_SUMMARY_LENGTH",
            6000
    );

    private AgentConversationConfig() {
    }

    private static int readInt(String propertyName, String envName, int defaultValue) {
        String value = readConfig(propertyName, envName);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(Integer.parseInt(value), 1);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long readLong(String propertyName, String envName, long defaultValue) {
        String value = readConfig(propertyName, envName);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Math.max(Long.parseLong(value), 1L);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String readConfig(String propertyName, String envName) {
        String value = System.getProperty(propertyName);
        if (hasText(value)) {
            return value.trim();
        }
        value = System.getenv(envName);
        if (hasText(value)) {
            return value.trim();
        }
        value = LOCAL_PROPERTIES.getProperty(propertyName);
        return hasText(value) ? value.trim() : null;
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
            throw new IllegalStateException("load local agent conversation config failed", e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
