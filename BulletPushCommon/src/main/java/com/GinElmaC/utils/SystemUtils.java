package com.GinElmaC.utils;

import io.netty.channel.epoll.Epoll;
import io.netty.incubator.channel.uring.IOUring;

/**
 * 操作系统级别工具类
 */
public class SystemUtils {
    //获取操作系统的名字
    public static final String OS_NAME = System.getProperty("os.name");

    private static boolean isLinux = false;
    private static boolean isWindows = false;

    //0-NIO 1-Epoll 2-IO_Uring
    public static int mode = 0;

    static{
        if(OS_NAME != null && OS_NAME.toLowerCase().contains("linux")){
            isLinux = true;
        }else if(OS_NAME != null && OS_NAME.toLowerCase().contains("windows")){
            isWindows = true;
        }
    }

    /**
     * 判断支持的channel类型
     */
    public static int ChargeMode(){
        if(isLinux && Epoll.isAvailable()){
            mode = 1;
        }else if (isLinux && IOUring.isAvailable()){
            mode = 2;
        }
        return mode;
    }

    public static String getOsName(){
        return isLinux?"linux":"windows";
    }
}
