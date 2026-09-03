package com.GinElmaC.NettyServer.Service;

import com.GinElmaC.NettyServer.Config.WebSocketGatewayConfig;
import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.log.LogIdGenerator;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 推送节点下行 Kafka Consumer。
 * 所有推送节点使用相同消费组，Kafka 会将每条 message_out 记录仅分配给一个节点。
 */
public class KafkaDownstreamMessageConsumer {
    private static final Log log = LogFactory.getLog(KafkaDownstreamMessageConsumer.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);

    private final RoomMessageDeliveryService deliveryService;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile KafkaConsumer<String, String> consumer;
    private Thread worker;

    public KafkaDownstreamMessageConsumer(RoomMessageDeliveryService deliveryService) {
        this.deliveryService = deliveryService;
    }

    public synchronized void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = new Thread(this::consumeLoop, "kafka-message-out-consumer");
        worker.setDaemon(true);
        worker.start();
    }

    private void consumeLoop() {
        try (KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(consumerProperties())) {
            consumer = kafkaConsumer;
            kafkaConsumer.subscribe(List.of(WebSocketGatewayConfig.DOWNSTREAM_TOPIC));
            log.Info("KAFKA_MESSAGE_OUT_CONSUMER_STARTED, topic:{}, group:{}",
                    WebSocketGatewayConfig.DOWNSTREAM_TOPIC,
                    WebSocketGatewayConfig.DOWNSTREAM_CONSUMER_GROUP);
            while (running.get()) {
                ConsumerRecords<String, String> records = kafkaConsumer.poll(POLL_TIMEOUT);
                processRecords(kafkaConsumer, records);
            }
        } catch (WakeupException e) {
            if (running.get()) {
                log.Error("KAFKA_MESSAGE_OUT_CONSUMER_WAKEUP_FAILED", e);
            }
        } catch (Exception e) {
            if (running.get()) {
                log.Error("KAFKA_MESSAGE_OUT_CONSUMER_FAILED", e);
            }
        } finally {
            consumer = null;
            running.set(false);
            log.Info("KAFKA_MESSAGE_OUT_CONSUMER_STOPPED");
        }
    }

    /**
     * 路由投递完成后才提交 offset。Redis 查询或跨节点转发失败时 seek 回当前记录，按至少一次语义重试。
     */
    private void processRecords(KafkaConsumer<String, String> kafkaConsumer, ConsumerRecords<String, String> records) {
        for (ConsumerRecord<String, String> record : records) {
            LogContext logContext = LogContext.create()
                    .traceId(LogIdGenerator.next())
                    .put("messageKey", record.key())
                    .put("topic", record.topic())
                    .put("partition", record.partition())
                    .put("offset", record.offset());
            try {
                log.Info(logContext, "KAFKA_MESSAGE_OUT_CONSUMED");
                deliveryService.deliverFromKafka(record.key(), record.value());
                TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
                kafkaConsumer.commitSync(Map.of(
                        topicPartition,
                        new OffsetAndMetadata(record.offset() + 1)
                ));
                log.Info(logContext, "KAFKA_MESSAGE_OUT_COMMITTED");
            } catch (Exception e) {
                kafkaConsumer.seek(new TopicPartition(record.topic(), record.partition()), record.offset());
                log.Error(logContext, "KAFKA_MESSAGE_OUT_ROUTE_FAILED", e);
                return;
            }
        }
    }

    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        KafkaConsumer<String, String> currentConsumer = consumer;
        if (currentConsumer != null) {
            currentConsumer.wakeup();
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    private Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, WebSocketGatewayConfig.BOOTSTRAP_SERVERS);
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, WebSocketGatewayConfig.SECURITY_PROTOCOL);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, WebSocketGatewayConfig.DOWNSTREAM_CONSUMER_GROUP);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return properties;
    }
}
