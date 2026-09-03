package com.GinElmaC.log;

import com.GinElmaC.utils.JsonUtil;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

public class Log {
    private static final DateTimeFormatter CONSOLE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final String loggerName;

    Log(String loggerName) {
        this.loggerName = loggerName;
    }

    public void Info(String message, Object... args) {
        write(LogLevel.INFO, null, message, args);
    }

    public void Warn(String message, Object... args) {
        write(LogLevel.WARN, null, message, args);
    }

    public void Error(String message, Object... args) {
        write(LogLevel.ERROR, null, message, args);
    }

    public void Info(LogContext context, String message, Object... args) {
        write(LogLevel.INFO, context, message, args);
    }

    public void Warn(LogContext context, String message, Object... args) {
        write(LogLevel.WARN, context, message, args);
    }

    public void Error(LogContext context, String message, Object... args) {
        write(LogLevel.ERROR, context, message, args);
    }

    public void info(String message, Object... args) {
        Info(message, args);
    }

    public void warn(String message, Object... args) {
        Warn(message, args);
    }

    public void error(String message, Object... args) {
        Error(message, args);
    }

    private void write(LogLevel level, LogContext context, String message, Object... args) {
        LocalDateTime logTime = LocalDateTime.now();
        Throwable throwable = extractThrowable(args);
        Object[] formatArgs = throwable == null ? args : Arrays.copyOf(args, args.length - 1);
        String formatMessage = format(message, formatArgs);
        String stackTrace = throwable == null ? null : getStackTrace(throwable);
        SourceLocation sourceLocation = resolveSourceLocation();
        // 未显式传入 LogContext 的日志统一视为系统日志，复用当前进程的系统级 LogID。
        LogContext effectiveContext = context == null ? LogRuntime.systemLogContext() : context;
        String contextJson = effectiveContext.getExtra().isEmpty() ? null : JsonUtil.toJson(effectiveContext.getExtra());

        writeConsole(logTime, level, formatMessage, stackTrace);
        if (LogMysqlConfig.enabled()) {
            PushLogWriter.getInstance().append(new PushLogEvent(
                    logTime,
                    level,
                    LogRuntime.getServerName(),
                    LogRuntime.getMachineId(),
                    LogRuntime.getNodeName(),
                    LogRuntime.getHostIp(),
                    loggerName,
                    Thread.currentThread().getName(),
                    sourceLocation.filePath(),
                    sourceLocation.lineNumber(),
                    effectiveContext.getTraceId(),
                    effectiveContext.getMsgId(),
                    effectiveContext.getUid(),
                    effectiveContext.getRoomId(),
                    formatMessage,
                    stackTrace,
                    contextJson
            ));
        }
    }

    /**
     * 从当前线程栈中找到第一个非日志组件自身的调用点。
     * 这样业务侧无需手动传文件名和行号，所有 log.Info/Warn/Error 都能自动落调用位置。
     */
    private SourceLocation resolveSourceLocation() {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTraceElements) {
            if (element == null || isLogInternalClass(element.getClassName())) {
                continue;
            }
            int lineNumber = element.getLineNumber();
            return new SourceLocation(toSourcePath(element), lineNumber > 0 ? lineNumber : null);
        }
        return new SourceLocation(null, null);
    }

    private boolean isLogInternalClass(String className) {
        return Thread.class.getName().equals(className)
                || Log.class.getName().equals(className)
                || LogFactory.class.getName().equals(className)
                || LogRuntime.class.getName().equals(className)
                || LogContext.class.getName().equals(className);
    }

    private String toSourcePath(StackTraceElement element) {
        String className = element.getClassName();
        int nestedClassIndex = className.indexOf('$');
        if (nestedClassIndex > 0) {
            className = className.substring(0, nestedClassIndex);
        }
        return className.replace('.', '/') + ".java";
    }

    private Throwable extractThrowable(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object last = args[args.length - 1];
        if (last instanceof Throwable) {
            return (Throwable) last;
        }
        return null;
    }

    private String format(String template, Object[] args) {
        if (template == null) {
            return "null";
        }
        if (args == null || args.length == 0) {
            return template;
        }

        StringBuilder builder = new StringBuilder();
        int start = 0;
        int argIndex = 0;
        while (argIndex < args.length) {
            int placeholder = template.indexOf("{}", start);
            if (placeholder < 0) {
                break;
            }
            builder.append(template, start, placeholder);
            builder.append(String.valueOf(args[argIndex++]));
            start = placeholder + 2;
        }
        builder.append(template.substring(start));
        while (argIndex < args.length) {
            builder.append(' ').append(String.valueOf(args[argIndex++]));
        }
        return builder.toString();
    }

    private void writeConsole(LocalDateTime logTime, LogLevel level, String message, String stackTrace) {
        String line = String.format("%s [%s] [%s] %s - %s",
                logTime.format(CONSOLE_TIME_FORMAT),
                level.getName(),
                Thread.currentThread().getName(),
                loggerName,
                message);
        if (level == LogLevel.ERROR) {
            System.err.println(line);
            if (stackTrace != null) {
                System.err.println(stackTrace);
            }
            return;
        }
        System.out.println(line);
    }

    private String getStackTrace(Throwable throwable) {
        StringWriter stringWriter = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    private record SourceLocation(String filePath, Integer lineNumber) {
    }
}
