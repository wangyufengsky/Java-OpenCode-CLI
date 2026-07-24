package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sonnet.wyf.gitreport.agentbridge.AgentBridgeClient;
import com.sonnet.wyf.gitreport.artifact.TaskArtifactLayout;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactWorkspace;
import com.sonnet.wyf.gitreport.console.WorkflowEventSink;
import com.sonnet.wyf.gitreport.console.WorkflowRunContext;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class MyBatisSqlReviewTaskRunner {
    static final long MAX_CANDIDATE_FILE_BYTES = 1_048_576L;
    static final long MAX_CANDIDATE_TOTAL_BYTES = 2_359_296L;
    private static final List<String> CANDIDATE_ARTIFACTS = List.of(
            "report.md", "summary.json", "database-evidence.json"
    );

    private final AgentBridgeClient client;
    private final MyBatisSqlPromptBuilder promptBuilder;
    private final MyBatisToolCallAudit toolCallAudit;
    private final MyBatisSqlOutputValidator outputValidator;
    private final WorkflowEventSink eventSink;
    private final ObjectMapper objectMapper;

    public MyBatisSqlReviewTaskRunner(
            AgentBridgeClient client,
            MyBatisSqlPromptBuilder promptBuilder,
            MyBatisToolCallAudit toolCallAudit,
            MyBatisSqlOutputValidator outputValidator,
            WorkflowEventSink eventSink,
            ObjectMapper objectMapper
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        this.toolCallAudit = Objects.requireNonNull(toolCallAudit, "toolCallAudit");
        this.outputValidator = Objects.requireNonNull(outputValidator, "outputValidator");
        this.eventSink = Objects.requireNonNull(eventSink, "eventSink");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public TaskResult run(
            WorkflowArtifactWorkspace workspace,
            Path repository,
            MyBatisSqlStatement statement,
            MyBatisDatabasePreflight.Result database,
            AgentBridgeSettings settings
    ) throws Exception {
        PreparedTask prepared = prepare(workspace, statement, database, settings);
        Path mapper = repository.toAbsolutePath().normalize().resolve(statement.mapperRelativePath()).normalize();
        try (MyBatisSqlReviewFilesystemGuard guard = MyBatisSqlReviewFilesystemGuard.protectRun(
                objectMapper,
                repository,
                workspace.stableRoot(),
                workspace.runRoot(),
                List.of(mapper.getParent()),
                List.of(mapper),
                List.of(prepared.layout().candidate())
        )) {
            return runPrepared(workspace, prepared, guard);
        }
    }

    public PreparedTask prepare(
            WorkflowArtifactWorkspace workspace,
            MyBatisSqlStatement statement,
            MyBatisDatabasePreflight.Result database,
            AgentBridgeSettings settings
    ) throws Exception {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(settings, "settings");
        TaskArtifactLayout layout = workspace.nextTaskAttempt("sql:" + statement.statementKey());
        String commandType = commandType(statement);
        String prompt = promptBuilder.build(new MyBatisSqlPromptBuilder.Context(
                statement.statementKey(),
                statement.mapperRelativePath(),
                statement.namespace(),
                statement.id(),
                commandType,
                statement.selectKey(),
                statement.startLine(),
                statement.endLine(),
                statement.rawXml(),
                statement.normalizedSql(),
                statement.dynamicNodeNames(),
                statement.parameterPlaceholders(),
                database.binding().dataSource(),
                database.binding().catalog(),
                database.binding().schema(),
                database.binding().project(),
                database.binding().scope().name(),
                database.safetyMode().configValue(),
                database.databaseSafety(),
                layout.candidate()
        ));
        Files.writeString(layout.workerPrompt(), prompt, StandardCharsets.UTF_8);
        writeTaskJson(layout, statement, database);
        writeStatus(layout, statement, "QUEUED", "created", "");
        Path bundleDirectory = MyBatisSqlReportRenderer.statementDirectory(workspace.bundleRoot(), statement);
        Files.createDirectories(bundleDirectory);
        initializeJavaFile(layout.root().resolve("tool-call-boundary.json"));
        initializeJavaFile(layout.validation());
        for (String artifact : CANDIDATE_ARTIFACTS) {
            initializeJavaFile(bundleDirectory.resolve(artifact));
        }
        return new PreparedTask(statement, database, settings, layout, commandType, prompt,
                "MyBatis SQL review: " + statement.statementKey(), bundleDirectory);
    }

    public TaskResult runPrepared(
            WorkflowArtifactWorkspace workspace,
            PreparedTask prepared,
            MyBatisSqlReviewFilesystemGuard guard
    ) throws Exception {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(guard, "guard");
        MyBatisSqlStatement statement = prepared.statement();
        MyBatisDatabasePreflight.Result database = prepared.database();
        AgentBridgeSettings settings = prepared.settings();
        TaskArtifactLayout layout = prepared.layout();
        String commandType = prepared.commandType();
        String prompt = prepared.prompt();
        String title = prepared.title();
        try (var ignored = WorkflowRunContext.openTask(statement.statementKey(), title)) {
            eventSink.taskStatusCurrent(
                    statement.statementKey(), title, "QUEUED", "created",
                    layout.status().toString(), ""
            );
            try {
                URI webUri = URI.create(settings.getWebBaseUrl());
                new MyBatisDatabasePreflight(client).recheck(
                        URI.create(settings.getMcpUrl()),
                        webUri,
                        database
                );
                client.clearSession(webUri);
                client.waitUntilIdle(
                        webUri,
                        Duration.ofMinutes(Math.max(1, settings.getTimeoutMinutes())),
                        Duration.ofMillis(Math.max(50, settings.getPollMillis()))
                );
                List<AgentBridgeClient.ToolCallRecord> before = client.getToolCalls(webUri);
                Instant startedAt = Instant.now();
                Set<String> preexistingIds = new LinkedHashSet<>();
                for (AgentBridgeClient.ToolCallRecord call : before) {
                    if (call == null || call.id() == null || call.id().isBlank()) {
                        throw new IllegalStateException("pre-task tool-call history contains a missing call id");
                    }
                    if (!preexistingIds.add(call.id())) {
                        throw new IllegalStateException("pre-task tool-call history contains duplicate call id: " + call.id());
                    }
                }
                MyBatisToolCallAudit.Boundary boundary = new MyBatisToolCallAudit.Boundary(
                        startedAt, preexistingIds
                );
                guard.withJavaWrites(List.of(
                        layout.root().resolve("tool-call-boundary.json"), layout.status()
                ), () -> {
                    writeBoundary(layout, boundary);
                    writeStatus(layout, statement, "RUNNING", "submitted", "");
                    return null;
                });
                eventSink.taskStatusCurrent(
                        statement.statementKey(), title, "RUNNING", "submitted",
                        layout.status().toString(), ""
                );
                try (MyBatisSqlReviewFilesystemGuard.TaskScope ignoredGuard =
                             guard.protectTask(layout.candidate())) {
                    client.postPrompt(webUri, composeMessage(settings.getTaskMessage(), prompt));
                    client.waitUntilIdle(
                            webUri,
                            Duration.ofMinutes(Math.max(1, settings.getTimeoutMinutes())),
                            Duration.ofMillis(Math.max(50, settings.getPollMillis()))
                    );
                }
                settle(settings.getValidationSettleSeconds());
                List<AgentBridgeClient.ToolCallRecord> after = client.getToolCalls(webUri);
                MyBatisSqlReviewFilesystemGuard.requireSafeCandidate(layout.root(), layout.candidate());
                MyBatisToolCallAudit.Result audit = toolCallAudit.audit(
                        after,
                        boundary,
                        database,
                        new MyBatisToolCallAudit.StatementContext(
                                statement.statementKey(), commandType, statement.selectKey()
                        )
                );
                MyBatisSqlOutputValidator.Result validation = outputValidator.validate(
                        layout.candidate(),
                        new MyBatisSqlOutputValidator.ExpectedTaskContext(
                                statement.statementKey(),
                                statement.mapperRelativePath(),
                                statement.namespace(),
                                statement.id(),
                                commandType,
                                statement.selectKey()
                        ),
                        database,
                        audit
                );
                CandidateSnapshot candidateSnapshot = captureCandidate(layout.candidate());
                MyBatisSqlReviewFilesystemGuard.SealedWrite<Path> sealedWrite = guard.withJavaWritesSealed(
                        List.of(
                                layout.validation(), layout.status(),
                                prepared.bundleDirectory().resolve("report.md"),
                                prepared.bundleDirectory().resolve("summary.json"),
                                prepared.bundleDirectory().resolve("database-evidence.json")
                        ),
                        () -> {
                            writeValidation(layout, validation);
                            Path published = publishCandidateToBundle(
                                    workspace, layout, statement, candidateSnapshot
                            );
                            writeStatus(layout, statement, "SUCCEEDED", "completed_by_output", "");
                            return published;
                        }
                );
                Path publishedDirectory = sealedWrite.value();
                CandidateSnapshot publishedSnapshot = captureCandidate(publishedDirectory);
                verifySealedBundle(candidateSnapshot, publishedSnapshot, publishedDirectory, sealedWrite);
                MyBatisSqlOutputValidator.Result copiedValidation = outputValidator.validate(
                        publishedDirectory,
                        new MyBatisSqlOutputValidator.ExpectedTaskContext(
                                statement.statementKey(),
                                statement.mapperRelativePath(),
                                statement.namespace(),
                                statement.id(),
                                commandType,
                                statement.selectKey()
                        ),
                        database,
                        audit
                );
                if (!validation.equals(copiedValidation)) {
                    throw new IllegalStateException(
                            "bundle validation changed after candidate copy: " + statement.statementKey()
                    );
                }
                eventSink.taskStatusCurrent(
                        statement.statementKey(), title, "SUCCEEDED", "completed_by_output",
                        layout.status().toString(), ""
                );
                return new TaskResult(
                        statement.statementKey(), layout.attempt(), layout.root(),
                        publishedDirectory, validation
                );
            } catch (Exception exception) {
                String error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                try {
                    guard.withJavaWrites(List.of(layout.status()), () -> {
                        writeStatus(layout, statement, "FAILED", "validation_failed_final", error);
                        return null;
                    });
                } catch (Exception statusFailure) {
                    exception.addSuppressed(statusFailure);
                }
                eventSink.taskStatusCurrent(
                        statement.statementKey(), title, "FAILED", "validation_failed_final",
                        layout.status().toString(), error
                );
                throw exception;
            }
        }
    }

    public record PreparedTask(
            MyBatisSqlStatement statement,
            MyBatisDatabasePreflight.Result database,
            AgentBridgeSettings settings,
            TaskArtifactLayout layout,
            String commandType,
            String prompt,
            String title,
            Path bundleDirectory
    ) {
    }

    void validatePublishedOffline(Path bundleRoot, MyBatisSqlStatement statement) throws IOException {
        outputValidator.validatePublishedOffline(
                MyBatisSqlReportRenderer.statementDirectory(bundleRoot, statement),
                new MyBatisSqlOutputValidator.ExpectedTaskContext(
                        statement.statementKey(),
                        statement.mapperRelativePath(),
                        statement.namespace(),
                        statement.id(),
                        commandType(statement),
                        statement.selectKey()
                )
        );
    }

    private void writeTaskJson(
            TaskArtifactLayout layout,
            MyBatisSqlStatement statement,
            MyBatisDatabasePreflight.Result database
    ) throws IOException {
        ObjectNode task = objectMapper.createObjectNode();
        task.put("schema_version", "mybatis-sql-review-task-attempt/v1");
        task.put("task_key", statement.statementKey());
        task.put("attempt", layout.attempt());
        task.put("mapper_key", statement.mapperKey());
        task.put("mapper_relative_path", statement.mapperRelativePath());
        task.put("namespace", statement.namespace());
        task.put("statement_id", statement.id());
        task.put("command_type", commandType(statement));
        task.put("select_key", statement.selectKey());
        task.put("candidate_directory", layout.candidate().toAbsolutePath().normalize().toString());
        task.put("data_source", database.binding().dataSource());
        task.put("catalog", database.binding().catalog());
        task.put("schema", database.binding().schema());
        task.put("project", database.binding().project().toString());
        task.put("scope", database.binding().scope().name());
        task.put("safety_mode", database.safetyMode().configValue());
        task.put("database_safety", database.databaseSafety());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(layout.taskJson().toFile(), task);
    }

    private void writeValidation(
            TaskArtifactLayout layout,
            MyBatisSqlOutputValidator.Result validation
    ) throws IOException {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("schema_version", "mybatis-sql-review-validation/v1");
        result.put("status", "accepted");
        result.put("statement_key", validation.statementKey());
        result.put("scenario_count", validation.scenarioCount());
        result.put("evidence_bytes", validation.evidenceBytes());
        result.putPOJO("audited_call_ids", validation.auditedCallIds());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(layout.validation().toFile(), result);
    }

    private void writeBoundary(
            TaskArtifactLayout layout,
            MyBatisToolCallAudit.Boundary boundary
    ) throws IOException {
        ObjectNode value = objectMapper.createObjectNode();
        value.put("schema_version", "mybatis-sql-review-tool-call-boundary/v1");
        value.put("started_at", boundary.startedAt().toString());
        value.put("preexisting_call_count", boundary.preexistingCallIds().size());
        value.putPOJO("preexisting_call_ids", boundary.preexistingCallIds().stream().sorted().toList());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                layout.root().resolve("tool-call-boundary.json").toFile(), value
        );
    }

    private void writeStatus(
            TaskArtifactLayout layout,
            MyBatisSqlStatement statement,
            String state,
            String phase,
            String error
    ) {
        try {
            ObjectNode status = objectMapper.createObjectNode();
            status.put("schema_version", "mybatis-sql-review-agent-status/v1");
            status.put("task_key", statement.statementKey());
            status.put("attempt", layout.attempt());
            status.put("state", state);
            status.put("phase", phase);
            status.put("candidate_directory", layout.candidate().toString());
            status.put("error", error == null ? "" : error);
            status.put("updated_at", OffsetDateTime.now().toString());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(layout.status().toFile(), status);
        } catch (IOException ignored) {
            // Task status is diagnostic and must not hide the original workflow result.
        }
    }

    private Path publishCandidateToBundle(
            WorkflowArtifactWorkspace workspace,
            TaskArtifactLayout layout,
            MyBatisSqlStatement statement,
            CandidateSnapshot snapshot
    ) throws IOException {
        Path target = MyBatisSqlReportRenderer.statementDirectory(workspace.bundleRoot(), statement);
        copyCandidateSnapshot(layout.candidate(), target, snapshot);
        return target;
    }

    static void copyCandidateSnapshot(
            Path candidate,
            Path target,
            CandidateSnapshot snapshot
    ) throws IOException {
        requireExactTargetTree(target);
        for (String artifact : CANDIDATE_ARTIFACTS) {
            Path source = candidate.resolve(artifact);
            Path destination = target.resolve(artifact);
            CandidateFile expected = snapshot.files().get(artifact);
            MessageDigest digest = sha256Digest();
            long copied = 0;
            try (var input = Files.newInputStream(
                    source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS
            ); var output = Files.newOutputStream(
                    destination,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS
            )) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        copied += read;
                        if (copied > expected.size()) {
                            throw new IllegalStateException(
                                    "candidate changed while copying: " + artifact
                            );
                        }
                        digest.update(buffer, 0, read);
                        output.write(buffer, 0, read);
                    }
                }
            }
            String copiedDigest = HexFormat.of().formatHex(digest.digest());
            if (copied != expected.size() || !copiedDigest.equals(expected.sha256())) {
                throw new IllegalStateException("candidate changed after validation: " + artifact);
            }
        }
    }

    static CandidateSnapshot captureCandidate(Path candidate) throws IOException {
        MyBatisSqlReviewFilesystemGuard.requireSafeCandidate(candidate.getParent(), candidate);
        Map<String, CandidateFile> files = new LinkedHashMap<>();
        long total = 0;
        for (String artifact : CANDIDATE_ARTIFACTS) {
            Path path = candidate.resolve(artifact);
            BasicFileAttributes before = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!before.isRegularFile() || before.isSymbolicLink()
                    || before.size() > MAX_CANDIDATE_FILE_BYTES) {
                throw new IllegalStateException(
                        "candidate artifact exceeds safe regular-file limit: " + artifact
                );
            }
            MessageDigest digest = sha256Digest();
            long size = 0;
            try (var input = Files.newInputStream(
                    path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS
            )) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        size += read;
                        if (size > MAX_CANDIDATE_FILE_BYTES) {
                            throw new IllegalStateException(
                                    "candidate artifact exceeds safe size limit: " + artifact
                            );
                        }
                        digest.update(buffer, 0, read);
                    }
                }
            }
            BasicFileAttributes after = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS
            );
            if (!after.isRegularFile()
                    || !Objects.equals(before.fileKey(), after.fileKey())
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.size() != after.size()
                    || size != after.size()) {
                throw new IllegalStateException("candidate changed while capturing: " + artifact);
            }
            total += size;
            if (total > MAX_CANDIDATE_TOTAL_BYTES) {
                throw new IllegalStateException("candidate artifacts exceed total safe size limit");
            }
            files.put(artifact, new CandidateFile(
                    size,
                    HexFormat.of().formatHex(digest.digest()),
                    after.fileKey() == null ? "" : after.fileKey().toString(),
                    after.lastModifiedTime()
            ));
        }
        return new CandidateSnapshot(Map.copyOf(files), total);
    }

    static void verifySealedBundle(
            CandidateSnapshot validated,
            CandidateSnapshot published,
            Path publishedDirectory,
            MyBatisSqlReviewFilesystemGuard.SealedWrite<?> sealedWrite
    ) {
        for (String artifact : CANDIDATE_ARTIFACTS) {
            CandidateFile expected = validated.files().get(artifact);
            CandidateFile actual = published.files().get(artifact);
            if (expected == null || actual == null
                    || expected.size() != actual.size()
                    || !expected.sha256().equals(actual.sha256())) {
                throw new IllegalStateException(
                        "sealed bundle differs from validated candidate: " + artifact
                );
            }
            Path path = publishedDirectory.resolve(artifact).toAbsolutePath().normalize();
            MyBatisSqlReviewFilesystemGuard.SealedFile sealed = sealedWrite.files().get(path);
            if (sealed == null
                    || sealed.size() != actual.size()
                    || !sealed.sha256().equals(actual.sha256())
                    || !sealed.fileKey().equals(actual.fileKey())
                    || !sealed.lastModifiedTime().equals(actual.lastModifiedTime())) {
                throw new IllegalStateException(
                        "published bundle changed after filesystem sealing: " + artifact
                );
            }
        }
    }

    private static void requireExactTargetTree(Path target) throws IOException {
        if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("bundle target is missing or unsafe: " + target);
        }
        Set<String> actual = new LinkedHashSet<>();
        try (var entries = Files.list(target)) {
            for (Path entry : entries.toList()) {
                actual.add(entry.getFileName().toString());
                if (Files.isSymbolicLink(entry)
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("bundle target contains unsafe artifact: " + entry);
                }
            }
        }
        if (!actual.equals(Set.copyOf(CANDIDATE_ARTIFACTS))) {
            throw new IllegalStateException("bundle target tree changed before copy: " + actual);
        }
    }

    private static void initializeJavaFile(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("Java-managed output path is unsafe: " + path);
            }
            return;
        }
        Files.createFile(path);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void settle(int seconds) throws InterruptedException {
        if (seconds > 0) {
            Thread.sleep(Duration.ofSeconds(seconds).toMillis());
        }
    }

    private String composeMessage(String message, String prompt) {
        return message == null || message.isBlank() ? prompt : message + "\n\n" + prompt;
    }

    private String commandType(MyBatisSqlStatement statement) {
        return statement.selectKey()
                ? "selectKey"
                : statement.commandType().toLowerCase(Locale.ROOT);
    }

    public record TaskResult(
            String statementKey,
            int attempt,
            Path attemptRoot,
            Path bundleDirectory,
            MyBatisSqlOutputValidator.Result validation
    ) {
    }

    record CandidateSnapshot(Map<String, CandidateFile> files, long totalBytes) {
        CandidateSnapshot {
            files = Map.copyOf(files);
        }
    }

    record CandidateFile(long size, String sha256, String fileKey, java.nio.file.attribute.FileTime lastModifiedTime) {
    }
}
