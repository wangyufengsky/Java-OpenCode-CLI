package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerHandle;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerManager;
import com.sonnet.wyf.gitreport.opencode.OpenCodeServerTaskRunner;
import com.sonnet.wyf.gitreport.opencode.ValidatedOpenCodeTaskSpec;
import com.sonnet.wyf.gitreport.orchestration.ConcurrentWorkflowTaskRunner;
import com.sonnet.wyf.gitreport.orchestration.TaskRunResult;
import com.sonnet.wyf.gitreport.runner.OpenCodeSettings;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyCodeReviewOutputValidator;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEngineeringReportProperties;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyOpenCodeReviewRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeeklyOpenCodeReviewRunnerTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void usesWeeklyYamlOpenCodeSettingsWhenRunningBatch() throws Exception {
        WeeklyEngineeringReportProperties properties = properties();
        properties.getOpencode().setTimeoutMinutes(7);
        properties.getOpencode().setValidationMaxCorrections(4);
        properties.getOpencode().setOutputWaitSeconds(5);
        CapturingTaskRunner taskRunner = new CapturingTaskRunner();
        WeeklyOpenCodeReviewRunner runner = new WeeklyOpenCodeReviewRunner(
                objectMapper,
                fakeServerManager(),
                taskRunner,
                directTaskRunner(),
                new WeeklyCodeReviewOutputValidator(objectMapper)
        );
        Path evidence = writeEvidence();

        runner.run(properties, request(), evidence, List.of());

        assertThat(taskRunner.spec.timeoutMinutes()).isEqualTo(7);
        assertThat(taskRunner.spec.validationMaxCorrections()).isEqualTo(4);
        assertThat(taskRunner.spec.validationSettleSeconds()).isEqualTo(5);
    }

    @Test
    void writesReviewUnitPromptThatRequiresFullRegionCoverageAndTraceableFindings() throws Exception {
        CapturingTaskRunner taskRunner = new CapturingTaskRunner();
        WeeklyOpenCodeReviewRunner runner = new WeeklyOpenCodeReviewRunner(
                objectMapper,
                fakeServerManager(),
                taskRunner,
                directTaskRunner(),
                new WeeklyCodeReviewOutputValidator(objectMapper)
        );

        runner.run(properties(), request(), writeEvidence(), List.of());

        String prompt = Files.readString(taskRunner.spec.promptFile());
        assertThat(prompt)
                .contains("review unit", "模块/作者", "多文件", "多 commit")
                .contains("读取 input_json 时，必须使用 `intellij-idea` MCP 文件读取工具")
                .contains("写入 summary_json 和 review_md 时，必须使用 `intellij-idea` MCP 文件编辑工具")
                .contains("`intellij-idea` MCP 读写工具不可用时必须返回 BLOCKED")
                .contains("reviewed_region_ids 必须覆盖 input_json.changed_regions 中的全部 region_id")
                .contains("不得只挑重点审查，不得因为文件多而跳过低风险 region")
                .contains("finding 必须绑定 region_id、author_key、commit、file、line_start、line_end")
                .contains("code-review.md 必须按模块/文件分节")
                .contains("unit_id: review-unit-001")
                .doesNotContain("OpenCode 原生文件");
    }

    @Test
    void rejectsUnknownReviewBatchRerunId() throws Exception {
        WeeklyOpenCodeReviewRunner runner = new WeeklyOpenCodeReviewRunner(
                objectMapper,
                fakeServerManager(),
                new CapturingTaskRunner(),
                directTaskRunner(),
                new WeeklyCodeReviewOutputValidator(objectMapper)
        );

        assertThatThrownBy(() -> runner.run(properties(), request(), writeEvidence(), List.of("missing-batch")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown weekly review batch id");
    }

    private WeeklyEngineeringReportProperties properties() {
        WeeklyEngineeringReportProperties properties = new WeeklyEngineeringReportProperties();
        properties.getProject().setRepo(tempDir);
        return properties;
    }

    private WorkflowRunRequest request() {
        OpenCodeSettings settings = new OpenCodeSettings();
        settings.setTimeoutMinutes(99);
        settings.setValidationMaxCorrections(99);
        settings.setOutputWaitSeconds(99);
        return new WorkflowRunRequest("full", "", "", LocalDate.of(2026, 6, 29), settings);
    }

    private Path writeEvidence() throws Exception {
        Path summary = tempDir.resolve("review-units/review-unit-001/code-review-summary.json");
        Path review = tempDir.resolve("review-units/review-unit-001/code-review.md");
        Path input = tempDir.resolve("review-units/review-unit-001/input.json");
        Files.createDirectories(summary.getParent());
        Path evidence = tempDir.resolve("weekly-evidence.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), Map.of(
                "review_batches", List.of(Map.of(
                        "batch_id", "review-unit-001",
                        "unit_id", "review-unit-001",
                        "group", Map.of(
                                "strategy", "module-author-capacity",
                                "module", "upfs-cup/src/main/java/com/spdb/upfs/cup/service/esf",
                                "author_key", "author-001-alice",
                                "region_count", 1,
                                "file_count", 1,
                                "commit_count", 1
                        ),
                        "input_json", input.toString(),
                        "summary_json", summary.toString(),
                        "review_md", review.toString(),
                        "changed_regions", List.of(Map.of(
                                "region_id", "region-00001",
                                "author_key", "author-001-alice",
                                "commit", "abc",
                                "file", "Foo.java",
                                "line_start", 1,
                                "line_end", 1,
                                "hunk", "+x"
                        ))
                ))
        ));
        return evidence;
    }

    private OpenCodeServerManager fakeServerManager() throws Exception {
        return new OpenCodeServerManager(null, null) {
            @Override
            public synchronized OpenCodeServerHandle ensureReady(OpenCodeSettings settings, Path out) {
                return new OpenCodeServerHandle(URI.create("http://127.0.0.1:1"), false);
            }
        };
    }

    private ConcurrentWorkflowTaskRunner directTaskRunner() {
        return new ConcurrentWorkflowTaskRunner(Runnable::run) {
            @Override
            public <T> List<TaskRunResult> run(String workflowName, List<T> tasks, int concurrency, java.util.function.Function<T, String> taskKey, java.util.function.Function<T, java.util.concurrent.Callable<TaskRunResult>> taskFactory) throws Exception {
                java.util.ArrayList<TaskRunResult> results = new java.util.ArrayList<>();
                for (T task : tasks) {
                    results.add(taskFactory.apply(task).call());
                }
                return results;
            }
        };
    }

    private static class CapturingTaskRunner extends OpenCodeServerTaskRunner {
        private ValidatedOpenCodeTaskSpec spec;

        private CapturingTaskRunner() throws Exception {
            super(null, null);
        }

        @Override
        public com.sonnet.wyf.gitreport.opencode.OpenCodeRunResult runUntilValidated(ValidatedOpenCodeTaskSpec spec) throws Exception {
            this.spec = spec;
            Files.writeString(Path.of(spec.runDir().getParent().getParent().toString(), "review-units", "review-unit-001", "code-review.md"), "# 批次代码审查\n");
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(spec.runDir().getParent().getParent().toString(), "review-units", "review-unit-001", "code-review-summary.json").toFile(), Map.ofEntries(
                    Map.entry("schema_version", "weekly-code-review-output/v1"),
                    Map.entry("batch_id", "review-unit-001"),
                    Map.entry("status", "completed"),
                    Map.entry("summary", "ok"),
                    Map.entry("reviewed_region_ids", List.of("region-00001")),
                    Map.entry("finding_counts", Map.of("P0", 0, "P1", 0, "P2", 0)),
                    Map.entry("findings", List.of()),
                    Map.entry("positive_signals", List.of()),
                    Map.entry("risk_signals", List.of()),
                    Map.entry("code_snippets", List.of()),
                    Map.entry("unverified", List.of())
            ));
            return null;
        }
    }
}
