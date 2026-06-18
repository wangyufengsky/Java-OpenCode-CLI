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

    @Test
    void gitReportRuntimeHasNoStaticAnalysisAttributionResidue() throws Exception {
        List<String> roots = List.of(
                "pom.xml",
                "src/main/java/com/sonnet/wyf/gitreport",
                "src/main/resources/chains/git-code-contribution-report.yml",
                "src/main/resources/git-report-prompt-pack"
        );
        List<String> forbidden = List.of(
                "StaticAnalysisAttributor",
                "static-analysis",
                "StaticAnalysis",
                "pmd",
                "spotbugs",
                "scanner_status",
                "owned_hunks",
                "attributed_findings",
                "context_findings",
                "owned_hunk",
                "owned_hunk_id",
                "source=scanner"
        );

        for (String root : roots) {
            Path path = Path.of(root);
            if (Files.isRegularFile(path)) {
                assertNoForbiddenResidue(path, forbidden);
                continue;
            }
            try (var files = Files.walk(path)) {
                files.filter(Files::isRegularFile)
                        .forEach(file -> assertNoForbiddenResidue(file, forbidden));
            }
        }
    }

    private void assertNoForbiddenResidue(Path file, List<String> forbidden) {
        try {
            String text = Files.readString(file);
            for (String term : forbidden) {
                assertThat(text).as(file + " contains residue term " + term).doesNotContain(term);
            }
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }
}
