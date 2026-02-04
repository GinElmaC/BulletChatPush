package com.GinElmaC.NettyServer.Service;

import com.GinElmaC.domain.model.CompleteMessage;

/**
 * 接收到消息的处理类
 * @param <T>
 */
public abstract class AbstractMessageService<T> {
     public abstract void doMessage(T t);
}
