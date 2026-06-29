package com.sonnet.wyf.gitreport.console;

import com.sonnet.wyf.gitreport.runner.OpenCodeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowExecutionServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void startsOnlyOneWorkflowAndLeavesLaterRunsQueued() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        WorkflowChain chain = blockingChain(firstStarted, releaseFirst);
        Fixture fixture = fixture(List.of(chain));

        long first = fixture.service.submit(new WorkflowRunSubmission("demo-chain", "full", null, null, null, Map.of("value", "first"), null));
        long second = fixture.service.submit(new WorkflowRunSubmission("demo-chain", "full", null, null, null, Map.of("value", "second"), null));

        assertThat(firstStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.repository.findRun(first)).get().extracting(WorkflowRunRecord::state).isEqualTo(RunState.RUNNING);
        assertThat(fixture.repository.findRun(second)).get().extracting(WorkflowRunRecord::state).isEqualTo(RunState.QUEUED);

        releaseFirst.countDown();
        awaitState(fixture.repository, first, RunState.SUCCEEDED);
        awaitState(fixture.repository, second, RunState.SUCCEEDED);
    }

    @Test
    void writesPerRunConfigFromStructuredFieldsWithoutChangingDefaults() throws Exception {
        Path configDir = tempDir.resolve("defaults");
        Files.createDirectories(configDir);
        Path defaultYaml = configDir.resolve("demo-chain.yml");
        Files.writeString(defaultYaml, "value: default");
        OpenCodeRunnerProperties runnerProperties = new OpenCodeRunnerProperties();
        runnerProperties.setConfigDir(configDir.toString());
        CapturingChain chain = new CapturingChain(runnerProperties);
        Fixture fixture = fixture(List.of(chain), runnerProperties);

        long runId = fixture.service.submit(new WorkflowRunSubmission("demo-chain", "full", null, null, null, Map.of("value", "edited"), null));

        awaitState(fixture.repository, runId, RunState.SUCCEEDED);
        assertThat(Files.readString(defaultYaml)).isEqualTo("value: default");
        assertThat(chain.configDir).contains("run-" + runId);
        assertThat(Files.readString(Path.of(chain.configDir).resolve("demo-chain.yml"))).contains("value: \"edited\"");
    }

    @Test
    void blankConfigSubmissionUsesDefaultStructuredConfigEvenWhileAnotherRunIsActive() throws Exception {
        Path configDir = tempDir.resolve("defaults");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("demo-chain.yml"), "value: default");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        Fixture fixture = fixture(List.of(blockingChain(firstStarted, releaseFirst)), configDir.toString());

        long first = fixture.service.submit(new WorkflowRunSubmission("demo-chain", "full", null, null, null, Map.of("value", "edited"), null));
        assertThat(firstStarted.await(3, TimeUnit.SECONDS)).isTrue();
        long second = fixture.service.submit(new WorkflowRunSubmission("demo-chain", "full", null, null, null, Map.of(), null));

        assertThat(Files.readString(Path.of(fixture.repository.findRun(first).orElseThrow().configPath()))).contains("value: \"edited\"");
        assertThat(Files.readString(Path.of(fixture.repository.findRun(second).orElseThrow().configPath()))).isEqualTo("value: default");

        releaseFirst.countDown();
        awaitState(fixture.repository, first, RunState.SUCCEEDED);
        awaitState(fixture.repository, second, RunState.SUCCEEDED);
    }

    @Test
    void marksRunFailedWhenRunConfigCannotBeWritten() throws Exception {
        Path runConfigFile = tempDir.resolve("run-config-file");
        Files.writeString(runConfigFile, "not a directory");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("console.sqlite"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new WorkflowRunSchema(jdbcTemplate).initialize();
        WorkflowRunRepository repository = new WorkflowRunRepository(jdbcTemplate);
        EventStreamService streamService = new EventStreamService();
        WorkflowEventSink eventSink = new WorkflowEventSink(repository, streamService);
        OpenCodeRunnerProperties runnerProperties = new OpenCodeRunnerProperties();
        TaskConsoleProperties consoleProperties = new TaskConsoleProperties();
        consoleProperties.setRunConfigDir(runConfigFile);
        WorkflowExecutionService service = new WorkflowExecutionService(
                new ChainCatalog(new DefaultResourceLoader(), runnerProperties, List.of(blockingChain(new CountDownLatch(0), new CountDownLatch(0)))),
                repository,
                eventSink,
                new RunConfigWriter(consoleProperties),
                runnerProperties
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submit(new WorkflowRunSubmission(
                        "demo-chain",
                        "full",
                        null,
                        null,
                        null,
                        Map.of("value", "edited"),
                        null
                )))
                .isInstanceOf(Exception.class);

        assertThat(repository.listRuns()).hasSize(1);
        assertThat(repository.listRuns().get(0).state()).isEqualTo(RunState.FAILED);
        assertThat(repository.listEvents(repository.listRuns().get(0).id()))
                .extracting(WorkflowRunEvent::eventType)
                .contains("FAILED");
    }

    private WorkflowChain blockingChain(CountDownLatch started, CountDownLatch release) {
        return new WorkflowChain() {
            @Override
            public String id() {
                return "demo-chain";
            }

            @Override
            public void run(WorkflowRunRequest request) throws Exception {
                started.countDown();
                release.await(3, TimeUnit.SECONDS);
            }
        };
    }

    private Fixture fixture(List<WorkflowChain> chains) throws Exception {
        return fixture(chains, "classpath:chains");
    }

    private Fixture fixture(List<WorkflowChain> chains, String configDir) throws Exception {
        OpenCodeRunnerProperties runnerProperties = new OpenCodeRunnerProperties();
        runnerProperties.setConfigDir(configDir);
        return fixture(chains, runnerProperties);
    }

    private Fixture fixture(List<WorkflowChain> chains, OpenCodeRunnerProperties runnerProperties) throws Exception {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve("console.sqlite"));
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new WorkflowRunSchema(jdbcTemplate).initialize();
        WorkflowRunRepository repository = new WorkflowRunRepository(jdbcTemplate);
        EventStreamService streamService = new EventStreamService();
        WorkflowEventSink eventSink = new WorkflowEventSink(repository, streamService);
        TaskConsoleProperties consoleProperties = new TaskConsoleProperties();
        consoleProperties.setRunConfigDir(tempDir.resolve("run-configs"));
        ChainCatalog catalog = new ChainCatalog(new DefaultResourceLoader(), runnerProperties, chains);
        WorkflowExecutionService service = new WorkflowExecutionService(catalog, repository, eventSink,
                new RunConfigWriter(consoleProperties), runnerProperties);
        return new Fixture(repository, service);
    }

    private void awaitState(WorkflowRunRepository repository, long runId, RunState expected) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (repository.findRun(runId).orElseThrow().state() == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(repository.findRun(runId).orElseThrow().state()).isEqualTo(expected);
    }

    private record Fixture(WorkflowRunRepository repository, WorkflowExecutionService service) {
    }

    private static class CapturingChain implements WorkflowChain {
        private String configDir;

        private CapturingChain(OpenCodeRunnerProperties properties) {
        }

        @Override
        public String id() {
            return "demo-chain";
        }

        @Override
        public void run(WorkflowRunRequest request) {
            configDir = request.configDir();
        }
    }
}
