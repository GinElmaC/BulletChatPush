package com.GinElmaC.log;

import com.GinElmaC.constant.LinkConfigConstant;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogRuntime {
    private static final DateTimeFormatter SYSTEM_TRACE_ID_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    // 进程启动时生成一次系统链路 LogID，供未显式传入 LogContext 的系统日志复用。
    private static final String SYSTEM_TRACE_ID = "System_" + LocalDateTime.now().format(SYSTEM_TRACE_ID_FORMATTER);
    private static volatile Integer machineId;
    private static volatile String nodeName = System.getenv("PUSH_NODE_NAME");
    private static volatile String hostIp = initHostIp();

    public static String getServerName() {
        return LinkConfigConstant.DEFAULT_SERVERNAME;
    }

    public static Integer getMachineId() {
        return machineId;
    }

    public static void setMachineId(Integer machineId) {
        LogRuntime.machineId = machineId;
    }

    public static String getNodeName() {
        return nodeName;
    }

    public static void setNodeName(String nodeName) {
        LogRuntime.nodeName = nodeName;
    }

    public static String getHostIp() {
        return hostIp;
    }

    public static void setHostIp(String hostIp) {
        LogRuntime.hostIp = hostIp;
    }

    public static String getSystemTraceId() {
        return SYSTEM_TRACE_ID;
    }

    public static LogContext systemLogContext() {
        return LogContext.create().traceId(SYSTEM_TRACE_ID);
    }

    private static String initHostIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
