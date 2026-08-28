package com.GinElmaC.NettyServer.Service;

import com.GinElmaC.NettyServer.Config.WebSocketGatewayConfig;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
/**
 * WebSocket 上行消息 Kafka Producer。
 * Producer 全局复用；调用方根据 Future 结果决定是否向客户端确认消息已被中台接收。
 */
public class KafkaUpstreamMessageProducer {
    private KafkaProducer<String, String> producer;

    public synchronized void send(String key, String payload, Callback callback) {
        if (!WebSocketGatewayConfig.kafkaEnabled()) {
            throw new IllegalStateException("kafka upstream config is empty");
        }
        producer().send(new ProducerRecord<>(WebSocketGatewayConfig.UPSTREAM_TOPIC, key, payload), callback);
    }

    public synchronized void close() {
        if (producer != null) {
            producer.close();
            producer = null;
        }
    }

    private KafkaProducer<String, String> producer() {
        if (producer != null) {
            return producer;
        }
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, WebSocketGatewayConfig.BOOTSTRAP_SERVERS);
        // Kafka 集群未启用 SASL/SSL，显式指定为 PLAINTEXT，避免客户端按其他协议握手。
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, WebSocketGatewayConfig.SECURITY_PROTOCOL);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        producer = new KafkaProducer<>(properties);
        return producer;
    }
}
