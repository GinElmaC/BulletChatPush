package com.GinElmaC.NettyServer.Agent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Agent 模型配置。
 * 配置优先级：JVM 参数 > 环境变量 > 本地忽略配置文件 > 代码默认值。
 */
public class AgentModelConfig {
    private static final Path LOCAL_CONFIG_PATH = Path.of(
            System.getProperty("push.local.config", "config/local.properties")
    );
    private static final Properties LOCAL_PROPERTIES = loadLocalProperties();

    // OpenAI-compatible 模型网关根地址或 chat/completions 完整地址。
    public static final String BASE_URL = readConfig("push.agent.model.baseUrl", "PUSH_AGENT_MODEL_BASE_URL");
    // 模型网关鉴权密钥。
    public static final String API_KEY = readConfig("push.agent.model.apiKey", "PUSH_AGENT_MODEL_API_KEY");
    // 当前已注册模型的实际名称，默认使用 deepseek-flash。
    public static final String MODEL_NAME = readConfig("push.agent.model.name", "PUSH_AGENT_MODEL_NAME", "deepseek-flash");
    // 单模型允许同时执行的最大流式请求数。
    public static final int DEEPSEEK_FLASH_MAX_CONCURRENCY = readInt(
            "push.agent.deepseekFlash.maxConcurrency",
            "PUSH_AGENT_DEEPSEEK_FLASH_MAX_CONCURRENCY",
            8
    );
    // 异常断开后并发租约的最长保留时间，避免 in_flight 永久泄漏。
    public static final int REQUEST_LEASE_SECONDS = readInt(
            "push.agent.model.requestLeaseSeconds",
            "PUSH_AGENT_MODEL_REQUEST_LEASE_SECONDS",
            90
    );

    public static boolean enabled() {
        return hasText(BASE_URL) && hasText(API_KEY) && hasText(MODEL_NAME);
    }

    /**
     * 读取没有默认值的字符串配置。
     */
    private static String readConfig(String propertyName, String envName) {
        return readConfig(propertyName, envName, null);
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
        String value = readConfig(propertyName, envName);
        if (!hasText(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * local.properties 仅用于本地启动，文件被 Git 忽略，避免 LLM 密钥进入版本库。
     */
    private static Properties loadLocalProperties() {
        Properties properties = new Properties();
        if (!Files.isRegularFile(LOCAL_CONFIG_PATH)) {
            return properties;
        }
        try (InputStream inputStream = Files.newInputStream(LOCAL_CONFIG_PATH)) {
            properties.load(inputStream);
            return properties;
        } catch (Exception e) {
            throw new IllegalStateException("load local agent config failed", e);
        }
    }
}
