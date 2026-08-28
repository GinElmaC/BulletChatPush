package com.GinElmaC.log;

public class LogFactory {
    public static Log getLog(Class<?> clazz) {
        return new Log(clazz.getName());
    }
}
