package com.GinElmaC.NettyServer.Config;

import java.util.Arrays;
import java.util.List;

/**
 * WebSocket Gateway 配置。
 * 对外节点列表必须显式配置，客户端不会推测或伪造可连接节点。
 */
public class WebSocketGatewayConfig {
    public static final int PORT = readInt("push.websocket.port", "PUSH_WEBSOCKET_PORT", 8082);
    /**
     * 客户端上行消息固定写入 message_in；部署时可通过环境变量或 JVM 参数替换。
     */
    public static final String UPSTREAM_TOPIC = readConfig(
            "push.kafka.upstreamTopic",
            "PUSH_KAFKA_UPSTREAM_TOPIC",
            "message_in"
    );
    /**
     * Kafka 集群地址。该值不是密钥，仍保留环境变量与 JVM 参数覆盖能力。
     */
    public static final String BOOTSTRAP_SERVERS = readConfig(
            "push.kafka.bootstrapServers",
            "PUSH_KAFKA_BOOTSTRAP_SERVERS",
            "172.23.218.49:9092"
    );
    /**
     * 当前 Kafka 集群不启用认证，客户端必须显式使用 PLAINTEXT。
     */
    public static final String SECURITY_PROTOCOL = readConfig(
            "push.kafka.securityProtocol",
            "PUSH_KAFKA_SECURITY_PROTOCOL",
            "PLAINTEXT"
    );
    public static final String PUBLIC_NODE_ENDPOINTS = readConfig(
            "push.websocket.nodeEndpoints",
            "PUSH_WEBSOCKET_NODE_ENDPOINTS",
            "ws://localhost:8082/ws"
    );

    private WebSocketGatewayConfig() {
    }

    /**
     * 返回前端可以随机选择的真实节点地址，例如 ws://10.0.1.101:8082/ws。
     */
    public static List<String> publicNodeEndpoints() {
        if (PUBLIC_NODE_ENDPOINTS == null || PUBLIC_NODE_ENDPOINTS.isBlank()) {
            return List.of();
        }
        return Arrays.stream(PUBLIC_NODE_ENDPOINTS.split(","))
                .map(String::trim)
                .filter(endpoint -> !endpoint.isBlank())
                .toList();
    }

    public static boolean kafkaEnabled() {
        return hasText(BOOTSTRAP_SERVERS) && hasText(UPSTREAM_TOPIC);
    }

    private static String readConfig(String propertyName, String envName, String defaultValue) {
        String value = System.getProperty(propertyName);
        if (hasText(value)) {
            return value;
        }
        value = System.getenv(envName);
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
}
