package com.GinElmaC.constant;

/**
 * 自定义Proto的相关参数
 */
public interface ProtoConstant {
    //最基础的包大小，即包边界：magic(2)+version(2)+packet_handerLength(4)+packet_dataLength
    int BASE_PACKET_SIZE = 12;

    short MAGIC = 0xABC;

    short VERSION = 1;

    String DEFAULT_SECRETKEY = "GINELMACSECRETKY";
}
