package com.GinElmaC.domain.model;

import com.GinElmaC.domain.enums.MessageType;
import com.GinElmaC.domain.protobuf.PacketHeader;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogIdGenerator;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CompleteMessage {
    private PacketHeader header;
    private MessageBody body;
    // 同一条消息在节点接收、服务处理、编码发送阶段复用的唯一日志链路 ID。
    private String logId;

    public CompleteMessage(PacketHeader packetHeader, MessageBody messageBody) {
        this(packetHeader, messageBody, messageBody == null ? null : messageBody.getLogId());
    }

    public CompleteMessage(PacketHeader packetHeader, MessageBody messageBody, String logId) {
        this.header = packetHeader;
        this.body = messageBody;
        this.logId = logId;
    }

    public PacketHeader getPacketHeader(){
        return this.header;
    }

    public MessageBody getMessageBody(){
        return this.body;
    }

    /**
     * 为当前消息构造结构化日志上下文。
     * 群聊消息的 toId 视为 roomId；其他类型只作为 targetId 写入扩展字段，避免错误标记为房间。
     */
    public LogContext createLogContext() {
        String currentLogId = getOrCreateLogId();
        LogContext context = LogContext.create().traceId(currentLogId);
        if (header != null) {
            context.put("messageType", header.getMessageType())
                    .put("appId", header.getAppId())
                    .put("headerUid", header.getUid());
        }
        if (body == null) {
            return context;
        }
        context.uid(body.getFromUserId())
                .put("toId", body.getToId())
                .put("bodyMessageType", body.getMessageType());
        MessageType messageType = header == null
                ? null
                : MessageType.fromType((short) header.getMessageType());
        if (messageType == MessageType.GROUP_CHAT_MESSAGE) {
            context.roomId(body.getToId());
        }
        return context;
    }

    /**
     * Builder 或外部构造消息未传入 LogID 时，在首次记录日志前补齐。
     */
    public String getOrCreateLogId() {
        if (logId == null || logId.isBlank()) {
            logId = body == null || body.getLogId() == null || body.getLogId().isBlank()
                    ? LogIdGenerator.next()
                    : body.getLogId();
        }
        if (body != null && (body.getLogId() == null || body.getLogId().isBlank())) {
            // 确保同一对象再次编码或跨节点转发时可以继续透传相同 LogID。
            body.setLogId(logId);
        }
        return logId;
    }
}
