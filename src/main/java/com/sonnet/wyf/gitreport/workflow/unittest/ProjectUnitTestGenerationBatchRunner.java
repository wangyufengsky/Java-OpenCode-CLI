package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import com.sonnet.wyf.gitreport.agentbridge.ValidationCheck;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactContext;
import com.sonnet.wyf.gitreport.failure.GlobalWorkflowExceptionHandler;
import com.sonnet.wyf.gitreport.failure.WorkflowFailureCategory;
import com.sonnet.wyf.gitreport.failure.WorkflowFailureException;
import com.sonnet.wyf.gitreport.failure.WorkflowFailureScope;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

public class ProjectUnitTestGenerationBatchRunner {
    public static final String RESULTS_JSON = "agentbridge-results.json";
    private static final int MCP_READ_RETRY_ATTEMPTS = 3;
    private static final Duration MCP_READ_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Pattern IDEA_TEST_SUCCESS = Pattern.compile("(?im)^=== Summary:\\s*[1-9]\\d* passed,\\s*0 failed");
    private static final Pattern IDEA_TEST_ZERO_EXIT = Pattern.compile(
            "(?im)(?:Process finished with exit code\\s+0|进程已结束，退出代码为\\s*0)(?!\\d)"
    );

    private final AgentBridgeClient client;
    private final ProjectUnitTestGenerationPromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final Duration pollInterval;
    private final GlobalWorkflowExceptionHandler failureHandler = new GlobalWorkflowExceptionHandler();

    public ProjectUnitTestGenerationBatchRunner(
            AgentBridgeClient client,
            ProjectUnitTestGenerationPromptBuilder promptBuilder,
            ObjectMapper objectMapper
    ) {
        this(client, promptBuilder, objectMapper, Duration.ofSeconds(1));
    }

    ProjectUnitTestGenerationBatchRunner(
            AgentBridgeClient client,
            ProjectUnitTestGenerationPromptBuilder promptBuilder,
            ObjectMapper objectMapper,
            Duration pollInterval
    ) {
        this.client = client;
        this.promptBuilder = promptBuilder;
        this.objectMapper = objectMapper;
        this.pollInterval = pollInterval;
    }

    public List<BatchResult> runBatches(ProjectUnitTestGenerationProperties properties, Path out, List<Map<String, Object>> batches) throws Exception {
        Map<String, BatchResult> allResults = readExistingResults(out);
        List<BatchResult> selectedResults = new ArrayList<>();
        for (Map<String, Object> batch : batches) {
            BatchResult result = runBatch(properties, out, batch);
            selectedResults.add(result);
            allResults.put(result.batchId(), result);
            writeResults(out, new ArrayList<>(allResults.values()));
            if (!result.accepted()) {
                break;
            }
        }
        return selectedResults;
    }

    public List<BatchResult> verifyBatches(ProjectUnitTestGenerationProperties properties, Path out, List<Map<String, Object>> batches) throws Exception {
        Map<String, BatchResult> allResults = readExistingResults(out);
        List<BatchResult> selectedResults = new ArrayList<>();
        for (Map<String, Object> batch : batches) {
            String batchId = string(batch.get("batch_id"));
            Acceptance acceptance = validate(properties, batch);
            BatchResult existing = allResults.get(batchId);
            BatchResult result = new BatchResult(
                    batchId,
                    acceptance.accepted(),
                    acceptance.summary(),
                    List.of(new AttemptRecord(0, "verification", acceptance.accepted(), acceptance.summary())),
                    existing == null ? List.of() : existing.issues()
            );
            selectedResults.add(result);
            allResults.put(result.batchId(), result);
            writeResults(out, new ArrayList<>(allResults.values()));
        }
        return selectedResults;
    }

    BatchResult runBatch(ProjectUnitTestGenerationProperties properties, Path out, Map<String, Object> batch) throws Exception {
        String batchId = string(batch.get("batch_id"));
        List<AttemptRecord> attempts = new ArrayList<>();
        List<String> issues = new ArrayList<>();

        Acceptance precheck = validate(properties, batch);
        attempts.add(new AttemptRecord(0, "precheck", precheck.accepted(), precheck.summary()));
        if (precheck.accepted()) {
            return new BatchResult(batchId, true, precheck.summary(), attempts, issues);
        }

        String failureSummary = precheck.summary();
        int maxAttempts = Math.max(1, properties.getAgentbridge().getMaxAttempts());
        URI webBaseUrl = URI.create(properties.getAgentbridge().getWebBaseUrl());
        Duration timeout = Duration.ofMinutes(Math.max(1, properties.getAgentbridge().getTimeoutMinutes()));
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Path runDir = WorkflowArtifactContext
                    .nextTaskAttempt("test-batch:" + batchId, out.resolve("test-batches").resolve(batchId))
                    .root();
            ProtectedSnapshot protectedSnapshot = null;
            try {
                protectedSnapshot = ProtectedSnapshot.capture(
                        properties.getProject().getRepo(),
                        out,
                        runDir.resolve("protected-file-backup"),
                        batch,
                        properties.getTest().getAdditionalBuildArtifactGlobs()
                );
                Path promptFile = runDir.resolve("worker-prompt.md");
                Files.writeString(promptFile, promptBuilder.buildBatchPrompt(
                        properties.getProject().getRepo(),
                        Path.of(string(batch.get("input_json"))),
                        failureSummary
                ));
                client.clearSession(webBaseUrl);
                client.postPrompt(webBaseUrl, Files.readString(promptFile));
                client.waitUntilIdle(webBaseUrl, timeout, pollInterval);

                ValidationCheck protectedFiles = protectedSnapshot.validateAndRestore();
                if (!protectedFiles.ok()) {
                    if (protectedFiles.error().startsWith("protected file restoration failed:")) {
                        throw WorkflowFailureException.task(
                                WorkflowFailureCategory.TASK_CONFIGURATION,
                                protectedFiles.error()
                        );
                    }
                    WorkflowFailureException failure = WorkflowFailureException.session(
                            WorkflowFailureCategory.FILE_INTEGRITY_VIOLATION,
                            protectedFiles.error()
                    );
                    if (!issues.contains(failure.getMessage())) {
                        issues.add(failure.getMessage());
                    }
                    attempts.add(new AttemptRecord(attempt, "session-failed", false, failure.getMessage()));
                    failureSummary = failure.getMessage();
                    continue;
                }

                Acceptance postcheck = validate(properties, batch);
                attempts.add(new AttemptRecord(attempt, "postcheck", postcheck.accepted(), postcheck.summary()));
                if (postcheck.accepted()) {
                    return new BatchResult(batchId, true, postcheck.summary(), attempts, issues);
                }
                failureSummary = postcheck.summary();
            } catch (Exception exception) {
                if (protectedSnapshot != null) {
                    ValidationCheck restored = protectedSnapshot.validateAndRestore();
                    if (!restored.ok() && !issues.contains(restored.error())) {
                        issues.add(restored.error());
                    }
                }
                WorkflowFailureException failure = failureHandler.sessionFailure(
                        WorkflowFailureCategory.SESSION_EXECUTION,
                        exception
                );
                if (failure.scope() == WorkflowFailureScope.TASK) {
                    throw failure;
                }
                attempts.add(new AttemptRecord(attempt, "session-failed", false, failure.getMessage()));
                failureSummary = failure.getMessage();
            }
        }
        return new BatchResult(
                batchId,
                false,
                "exceeded agentbridge.max-attempts: " + failureSummary,
                attempts,
                issues
        );
    }

    private Acceptance validate(ProjectUnitTestGenerationProperties properties, Map<String, Object> batch) throws Exception {
        URI mcpUrl = URI.create(properties.getAgentbridge().getMcpUrl());
        String targetTestFile = first(batch.get("target_test_files"));
        String testClass = testClassFqn(targetTestFile);
        String sourceClass = sourceClass(batch);
        String module = moduleName(targetTestFile);
        boolean requireCoverage = requireCoverage(batch, properties);
        int threshold = threshold(batch, properties);

        List<String> failures = new ArrayList<>();
        AgentBridgeClient.ToolResponse tests = callReadOnlyToolWithRetry(mcpUrl, "list_tests", objectMapper.createObjectNode()
                .put("file_pattern", Path.of(targetTestFile).getFileName().toString()));
        if (isToolFailure(tests)) {
            failures.add(toolFailureSummary("list_tests", tests));
        } else if (!hasRecognizedTest(tests)) {
            failures.add("测试类不存在或 IDE 未识别: " + targetTestFile);
        }
        AgentBridgeClient.ToolResponse compilation = callReadOnlyToolWithRetry(mcpUrl, "get_compilation_errors", objectMapper.createObjectNode()
                .put("path", targetTestFile));
        if (isToolFailure(compilation)) {
            failures.add(toolFailureSummary("get_compilation_errors", compilation));
        } else if (!hasNoCompilationErrors(compilation)) {
            failures.add("目标测试类存在编译错误: " + targetTestFile);
        }
        Coverage coverage = new Coverage(-1);
        if (failures.isEmpty()) {
            Path coverageReport = coverageReportPath(properties.getProject().getRepo(), module);
            Path coverageExec = coverageExecPath(properties.getProject().getRepo(), module);
            if (requireCoverage) {
                Files.deleteIfExists(coverageReport);
                Files.deleteIfExists(coverageExec);
            }
            if (requireCoverage) {
                AgentBridgeClient.ToolResponse run = client.callTool(
                        mcpUrl,
                        "run_command",
                        runCommandArguments(properties, testClass, module, coverageExec)
                );
                if (!commandSucceeded(run)) {
                    failures.add("目标测试类运行失败: " + testClass + " " + run.text());
                }
            } else {
                AgentBridgeClient.ToolResponse run = client.callTool(
                        mcpUrl,
                        "run_tests",
                        runTestsArguments(properties, testClass, module)
                );
                if (isToolFailure(run)) {
                    failures.add(toolFailureSummary("run_tests", run));
                } else {
                    AgentBridgeClient.ToolResponse output = callReadOnlyToolWithRetry(
                            mcpUrl, "read_run_output", readRunOutputArguments());
                    if (isToolFailure(output)) {
                        failures.add(toolFailureSummary("read_run_output", output));
                    } else if (!ideaTestSucceeded(run, output)) {
                        failures.add("目标测试类运行失败: " + testClass + " " + output.text());
                    }
                }
            }
            if (failures.isEmpty() && requireCoverage) {
                coverage = jacocoCoverage(coverageReport, sourceClass);
                if (coverage.percent() < 0) {
                    failures.add("覆盖率无数据: " + sourceClass);
                } else if (coverage.percent() < threshold) {
                    failures.add("覆盖率不足: " + sourceClass + " " + coverage.percent() + "% < " + threshold + "%");
                }
            }
        }
        if (failures.isEmpty()) {
            String suffix = requireCoverage ? ", coverage=" + coverage.percent() + "%" : ", coverage=skipped";
            return new Acceptance(true, "accepted: " + testClass + suffix);
        }
        return new Acceptance(false, String.join("; ", failures));
    }

    private AgentBridgeClient.ToolResponse callReadOnlyToolWithRetry(
            URI mcpUrl,
            String name,
            ObjectNode arguments
    ) throws Exception {
        AgentBridgeClient.ToolResponse response = null;
        for (int attempt = 1; attempt <= MCP_READ_RETRY_ATTEMPTS; attempt++) {
            response = client.callTool(mcpUrl, name, arguments);
            if (!isTransientMcpFailure(response) || attempt == MCP_READ_RETRY_ATTEMPTS) {
                return response;
            }
            Thread.sleep(MCP_READ_RETRY_DELAY.toMillis());
        }
        return response;
    }

    private boolean isToolFailure(AgentBridgeClient.ToolResponse response) {
        return response == null || response.rawResult().path("isError").asBoolean(false);
    }

    private boolean isTransientMcpFailure(AgentBridgeClient.ToolResponse response) {
        if (!isToolFailure(response)) {
            return false;
        }
        String text = response == null ? "" : response.text().toLowerCase(Locale.ROOT);
        return text.contains("interrupted") || text.contains("cancelled") || text.contains("canceled");
    }

    private String toolFailureSummary(String tool, AgentBridgeClient.ToolResponse response) {
        String text = response == null ? "没有返回结果" : response.text();
        return "IDE/MCP 调用中断或失败: " + tool + " " + text;
    }

    private boolean hasRecognizedTest(AgentBridgeClient.ToolResponse response) {
        JsonNode tests = response.structured().path("tests");
        if (tests.isArray() && !tests.isEmpty()) {
            return true;
        }
        String text = response.text().toLowerCase(Locale.ROOT);
        return !text.isBlank() && !text.contains("no test") && !text.contains("not found") && !text.contains("missing");
    }

    private boolean hasNoCompilationErrors(AgentBridgeClient.ToolResponse response) {
        JsonNode errors = response.structured().path("errors");
        if (errors.isArray()) {
            return errors.isEmpty();
        }
        String text = response.text().toLowerCase(Locale.ROOT);
        return text.contains("no compilation error") || text.contains("0 errors") || (!text.contains("error") && !text.isBlank());
    }

    private ObjectNode runCommandArguments(
            ProjectUnitTestGenerationProperties properties,
            String testClass,
            String module,
            Path coverageExec
    ) {
        StringBuilder command = new StringBuilder();
        command.append("cd ").append(shellQuote(properties.getProject().getRepo().toAbsolutePath().normalize().toString()));
        String jacocoVersion = properties.getTest().getJacocoVersion();
        Path agentJar = Path.of(System.getProperty("user.home"))
                .resolve(".m2/repository/org/jacoco/org.jacoco.agent")
                .resolve(jacocoVersion)
                .resolve("org.jacoco.agent-" + jacocoVersion + "-runtime.jar");
        String javaAgent = "-javaagent:" + agentJar.toAbsolutePath().normalize()
                + "=destfile=" + coverageExec.toAbsolutePath().normalize();
        String jvmArgBase = properties.getTest().getJacocoJvmArgBase().trim();
        String jvmArgs = jvmArgBase.isBlank() ? javaAgent : jvmArgBase + " " + javaAgent;
        command.append(" && mvn -q org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get");
        command.append(" -Dartifact=").append(shellQuote("org.jacoco:org.jacoco.agent:" + jacocoVersion + ":jar:runtime"));
        command.append(" && mvn -q");
        if (!module.isBlank()) {
            command.append(" -pl ").append(shellQuote(module)).append(" -am");
        }
        command.append(" -Dtest=").append(shellQuote(simpleName(testClass)));
        command.append(" ").append(shellQuote("-D" + properties.getTest().getJacocoJvmArgProperty() + "=" + jvmArgs));
        command.append(" test");
        command.append(" ").append(shellQuote("org.jacoco:jacoco-maven-plugin:" + jacocoVersion + ":report"));
        int timeoutSeconds = Math.max(60, properties.getAgentbridge().getTimeoutMinutes() * 60);
        return objectMapper.createObjectNode()
                .put("command", command.toString())
                .put("timeout", timeoutSeconds)
                .put("max_chars", 12000)
                .put("title", "Run unit test with JaCoCo");
    }

    private ObjectNode runTestsArguments(
            ProjectUnitTestGenerationProperties properties,
            String testClass,
            String module
    ) {
        int timeoutSeconds = Math.max(60, properties.getAgentbridge().getTimeoutMinutes() * 60);
        return objectMapper.createObjectNode()
                .put("target", testClass)
                .put("module", module)
                .put("timeout", timeoutSeconds)
                .put("title", "Run unit test in IDEA");
    }

    private ObjectNode readRunOutputArguments() {
        return objectMapper.createObjectNode()
                .put("offset", -1)
                .put("max_chars", 12000)
                .put("title", "Read IDEA unit test result");
    }

    private boolean ideaTestSucceeded(
            AgentBridgeClient.ToolResponse run,
            AgentBridgeClient.ToolResponse output
    ) {
        return !run.rawResult().path("isError").asBoolean(false)
                && !output.rawResult().path("isError").asBoolean(false)
                && (IDEA_TEST_SUCCESS.matcher(output.text()).find()
                || IDEA_TEST_ZERO_EXIT.matcher(output.text()).find());
    }

    private boolean commandSucceeded(AgentBridgeClient.ToolResponse response) {
        if (response.structured().has("success")) {
            return response.structured().path("success").asBoolean(false);
        }
        String text = response.text().toLowerCase(Locale.ROOT);
        return text.contains("command succeeded")
                || text.contains("build success");
    }

    private Coverage jacocoCoverage(Path report, String sourceClass) throws Exception {
        if (!Files.exists(report)) {
            return new Coverage(-1);
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(report.toFile());
        String className = sourceClass.replace('.', '/');
        NodeList classes = document.getElementsByTagName("class");
        for (int classIndex = 0; classIndex < classes.getLength(); classIndex++) {
            Element classElement = (Element) classes.item(classIndex);
            if (!className.equals(classElement.getAttribute("name"))) {
                continue;
            }
            NodeList counters = classElement.getElementsByTagName("counter");
            for (int counterIndex = 0; counterIndex < counters.getLength(); counterIndex++) {
                Element counter = (Element) counters.item(counterIndex);
                if (!"LINE".equals(counter.getAttribute("type"))) {
                    continue;
                }
                int missed = parseInt(counter.getAttribute("missed"));
                int covered = parseInt(counter.getAttribute("covered"));
                int total = missed + covered;
                return total == 0 ? new Coverage(-1) : new Coverage(covered * 100.0 / total);
            }
            return new Coverage(-1);
        }
        return new Coverage(-1);
    }

    private Path coverageReportPath(Path repo, String module) {
        Path root = repo.toAbsolutePath().normalize();
        if (!module.isBlank()) {
            root = root.resolve(module).normalize();
        }
        return root.resolve("target/site/jacoco/jacoco.xml");
    }

    private Path coverageExecPath(Path repo, String module) {
        Path root = repo.toAbsolutePath().normalize();
        if (!module.isBlank()) {
            root = root.resolve(module).normalize();
        }
        return root.resolve("target/jacoco.exec");
    }

    private int parseInt(String value) {
        return value == null || value.isBlank() ? 0 : Integer.parseInt(value);
    }

    private String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private int threshold(Map<String, Object> batch, ProjectUnitTestGenerationProperties properties) {
        Object coverage = batch.get("coverage");
        if (coverage instanceof Map<?, ?> map && map.get("threshold_percent") instanceof Number number) {
            return number.intValue();
        }
        return properties.getTest().getCoverageThresholdPercent();
    }

    private boolean requireCoverage(Map<String, Object> batch, ProjectUnitTestGenerationProperties properties) {
        Object coverage = batch.get("coverage");
        if (coverage instanceof Map<?, ?> map && map.get("required") instanceof Boolean required) {
            return required;
        }
        return properties.getTest().isRequireCoverage();
    }

    private String sourceClass(Map<String, Object> batch) {
        Object scope = batch.get("scope");
        if (scope instanceof Map<?, ?> map && map.get("qualified_name") != null) {
            return map.get("qualified_name").toString();
        }
        Object types = batch.get("types");
        if (types instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> type && type.get("qualified_name") != null) {
            return type.get("qualified_name").toString();
        }
        return first(batch.get("source_files"));
    }

    private String testClassFqn(String targetTestFile) {
        String normalized = normalize(targetTestFile);
        String marker = "src/test/java/";
        int index = normalized.indexOf(marker);
        String classPath = index >= 0 ? normalized.substring(index + marker.length()) : normalized;
        return classPath.replaceAll("\\.java$", "").replace('/', '.');
    }

    private String simpleName(String qualifiedName) {
        int index = qualifiedName.lastIndexOf('.');
        return index < 0 ? qualifiedName : qualifiedName.substring(index + 1);
    }

    private String moduleName(String targetTestFile) {
        String normalized = normalize(targetTestFile);
        String marker = "/src/test/java/";
        int index = normalized.indexOf(marker);
        return index > 0 ? normalized.substring(0, index) : "";
    }

    private void writeResults(Path out, List<BatchResult> results) throws Exception {
        Files.createDirectories(out);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BatchResult result : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batch_id", result.batchId());
            row.put("accepted", result.accepted());
            row.put("failure_summary", result.failureSummary());
            row.put("attempts", result.attempts().stream().map(AttemptRecord::toMap).toList());
            row.put("issues", result.issues());
            rows.add(row);
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve(RESULTS_JSON).toFile(), Map.of(
                "generated_at", OffsetDateTime.now().toString(),
                "batches", rows
        ));
    }

    private Map<String, BatchResult> readExistingResults(Path out) throws Exception {
        Path path = out.resolve(RESULTS_JSON);
        if (!Files.exists(path)) {
            return new LinkedHashMap<>();
        }
        Map<String, BatchResult> results = new LinkedHashMap<>();
        JsonNode root = objectMapper.readTree(path.toFile());
        for (JsonNode row : root.path("batches")) {
            String batchId = row.path("batch_id").asText("");
            if (batchId.isBlank()) {
                continue;
            }
            results.put(batchId, new BatchResult(
                    batchId,
                    row.path("accepted").asBoolean(false),
                    row.path("failure_summary").asText(""),
                    attempts(row.path("attempts")),
                    strings(row.path("issues"))
            ));
        }
        return results;
    }

    private List<AttemptRecord> attempts(JsonNode rows) {
        if (!rows.isArray()) {
            return List.of();
        }
        List<AttemptRecord> attempts = new ArrayList<>();
        for (JsonNode row : rows) {
            attempts.add(new AttemptRecord(
                    row.path("attempt").asInt(0),
                    row.path("phase").asText("unknown"),
                    row.path("accepted").asBoolean(false),
                    row.path("summary").asText("")
            ));
        }
        return attempts;
    }

    private List<String> strings(JsonNode rows) {
        if (!rows.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        rows.forEach(row -> values.add(row.asText("")));
        return values.stream().filter(value -> !value.isBlank()).toList();
    }

    private String first(Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.get(0).toString();
        }
        return "";
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/');
    }

    public record BatchResult(
            String batchId,
            boolean accepted,
            String failureSummary,
            List<AttemptRecord> attempts,
            List<String> issues
    ) {
    }

    public record AttemptRecord(int attempt, String phase, boolean accepted, String summary) {
        Map<String, Object> toMap() {
            return Map.of(
                    "attempt", attempt,
                    "phase", phase,
                    "accepted", accepted,
                    "summary", summary
            );
        }
    }

    private record Acceptance(boolean accepted, String summary) {
    }

    private record Coverage(double percent) {
    }

    private static class ProtectedSnapshot {
        private final Path repo;
        private final Path out;
        private final List<String> allowedWriteGlobs;
        private final List<String> targetTestFiles;
        private final List<String> additionalBuildArtifactGlobs;
        private final Map<String, String> before;
        private final Path backupRoot;

        private ProtectedSnapshot(
                Path repo,
                Path out,
                List<String> allowedWriteGlobs,
                List<String> targetTestFiles,
                List<String> additionalBuildArtifactGlobs,
                Map<String, String> before,
                Path backupRoot
        ) {
            this.repo = repo.toAbsolutePath().normalize();
            this.out = out.toAbsolutePath().normalize();
            this.allowedWriteGlobs = List.copyOf(allowedWriteGlobs);
            this.targetTestFiles = List.copyOf(targetTestFiles);
            this.additionalBuildArtifactGlobs = List.copyOf(additionalBuildArtifactGlobs);
            this.before = before;
            this.backupRoot = backupRoot.toAbsolutePath().normalize();
        }

        static ProtectedSnapshot capture(
                Path repo,
                Path out,
                Path backupRoot,
                Map<String, Object> batch,
                List<String> additionalBuildArtifactGlobs
        ) throws Exception {
            Path normalizedRepo = repo.toAbsolutePath().normalize();
            var workspace = WorkflowArtifactContext.currentOrNull();
            Path normalizedOut = (workspace == null ? out : workspace.runRoot()).toAbsolutePath().normalize();
            List<String> allowedWriteGlobs = listOfStrings(batch.get("allowed_write_globs"));
            List<String> targetTestFiles = listOfStrings(batch.get("target_test_files"));
            Map<String, String> before = fingerprint(
                    normalizedRepo,
                    normalizedOut,
                    allowedWriteGlobs,
                    targetTestFiles,
                    additionalBuildArtifactGlobs
            );
            Path normalizedBackupRoot = backupRoot.toAbsolutePath().normalize();
            Files.createDirectories(normalizedBackupRoot);
            for (String relative : before.keySet()) {
                Path source = normalizedRepo.resolve(relative).normalize();
                Path backup = normalizedBackupRoot.resolve(relative).normalize();
                Files.createDirectories(backup.getParent());
                Files.copy(
                        source,
                        backup,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
            }
            return new ProtectedSnapshot(
                    normalizedRepo,
                    normalizedOut,
                    allowedWriteGlobs,
                    targetTestFiles,
                    additionalBuildArtifactGlobs,
                    before,
                    normalizedBackupRoot
            );
        }

        ValidationCheck validateAndRestore() {
            try {
                Map<String, String> after = fingerprint(
                        repo,
                        out,
                        allowedWriteGlobs,
                        targetTestFiles,
                        additionalBuildArtifactGlobs
                );
                List<String> violations = new ArrayList<>();
                for (String path : before.keySet()) {
                    if (!after.containsKey(path)) {
                        violations.add("deleted protected file: " + path);
                        restore(path);
                    } else if (!before.get(path).equals(after.get(path))) {
                        violations.add("modified protected file: " + path);
                        restore(path);
                    }
                }
                for (String path : after.keySet()) {
                    if (!before.containsKey(path)) {
                        violations.add("created protected file: " + path);
                        Files.deleteIfExists(repo.resolve(path).normalize());
                    }
                }
                if (!violations.isEmpty()) {
                    Map<String, String> restored = fingerprint(
                            repo,
                            out,
                            allowedWriteGlobs,
                            targetTestFiles,
                            additionalBuildArtifactGlobs
                    );
                    if (!before.equals(restored)) {
                        return ValidationCheck.failed(
                                "protected file restoration failed: protected snapshot still differs"
                        );
                    }
                    return ValidationCheck.failed(String.join("; ", violations));
                }
                return ValidationCheck.success();
            } catch (Exception exception) {
                return ValidationCheck.failed("protected file restoration failed: " + exception.getMessage());
            }
        }

        private void restore(String relative) throws Exception {
            Path source = backupRoot.resolve(relative).normalize();
            Path target = repo.resolve(relative).normalize();
            if (!source.startsWith(backupRoot) || !target.startsWith(repo)) {
                throw new IllegalStateException("protected restore path escapes its root: " + relative);
            }
            requireNoSymbolicLinkComponents(backupRoot, source, "protected restore backup");
            if (!Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("protected restore backup is not a regular file: " + relative);
            }
            if (!before.get(relative).equals(sha256(source))) {
                throw new IllegalStateException("protected restore backup hash changed: " + relative);
            }
            requireNoSymbolicLinkComponents(repo, target, "protected restore target");
            Files.createDirectories(target.getParent());
            requireNoSymbolicLinkComponents(repo, target, "protected restore target");
            Files.copy(
                    source,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.COPY_ATTRIBUTES
            );
        }

        private static void requireNoSymbolicLinkComponents(
                Path base,
                Path target,
                String label
        ) throws Exception {
            Path normalizedBase = base.toAbsolutePath().normalize();
            Path normalizedTarget = target.toAbsolutePath().normalize();
            if (!normalizedTarget.startsWith(normalizedBase)) {
                throw new IllegalStateException(label + " escapes its root: " + normalizedTarget);
            }
            if (Files.isSymbolicLink(normalizedBase)) {
                throw new IllegalStateException(label + " contains symbolic link: " + normalizedBase);
            }
            Path current = normalizedBase;
            for (Path component : normalizedBase.relativize(normalizedTarget)) {
                current = current.resolve(component);
                if (Files.isSymbolicLink(current)) {
                    throw new IllegalStateException(label + " contains symbolic link: " + current);
                }
            }
        }

        private static Map<String, String> fingerprint(
                Path repo,
                Path out,
                List<String> allowedWriteGlobs,
                List<String> targetTestFiles,
                List<String> additionalBuildArtifactGlobs
        ) throws Exception {
            if (!Files.exists(repo)) {
                return Map.of();
            }
            Map<String, String> hashes = new LinkedHashMap<>();
            try (var stream = Files.walk(repo)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(file -> !isAllowedWrite(
                                repo,
                                out,
                                file,
                                allowedWriteGlobs,
                                targetTestFiles,
                                additionalBuildArtifactGlobs
                        ))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList()) {
                    hashes.put(normalize(repo.relativize(file.toAbsolutePath().normalize()).toString()), sha256(file));
                }
            }
            return hashes;
        }

        private static boolean isAllowedWrite(
                Path repo,
                Path out,
                Path file,
                List<String> allowedWriteGlobs,
                List<String> targetTestFiles,
                List<String> additionalBuildArtifactGlobs
        ) {
            Path normalized = file.toAbsolutePath().normalize();
            Path gitRoot = repo.resolve(".git").normalize();
            Path agentBridgeRoot = repo.resolve(".agentbridge").normalize();
            Path ideaRoot = repo.resolve(".idea").normalize();
            return ProjectUnitTestGenerationPaths.isAllowedBatchTestWrite(repo, normalized, allowedWriteGlobs, targetTestFiles)
                    || ProjectUnitTestGenerationPaths.isAllowedBatchPomWrite(repo, normalized, allowedWriteGlobs)
                    || ProjectUnitTestGenerationPaths.isBuildArtifact(repo, normalized, additionalBuildArtifactGlobs)
                    || normalized.startsWith(gitRoot)
                    || normalized.startsWith(agentBridgeRoot)
                    || normalized.startsWith(ideaRoot)
                    || (out.startsWith(repo) && normalized.startsWith(out));
        }

        private static List<String> listOfStrings(Object value) {
            if (!(value instanceof List<?> list)) {
                return List.of();
            }
            return list.stream().map(Object::toString).toList();
        }

        private static String sha256(Path file) throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        }
    }
}
