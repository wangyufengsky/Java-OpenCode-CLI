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
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class MyBatisSqlReviewTaskRunner {
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
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(repository, "repository");
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
                database.connectionId(),
                database.databaseName(),
                database.schemaName(),
                layout.candidate()
        ));
        Files.writeString(layout.workerPrompt(), prompt, StandardCharsets.UTF_8);
        writeTaskJson(layout, statement, database);

        String title = "MyBatis SQL review: " + statement.statementKey();
        try (var ignored = WorkflowRunContext.openTask(statement.statementKey(), title)) {
            writeStatus(layout, statement, "QUEUED", "created", "");
            eventSink.taskStatusCurrent(
                    statement.statementKey(), title, "QUEUED", "created",
                    layout.status().toString(), ""
            );
            try {
                URI webUri = URI.create(settings.getWebBaseUrl());
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
                writeBoundary(layout, boundary);
                writeStatus(layout, statement, "RUNNING", "submitted", "");
                eventSink.taskStatusCurrent(
                        statement.statementKey(), title, "RUNNING", "submitted",
                        layout.status().toString(), ""
                );
                try (MyBatisSqlReviewFilesystemGuard ignoredGuard =
                             MyBatisSqlReviewFilesystemGuard.protect(
                                     repository,
                                     workspace.stableRoot(),
                                     layout.root(),
                                     layout.candidate()
                             )) {
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
                writeValidation(layout, validation);
                Path publishedDirectory = publishCandidateToBundle(workspace, layout, statement);
                writeStatus(layout, statement, "SUCCEEDED", "completed_by_output", "");
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
                writeStatus(layout, statement, "FAILED", "validation_failed_final", error);
                eventSink.taskStatusCurrent(
                        statement.statementKey(), title, "FAILED", "validation_failed_final",
                        layout.status().toString(), error
                );
                throw exception;
            }
        }
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
        task.put("connection_id", database.connectionId());
        task.put("database_name", database.databaseName());
        task.put("schema_name", database.schemaName());
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
            MyBatisSqlStatement statement
    ) throws IOException {
        Path target = MyBatisSqlReportRenderer.statementDirectory(workspace.bundleRoot(), statement);
        if (Files.exists(target)) {
            try (var paths = Files.walk(target)) {
                for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(target);
        for (String artifact : CANDIDATE_ARTIFACTS) {
            Path source = layout.candidate().resolve(artifact);
            try (var input = Files.newInputStream(
                    source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS
            )) {
                Files.copy(input, target.resolve(artifact), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
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
}
