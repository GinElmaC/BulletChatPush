package com.GinElmaC;

import com.GinElmaC.service.KafkaBusinessMessageService;

/**
 * 业务 Service 启动入口。
 * 该进程与推送节点独立部署，专门负责消费 message_in 并将处理结果写入 message_out。
 */
public class PushServiceStart {
    public static void main(String[] args) {
        KafkaBusinessMessageService messageService = new KafkaBusinessMessageService();
        Runtime.getRuntime().addShutdownHook(new Thread(
                messageService::stop,
                "push-business-service-shutdown"
        ));
        messageService.start();
    }
}
