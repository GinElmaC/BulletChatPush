package com.GinElmaC.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 消息体java对象
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageBody {
    //发送方uid
    long fromUserId;
    //时间戳
    long timeStamp;
    //接收方uid
    long toId;
    //消息类型
    short messageType;
    //内容
    String content;
    // 跨节点透传的唯一日志链路 ID，旧消息缺失该字段时由接收节点生成。
    String logId;
}
