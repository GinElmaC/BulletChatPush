package com.GinElmaC.NettyServer.Config;

import com.GinElmaC.constant.LinkConfigConstant;
import lombok.Data;

/**
 * 链接协议
 */
@Data
public class LinkConfig {
    /**
     * 服务名
     */
    public static String SERVERNAME = LinkConfigConstant.DEFAULT_SERVERNAME;
    /**
     * 机器id
     */
    public static Integer MACHINE_ID;
}
