package com.GinElmaC.NettyServer.Agent;

import com.GinElmaC.log.Log;
import com.GinElmaC.log.LogContext;
import com.GinElmaC.log.LogFactory;
import com.GinElmaC.utils.JsonUtil;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 读取项目内指定源码片段的只读工具。
 * 该工具主要给日志排障场景使用，模型可直接复用日志里的 sourceFilePath/sourceLine 查看对应实现。
 */
public class QueryProjectCodeTool implements AgentTool {
    public static final String NAME = "query_project_code";
    private static final int DEFAULT_CONTEXT_LINES = 20;
    private static final int DEFAULT_HEAD_LINES = 120;
    private static final int MAX_CONTEXT_LINES = 80;
    private static final int MAX_SNIPPET_LINES = 200;
    private static final int MAX_FILE_SIZE_BYTES = 512 * 1024;
    private static final int MAX_CANDIDATE_COUNT = 10;
    private static final Log log = LogFactory.getLog(QueryProjectCodeTool.class);
    // 只允许读取仓库内安全的源码目录，避免模型误读本地配置、密钥或产物文件。
    private static final List<String> ALLOWED_ROOTS = List.of(
            "BulletPushCommon/src/main/java",
            "BulletPushCore/src/main/java",
            "BulletPushService/src/main/java",
            "BulletPushStarter/src/main/java",
            "BulletPushAdmin/src",
            "BulletPushUserClient/src",
            "BulletPushCommon/src/main/resources/sql",
            "BulletPushCore/src/main/resources/sql",
            "BulletPushService/src/main/resources/sql",
            "BulletPushStarter/src/main/resources/sql"
    );
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".java",
            ".vue",
            ".js",
            ".ts",
            ".tsx",
            ".jsx",
            ".css",
            ".sql",
            ".xml"
    );

    @Override
    public AgentToolDefinition definition() {
        return new AgentToolDefinition(
                NAME,
                "根据项目内源码路径和行号读取具体代码片段。优先使用日志中的 sourceFilePath/sourceLine。",
                """
                        {
                          "type":"object",
                          "properties":{
                            "filePath":{"type":"string","description":"需要查看的源码路径。优先传日志中的 sourceFilePath，或仓库相对路径。"},
                            "line":{"type":"integer","minimum":1,"description":"需要聚焦的源码行号。优先传日志中的 sourceLine。"},
                            "startLine":{"type":"integer","minimum":1,"description":"可选起始行号，传入后优先生效。"},
                            "endLine":{"type":"integer","minimum":1,"description":"可选结束行号，传入后优先生效。"},
                            "contextLines":{"type":"integer","minimum":0,"maximum":80,"description":"基于 line 自动扩展的上下文行数，默认 20。"}
                          },
                          "required":["filePath"]
                        }
                        """
        );
    }

    @Override
    public String execute(JsonObject arguments, AgentToolRequest request) {
        CodeQueryParam param = readParam(arguments);
        AgentTraceContext traceContext = request == null ? null : request.traceContext();
        long startedAtMillis = System.currentTimeMillis();
        logQueryStarted(traceContext, param);
        try {
            String normalizedPath = normalizeRequestedPath(param.filePath());
            if (normalizedPath == null) {
                String result = JsonUtil.toJson(CodeQueryResult.invalidRequest(null, "filePath 不能为空。"));
                logQueryCompleted(traceContext, "invalid_request", 0, result.length(), startedAtMillis);
                return result;
            }
            if (containsParentSegment(normalizedPath)) {
                String result = JsonUtil.toJson(CodeQueryResult.rejected(param.filePath(), null,
                        "filePath 非法，禁止使用 .. 访问项目外路径。"));
                logQueryCompleted(traceContext, "rejected", 0, result.length(), startedAtMillis);
                return result;
            }
            if (hasExplicitExtension(normalizedPath) && !isAllowedExtension(normalizedPath)) {
                String result = JsonUtil.toJson(CodeQueryResult.rejected(param.filePath(), null,
                        "当前工具只允许读取白名单源码类型。"));
                logQueryCompleted(traceContext, "rejected", 0, result.length(), startedAtMillis);
                return result;
            }

            Path projectRoot = resolveProjectRoot();
            List<Path> allowedRoots = resolveAllowedRoots(projectRoot);
            List<Path> matches = resolveMatches(projectRoot, allowedRoots, normalizedPath);
            String result;
            String status;
            if (matches.isEmpty()) {
                status = "not_found";
                result = JsonUtil.toJson(CodeQueryResult.notFound(param.filePath()));
            } else if (matches.size() > 1) {
                status = "ambiguous";
                result = JsonUtil.toJson(CodeQueryResult.ambiguous(
                        param.filePath(),
                        matches.stream().map(path -> toRelativePath(projectRoot, path)).toList()
                ));
            } else {
                status = "ok";
                result = JsonUtil.toJson(buildSuccessResult(projectRoot, matches.get(0), param));
            }
            logQueryCompleted(traceContext, status, matches.size(), result.length(), startedAtMillis);
            return result;
        } catch (Exception e) {
            logQueryFailed(traceContext, startedAtMillis, e);
            throw new IllegalStateException("query project code failed", e);
        }
    }

    private CodeQueryResult buildSuccessResult(Path projectRoot, Path resolvedPath, CodeQueryParam param) throws java.io.IOException {
        if (Files.size(resolvedPath) > MAX_FILE_SIZE_BYTES) {
            return CodeQueryResult.rejected(param.filePath(), toRelativePath(projectRoot, resolvedPath),
                    "源码文件过大，当前工具仅返回 512KB 以内的文本文件。");
        }
        List<String> lines = Files.readAllLines(resolvedPath, StandardCharsets.UTF_8);
        LineWindow window = resolveWindow(lines.size(), param);
        return CodeQueryResult.ok(
                param.filePath(),
                toRelativePath(projectRoot, resolvedPath),
                window.focusLine(),
                window.startLine(),
                window.endLine(),
                lines.size(),
                renderCode(lines, window.startLine(), window.endLine())
        );
    }

    private Path resolveProjectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = projectRootCandidate(current);
            if (candidate != null) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot resolve BulletChatPush project root");
    }

    private Path projectRootCandidate(Path current) {
        if (isProjectRoot(current)) {
            return current;
        }
        Path directChild = current.resolve("BulletChatPush").normalize();
        if (isProjectRoot(directChild)) {
            return directChild;
        }
        Path nestedChild = current.resolve("push/BulletChatPush").normalize();
        if (isProjectRoot(nestedChild)) {
            return nestedChild;
        }
        return null;
    }

    private boolean isProjectRoot(Path path) {
        return Files.isRegularFile(path.resolve("pom.xml"))
                && Files.isDirectory(path.resolve("BulletPushCommon"))
                && Files.isDirectory(path.resolve("BulletPushCore"))
                && Files.isDirectory(path.resolve("BulletPushStarter"));
    }

    private List<Path> resolveAllowedRoots(Path projectRoot) {
        List<Path> roots = new ArrayList<>();
        for (String root : ALLOWED_ROOTS) {
            Path resolved = projectRoot.resolve(root).normalize();
            if (Files.isDirectory(resolved)) {
                roots.add(resolved);
            }
        }
        return roots;
    }

    private List<Path> resolveMatches(Path projectRoot, List<Path> allowedRoots, String normalizedPath) throws java.io.IOException {
        LinkedHashSet<Path> matches = new LinkedHashSet<>();
        // 先尝试绝对路径和仓库相对路径，避免明明给了完整路径还退化为全量搜索。
        addIfReadable(matches, projectRoot, allowedRoots, parsePath(normalizedPath));
        addIfReadable(matches, projectRoot, allowedRoots, projectRoot.resolve(normalizedPath).normalize());
        for (Path allowedRoot : allowedRoots) {
            addIfReadable(matches, projectRoot, allowedRoots, allowedRoot.resolve(normalizedPath).normalize());
        }
        if (!matches.isEmpty()) {
            return List.copyOf(matches);
        }
        return findBySuffix(projectRoot, allowedRoots, normalizedPath);
    }

    private void addIfReadable(
            LinkedHashSet<Path> matches,
            Path projectRoot,
            List<Path> allowedRoots,
            Path candidate
    ) {
        if (candidate == null) {
            return;
        }
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalizedCandidate) || !isAllowedPath(projectRoot, allowedRoots, normalizedCandidate)) {
            return;
        }
        matches.add(normalizedCandidate);
    }

    private List<Path> findBySuffix(Path projectRoot, List<Path> allowedRoots, String normalizedPath) throws java.io.IOException {
        String suffix = normalizedPath.startsWith("/") ? normalizedPath.substring(1) : normalizedPath;
        LinkedHashSet<Path> matches = new LinkedHashSet<>();
        for (Path allowedRoot : allowedRoots) {
            try (Stream<Path> stream = Files.walk(allowedRoot, 20)) {
                List<Path> rootMatches = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> isAllowedPath(projectRoot, allowedRoots, path))
                        .filter(path -> matchesSuffix(projectRoot, allowedRoot, path, suffix))
                        .limit(MAX_CANDIDATE_COUNT)
                        .toList();
                matches.addAll(rootMatches);
            }
            if (matches.size() >= MAX_CANDIDATE_COUNT) {
                break;
            }
        }
        return matches.stream().limit(MAX_CANDIDATE_COUNT).toList();
    }

    private boolean matchesSuffix(Path projectRoot, Path allowedRoot, Path candidate, String suffix) {
        String fileName = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
        if (!suffix.contains("/") && fileName.equals(suffix)) {
            return true;
        }
        String projectRelative = toRelativePath(projectRoot, candidate);
        if (projectRelative.endsWith(suffix)) {
            return true;
        }
        String rootRelative = toUnixPath(allowedRoot.relativize(candidate));
        return rootRelative.endsWith(suffix);
    }

    private boolean isAllowedPath(Path projectRoot, List<Path> allowedRoots, Path candidate) {
        if (!candidate.startsWith(projectRoot) || !isAllowedExtension(candidate.toString())) {
            return false;
        }
        for (Path allowedRoot : allowedRoots) {
            if (candidate.startsWith(allowedRoot)) {
                return true;
            }
        }
        return false;
    }

    private LineWindow resolveWindow(int totalLines, CodeQueryParam param) {
        if (totalLines <= 0) {
            return new LineWindow(1, 0, null);
        }
        Integer focusLine = normalizePositive(param.line());
        Integer startLine = normalizePositive(param.startLine());
        Integer endLine = normalizePositive(param.endLine());
        int contextLines = clampContextLines(param.contextLines());
        if (startLine != null || endLine != null) {
            int safeStartLine = clampLine(startLine != null ? startLine : (focusLine == null ? 1 : focusLine), totalLines);
            int safeEndLine = clampLine(endLine != null ? endLine : (focusLine == null ? safeStartLine : focusLine), totalLines);
            if (safeEndLine < safeStartLine) {
                safeEndLine = safeStartLine;
            }
            if (safeEndLine - safeStartLine + 1 > MAX_SNIPPET_LINES) {
                safeEndLine = Math.min(totalLines, safeStartLine + MAX_SNIPPET_LINES - 1);
            }
            Integer safeFocusLine = focusLine == null ? null : clampLine(focusLine, totalLines);
            return new LineWindow(safeStartLine, safeEndLine, safeFocusLine);
        }
        if (focusLine != null) {
            int safeFocusLine = clampLine(focusLine, totalLines);
            int safeStartLine = Math.max(1, safeFocusLine - contextLines);
            int safeEndLine = Math.min(totalLines, safeFocusLine + contextLines);
            return new LineWindow(safeStartLine, safeEndLine, safeFocusLine);
        }
        return new LineWindow(1, Math.min(totalLines, DEFAULT_HEAD_LINES), null);
    }

    private String renderCode(List<String> lines, int startLine, int endLine) {
        StringBuilder builder = new StringBuilder();
        for (int lineNo = startLine; lineNo <= endLine; lineNo++) {
            builder.append(lineNo)
                    .append(" | ")
                    .append(lines.get(lineNo - 1))
                    .append('\n');
        }
        return builder.toString();
    }

    private String normalizeRequestedPath(String filePath) {
        if (filePath == null) {
            return null;
        }
        String normalized = filePath.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.isBlank() ? null : normalized;
    }

    private boolean containsParentSegment(String normalizedPath) {
        for (String segment : normalizedPath.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExplicitExtension(String normalizedPath) {
        int slashIndex = normalizedPath.lastIndexOf('/');
        int dotIndex = normalizedPath.lastIndexOf('.');
        return dotIndex > slashIndex;
    }

    private boolean isAllowedExtension(String path) {
        String lowerCasePath = path.toLowerCase(Locale.ROOT);
        for (String extension : ALLOWED_EXTENSIONS) {
            if (lowerCasePath.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private Path parsePath(String value) {
        if (value == null || !value.startsWith("/")) {
            return null;
        }
        try {
            return Path.of(value).normalize();
        } catch (Exception e) {
            return null;
        }
    }

    private String toRelativePath(Path projectRoot, Path path) {
        return toUnixPath(projectRoot.relativize(path.toAbsolutePath().normalize()));
    }

    private String toUnixPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private int clampContextLines(Integer contextLines) {
        if (contextLines == null) {
            return DEFAULT_CONTEXT_LINES;
        }
        return Math.max(0, Math.min(contextLines, MAX_CONTEXT_LINES));
    }

    private Integer normalizePositive(Integer value) {
        return value == null || value <= 0 ? null : value;
    }

    private int clampLine(int line, int totalLines) {
        return Math.max(1, Math.min(line, totalLines));
    }

    private CodeQueryParam readParam(JsonObject arguments) {
        return new CodeQueryParam(
                readText(arguments, "filePath"),
                readInt(arguments, "line"),
                readInt(arguments, "startLine"),
                readInt(arguments, "endLine"),
                readInt(arguments, "contextLines")
        );
    }

    private String readText(JsonObject arguments, String key) {
        if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
            return null;
        }
        String value = arguments.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Integer readInt(JsonObject arguments, String key) {
        if (arguments == null || !arguments.has(key) || arguments.get(key).isJsonNull()) {
            return null;
        }
        try {
            return arguments.get(key).getAsInt();
        } catch (Exception e) {
            return null;
        }
    }

    private void logQueryStarted(AgentTraceContext traceContext, CodeQueryParam param) {
        if (traceContext == null) {
            return;
        }
        LogContext context = traceContext.logContext()
                .put("toolName", NAME)
                .put("queryFilePath", param.filePath())
                .put("queryLine", param.line())
                .put("queryStartLine", param.startLine())
                .put("queryEndLine", param.endLine())
                .put("queryContextLines", param.contextLines());
        log.Info(context, "AGENT_TOOL_CODE_QUERY_STARTED");
    }

    private void logQueryCompleted(
            AgentTraceContext traceContext,
            String status,
            int candidateCount,
            int resultLength,
            long startedAtMillis
    ) {
        if (traceContext == null) {
            return;
        }
        log.Info(traceContext.logContext()
                        .put("toolName", NAME)
                        .put("queryStatus", status)
                        .put("candidateCount", candidateCount)
                        .put("resultLength", resultLength)
                        .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis),
                "AGENT_TOOL_CODE_QUERY_COMPLETED");
    }

    private void logQueryFailed(AgentTraceContext traceContext, long startedAtMillis, Exception error) {
        if (traceContext == null) {
            return;
        }
        log.Error(traceContext.logContext()
                        .put("toolName", NAME)
                        .put("toolDurationMs", System.currentTimeMillis() - startedAtMillis)
                        .put("errorType", error.getClass().getSimpleName()),
                "AGENT_TOOL_CODE_QUERY_FAILED",
                error);
    }

    private record CodeQueryParam(
            String filePath,
            Integer line,
            Integer startLine,
            Integer endLine,
            Integer contextLines
    ) {
    }

    private record LineWindow(int startLine, int endLine, Integer focusLine) {
    }

    private record CodeQueryResult(
            String status,
            String requestedPath,
            String resolvedPath,
            Integer focusLine,
            Integer snippetStartLine,
            Integer snippetEndLine,
            Integer totalLines,
            String code,
            List<String> candidates,
            String message
    ) {
        private static CodeQueryResult ok(
                String requestedPath,
                String resolvedPath,
                Integer focusLine,
                Integer snippetStartLine,
                Integer snippetEndLine,
                Integer totalLines,
                String code
        ) {
            return new CodeQueryResult(
                    "ok",
                    requestedPath,
                    resolvedPath,
                    focusLine,
                    snippetStartLine,
                    snippetEndLine,
                    totalLines,
                    code,
                    List.of(),
                    null
            );
        }

        private static CodeQueryResult notFound(String requestedPath) {
            return new CodeQueryResult(
                    "not_found",
                    requestedPath,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    "未找到匹配的源码文件，请优先传入日志里的 sourceFilePath 或仓库相对路径。"
            );
        }

        private static CodeQueryResult ambiguous(String requestedPath, List<String> candidates) {
            return new CodeQueryResult(
                    "ambiguous",
                    requestedPath,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    candidates,
                    "匹配到多个源码文件，请改用 candidates 中的 resolvedPath 重试。"
            );
        }

        private static CodeQueryResult rejected(String requestedPath, String resolvedPath, String message) {
            return new CodeQueryResult(
                    "rejected",
                    requestedPath,
                    resolvedPath,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    message
            );
        }

        private static CodeQueryResult invalidRequest(String requestedPath, String message) {
            return new CodeQueryResult(
                    "invalid_request",
                    requestedPath,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    message
            );
        }
    }
}
