package com.GinElmaC.domain.model;

import com.GinElmaC.domain.protobuf.PacketHeader;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
public class CompleteMessage {
    PacketHeader header;
    MessageBody body;

    public CompleteMessage(PacketHeader packetHeader, MessageBody messageBody) {
        this.header = packetHeader;
        this.body = messageBody;
    }
}
