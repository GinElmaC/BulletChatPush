package com.GinElmaC.log;

public class LogMysqlConfig {
    public static final String MYSQL_URL = readConfig("push.log.mysql.url", "PUSH_LOG_MYSQL_URL");
    public static final String MYSQL_USER = readConfig("push.log.mysql.user", "PUSH_LOG_MYSQL_USER");
    public static final String MYSQL_PASSWORD = readConfig("push.log.mysql.password", "PUSH_LOG_MYSQL_PASSWORD");

    public static boolean enabled() {
        return hasText(MYSQL_URL) && hasText(MYSQL_USER);
    }

    private static String readConfig(String propertyName, String envName) {
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
