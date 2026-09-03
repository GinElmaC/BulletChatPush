package com.GinElmaC.service;

/**
 * 业务 Service 的 Kafka 配置。
 * 所有实例使用同一 input consumer group，使一条 message_in 只由一个业务实例处理。
 */
public final class BusinessKafkaConfig {
    public static final String BOOTSTRAP_SERVERS = readConfig(
            "push.kafka.bootstrapServers",
            "PUSH_KAFKA_BOOTSTRAP_SERVERS",
            "47.102.43.148:9092"
    );
    public static final String SECURITY_PROTOCOL = readConfig(
            "push.kafka.securityProtocol",
            "PUSH_KAFKA_SECURITY_PROTOCOL",
            "PLAINTEXT"
    );
    public static final String INPUT_TOPIC = readConfig(
            "push.kafka.inputTopic",
            "PUSH_KAFKA_INPUT_TOPIC",
            "message_in"
    );
    public static final String OUTPUT_TOPIC = readConfig(
            "push.kafka.outputTopic",
            "PUSH_KAFKA_OUTPUT_TOPIC",
            "message_out"
    );
    public static final String CONSUMER_GROUP = readConfig(
            "push.kafka.businessConsumerGroup",
            "PUSH_KAFKA_BUSINESS_CONSUMER_GROUP",
            "push-business-service"
    );

    private BusinessKafkaConfig() {
    }

    private static String readConfig(String propertyName, String envName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (hasText(value)) {
            return value;
        }
        value = System.getenv(envName);
        return hasText(value) ? value : defaultValue;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
