package com.GinElmaC.redis;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Redis 连接配置。
 * 配置优先级：JVM 参数 > 环境变量 > 本地忽略配置文件 > 默认值。
 */
public class RedisConfig {
    private static final Path LOCAL_CONFIG_PATH = Path.of(
            System.getProperty("push.local.config", "config/local.properties")
    );
    private static final Properties LOCAL_PROPERTIES = loadLocalProperties();

    // 远程 Redis 的公网地址；本地调试可通过 JVM 参数或环境变量覆盖。
    public static final String REDIS_HOST = readConfig(
            "push.redis.host",
            "PUSH_REDIS_HOST",
            "47.102.43.148"
    );
    public static final int REDIS_PORT = readInt(
            "push.redis.port",
            "PUSH_REDIS_PORT",
            6379
    );
    // 不在源码保存密码。未开启认证的 Redis 可不设置该变量。
    public static final String REDIS_PASSWORD = readConfig(
            "push.redis.password",
            "PUSH_REDIS_PASSWORD",
            null
    );

    private RedisConfig() {
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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 本地密钥配置文件被 Git 忽略，适用于 IDE 直接启动与本机调试。
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
            throw new IllegalStateException("load local redis config failed", e);
        }
    }
}
