package com.sonnet.wyf.gitreport;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
    void orchestrationUsesSpringExecutorAndScheduledWaiter() throws Exception {
        String code = read("src/main/java/com/sonnet/wyf/gitreport/orchestration/GitReportOrchestrator.java");

        assertThat(code).contains("AsyncTaskExecutor");
        assertThat(code).contains("ScheduledProbeWaiter");
        assertThat(code).doesNotContain("Executors.");
        assertThat(code).doesNotContain("TimeUnit.SECONDS.sleep");
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

        assertThat(client).contains("/api/session");
        assertThat(client).contains("/prompt");
        assertThat(client).contains("/message?order=asc&limit=100");
        assertThat(client).doesNotContain("prompt_async");
        assertThat(client).doesNotContain("x-opencode-directory");
        assertThat(client).contains("result.put(\"providerID\"");
        assertThat(client).contains("result.put(\"id\"");
        assertThat(client).doesNotContain("modelID");
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

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
