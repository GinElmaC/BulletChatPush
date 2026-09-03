package com.GinElmaC.service;

import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.log.LogIdGenerator;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 业务层 Kafka 消费服务。
 * 该服务从 message_in 获取消息，执行业务处理后写入 message_out，为后续推送节点下行消费提供消息来源。
 */
public class KafkaBusinessMessageService {
    private static final Log log = LogFactory.getLog(KafkaBusinessMessageService.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500);
    private static final Duration PRODUCE_TIMEOUT = Duration.ofSeconds(10);

    private final AtomicBoolean running = new AtomicBoolean();
    private final BusinessMessageProcessor messageProcessor;
    private volatile KafkaConsumer<String, String> consumer;

    public KafkaBusinessMessageService() {
        this(new PrintBusinessMessageProcessor());
    }

    KafkaBusinessMessageService(BusinessMessageProcessor messageProcessor) {
        this.messageProcessor = messageProcessor;
    }

    /**
     * 在当前线程持续消费。业务 Service 应以独立进程运行，不能放入推送节点的 Netty 线程中。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try (KafkaConsumer<String, String> kafkaConsumer = new KafkaConsumer<>(consumerProperties());
             KafkaProducer<String, String> producer = new KafkaProducer<>(producerProperties())) {
            consumer = kafkaConsumer;
            kafkaConsumer.subscribe(List.of(BusinessKafkaConfig.INPUT_TOPIC));
            log.Info("BUSINESS_KAFKA_SERVICE_STARTED, inputTopic:{}, outputTopic:{}, group:{}",
                    BusinessKafkaConfig.INPUT_TOPIC,
                    BusinessKafkaConfig.OUTPUT_TOPIC,
                    BusinessKafkaConfig.CONSUMER_GROUP);

            while (running.get()) {
                ConsumerRecords<String, String> records = kafkaConsumer.poll(POLL_TIMEOUT);
                if (!processRecords(kafkaConsumer, producer, records)) {
                    // 失败记录已 seek 回原 offset，短暂等待后重试，避免持续空转占满 CPU。
                    Thread.sleep(1_000);
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                throw e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            consumer = null;
            running.set(false);
            log.Info("BUSINESS_KAFKA_SERVICE_STOPPED");
        }
    }

    /**
     * 每条 message_in 成功写入 message_out 后立刻提交该分区 offset。
     * 如果业务处理或 Kafka 写入失败，回退到当前 offset，使消息按至少一次语义重试。
     */
    private boolean processRecords(
            KafkaConsumer<String, String> kafkaConsumer,
            KafkaProducer<String, String> producer,
            ConsumerRecords<String, String> records
    ) {
        for (ConsumerRecord<String, String> record : records) {
            LogContext logContext = createLogContext(record);
            try {
                log.Info(logContext
                                .put("inputTopic", record.topic())
                                .put("inputPartition", record.partition())
                                .put("inputOffset", record.offset()),
                        "KAFKA_MESSAGE_IN_CONSUMED");

                // 当前处理器仅打印消息；后续接入真实业务时必须保留 logId、roomId 等路由字段。
                String outputPayload = messageProcessor.process(record.key(), record.value(), logContext);
                producer.send(new ProducerRecord<>(
                                BusinessKafkaConfig.OUTPUT_TOPIC,
                                record.key(),
                                outputPayload
                        ))
                        .get(PRODUCE_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

                TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
                kafkaConsumer.commitSync(Map.of(
                        topicPartition,
                        new OffsetAndMetadata(record.offset() + 1)
                ));
                log.Info(logContext
                                .put("outputTopic", BusinessKafkaConfig.OUTPUT_TOPIC)
                                .put("outputPartition", record.partition()),
                        "KAFKA_MESSAGE_OUT_PUBLISHED");
            } catch (Exception e) {
                TopicPartition topicPartition = new TopicPartition(record.topic(), record.partition());
                kafkaConsumer.seek(topicPartition, record.offset());
                log.Error(logContext
                                .put("inputTopic", record.topic())
                                .put("inputPartition", record.partition())
                                .put("inputOffset", record.offset()),
                        "KAFKA_BUSINESS_MESSAGE_PROCESS_FAILED",
                        e);
                return false;
            }
        }
        return true;
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            KafkaConsumer<String, String> currentConsumer = consumer;
            if (currentConsumer != null) {
                // wakeup 可立即中断 poll，保证 shutdown hook 不必等待轮询超时。
                currentConsumer.wakeup();
            }
        }
    }

    private Properties consumerProperties() {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BusinessKafkaConfig.BOOTSTRAP_SERVERS);
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, BusinessKafkaConfig.SECURITY_PROTOCOL);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, BusinessKafkaConfig.CONSUMER_GROUP);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return properties;
    }

    private Properties producerProperties() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BusinessKafkaConfig.BOOTSTRAP_SERVERS);
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, BusinessKafkaConfig.SECURITY_PROTOCOL);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        properties.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10_000);
        properties.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        return properties;
    }

    /**
     * 从浏览器上行 JSON 提取链路字段。旧格式缺少字段时仍可消费，并生成新的 LogID。
     */
    private LogContext createLogContext(ConsumerRecord<String, String> record) {
        LogContext context = LogContext.create()
                .traceId(LogIdGenerator.next())
                .put("messageKey", record.key());
        try {
            JsonObject message = JsonParser.parseString(record.value()).getAsJsonObject();
            String logId = optionalString(message, "logId");
            if (logId != null) {
                context.traceId(logId);
            }
            String messageId = optionalString(message, "messageId");
            if (messageId != null) {
                context.msgId(messageId);
            }
            Long userId = optionalLong(message, "userId");
            if (userId != null) {
                context.uid(userId);
            }
            Long roomId = optionalLong(message, "roomId");
            if (roomId != null) {
                context.roomId(roomId);
            }
        } catch (Exception e) {
            context.put("payloadFormat", "UNRECOGNIZED");
        }
        return context;
    }

    private String optionalString(JsonObject message, String fieldName) {
        JsonElement value = message.get(fieldName);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private Long optionalLong(JsonObject message, String fieldName) {
        JsonElement value = message.get(fieldName);
        return value == null || value.isJsonNull() ? null : value.getAsLong();
    }
}
