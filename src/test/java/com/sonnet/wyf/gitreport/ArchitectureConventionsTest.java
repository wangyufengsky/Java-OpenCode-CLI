package com.sonnet.wyf.gitreport;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureConventionsTest {
    @Test
    void springBootEntryPointDoesNotOwnModuleWiring() throws Exception {
        String code = read("src/main/java/com/sonnet/wyf/gitreport/GitReportApplication.java");

        assertThat(code).doesNotContain("@Bean");
        assertThat(code).doesNotContain("new GitReportPreparation");
        assertThat(code).doesNotContain("new GitReportOrchestrator");
        assertThat(code).doesNotContain("new OpenCodeServerTaskRunner");
        assertThat(code).contains("SpringApplication.exit(context)");
    }

    @Test
    void orchestrationUsesSpringExecutorAndRunnerOwnsScheduledWaiter() throws Exception {
        String orchestrator = read("src/main/java/com/sonnet/wyf/gitreport/orchestration/GitReportOrchestrator.java");
        String taskRunner = read("src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerTaskRunner.java");

        assertThat(orchestrator).contains("AsyncTaskExecutor");
        assertThat(orchestrator).doesNotContain("Executors.");
        assertThat(orchestrator).doesNotContain("TimeUnit.SECONDS.sleep");
        assertThat(taskRunner).contains("ScheduledProbeWaiter");
        assertThat(taskRunner).doesNotContain("TimeUnit.SECONDS.sleep");
    }

    @Test
    void serverStartupHealthCheckUsesScheduledWaiter() throws Exception {
        String code = read("src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerManager.java");

        assertThat(code).contains("ScheduledProbeWaiter");
        assertThat(code).doesNotContain("TimeUnit.MILLISECONDS.sleep");
    }

    @Test
    void openCodeClientUsesOpenCode117ApiContractOnly() throws Exception {
        String client = read("src/main/java/com/sonnet/wyf/gitreport/opencode/OpenCodeServerClient.java");
        String application = read("src/main/resources/application.yml");
        String example = read("src/main/resources/application-example.yml");

        assertThat(client).contains("/session");
        assertThat(client).contains("X-OpenCode-Directory");
        assertThat(client).contains("/prompt_async");
        assertThat(client).contains("/message");
        assertThat(client).doesNotContain("/api/session");
        assertThat(client).doesNotContain("?directory=");
        assertThat(client).contains("body.put(\"model\", sessionModelObject");
        assertThat(client).contains("result.put(\"providerID\"");
        assertThat(client).contains("result.put(\"id\"");
        assertThat(client).contains("result.put(\"modelID\"");
        assertThat(application).doesNotContain("\n    model:");
        assertThat(example).doesNotContain("\n    model:");
    }

    @Test
    void statsAndPromptModulesUseInjectedDependencies() throws Exception {
        String stats = read("src/main/java/com/sonnet/wyf/gitreport/preparation/GitStatsCollector.java");
        String prompt = read("src/main/java/com/sonnet/wyf/gitreport/prompt/PromptBuilder.java");

        assertThat(stats).doesNotContain("new ObjectMapper");
        assertThat(stats).doesNotContain("new WorkloadScoreCalculator");
        assertThat(prompt).contains("ResourceLoader");
        assertThat(prompt).doesNotContain("Thread.currentThread().getContextClassLoader()");
    }

    @Test
    void fileScopePatternsAreCompiledOnceAndRuntimeOutputIsIgnored() throws Exception {
        String filter = read("src/main/java/com/sonnet/wyf/gitreport/preparation/FileScopeFilter.java");
        String gitignore = read(".gitignore");

        assertThat(filter).contains("List<Pattern> includePatterns");
        assertThat(filter).contains("List<Pattern> excludePatterns");
        assertThat(gitignore).contains("D:/");
    }

    @Test
    void runtimePromptPacksUseProgramScheduledSessionsInsteadOfMainSubAgentTerms() throws Exception {
        for (String path : List.of(
                "src/main/resources/git-report-prompt-pack",
                "src/main/resources/smartesb-rewrite-code-review-prompt-pack"
        )) {
            try (var files = Files.walk(Path.of(path))) {
                files.filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".md"))
                        .forEach(file -> {
                            try {
                                assertThat(Files.readString(file))
                                        .as(file.toString())
                                        .doesNotContain("主 agent")
                                        .doesNotContain("主agent")
                                        .doesNotContain("子 agent")
                                        .doesNotContain("子agent")
                                        .doesNotContain("main agent")
                                        .doesNotContain("main-agent")
                                        .doesNotContain("sub agent")
                                        .doesNotContain("sub-agent")
                                        .doesNotContain("subagent")
                                        .doesNotContain("child agent")
                                        .doesNotContain("child-agent");
                            } catch (Exception exception) {
                                throw new RuntimeException(exception);
                            }
                        });
            }
        }
    }

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
