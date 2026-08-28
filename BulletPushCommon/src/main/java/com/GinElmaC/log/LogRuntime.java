package com.GinElmaC.log;

import com.GinElmaC.constant.LinkConfigConstant;

import java.net.InetAddress;

public class LogRuntime {
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

    private static String initHostIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }
}
