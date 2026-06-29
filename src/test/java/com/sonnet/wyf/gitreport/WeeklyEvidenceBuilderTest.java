package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.preparation.CommandExecutor;
import com.sonnet.wyf.gitreport.preparation.CommentLineCounter;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEngineeringReportProperties;
import com.sonnet.wyf.gitreport.workflow.weekly.WeeklyEvidenceBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyEvidenceBuilderTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void buildsWeeklyGitEvidenceAndReviewBatchesWithoutGitReportArtifacts() throws Exception {
        Path repo = writeGitRepo();
        WeeklyEngineeringReportProperties properties = properties(repo, tempDir.resolve("weekly"));

        Path evidencePath = newBuilder().build(properties, LocalDate.of(2026, 6, 26));

        Map<String, Object> evidence = readMap(evidencePath);
        assertThat(evidence.get("schema_version")).isEqualTo("weekly-engineering-report/v1");
        assertThat(evidence).containsKeys("week", "project", "source_runs", "weekly_git", "review_batches", "data_quality");
        assertThat((Map<String, Object>) evidence.get("week"))
                .containsEntry("start", "2000-01-01")
                .containsEntry("end", "2099-12-31")
                .containsEntry("label", "2000-01-01_to_2099-12-31");

        Map<String, Object> sourceRuns = (Map<String, Object>) evidence.get("source_runs");
        assertThat((Map<String, Object>) sourceRuns.get("weekly_git"))
                .containsEntry("status", "generated");
        assertThat(sourceRuns).doesNotContainKeys("git_report", "smartesb_rewrite_review", "smartesb_code_reader");
        assertThat(Path.of(((Map<String, Object>) sourceRuns.get("weekly_git")).get("weekly_git_evidence_json").toString())).exists();
        assertThat(Path.of(((Map<String, Object>) sourceRuns.get("weekly_git")).get("review_batches_json").toString())).exists();
        assertThat(properties.getPaths().getOut().resolve("sources/weekly-git/index_inputs.json")).doesNotExist();
        assertThat(properties.getPaths().getOut().resolve("sources/weekly-git/reports")).doesNotExist();

        Map<String, Object> weeklyGit = (Map<String, Object>) evidence.get("weekly_git");
        assertThat((List<Map<String, Object>>) weeklyGit.get("authors"))
                .extracting(row -> row.get("author_key"))
                .containsExactly("author-001-alice-alice-example-com");

        List<Map<String, Object>> batches = (List<Map<String, Object>>) evidence.get("review_batches");
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).containsEntry("batch_id", "review-batch-001-src-main-java-foo-java");
        assertThat((List<Map<String, Object>>) batches.get(0).get("changed_regions"))
                .singleElement()
                .satisfies(region -> {
                    assertThat(region).containsEntry("author_key", "author-001-alice-alice-example-com");
                    assertThat(region).containsEntry("author", "Alice <alice@example.com>");
                    assertThat(region.get("commit").toString()).isNotBlank();
                    assertThat(region).containsEntry("file", "src/main/java/Foo.java");
                    assertThat(region.get("hunk").toString()).contains("return value == null");
                });

        assertThat((Map<String, Object>) evidence.get("data_quality")).containsEntry("status", "clean");
    }

    private WeeklyEvidenceBuilder newBuilder() {
        return new WeeklyEvidenceBuilder(objectMapper, new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper));
    }

    private WeeklyEngineeringReportProperties properties(Path repo, Path out) {
        WeeklyEngineeringReportProperties properties = new WeeklyEngineeringReportProperties();
        properties.getProject().setId("upfs-production");
        properties.getProject().setName("UPFS Production");
        properties.getProject().setRepo(repo);
        properties.getProject().setRevision("HEAD");
        properties.getPaths().setOut(out);
        properties.setStartday(LocalDate.of(2000, 1, 1));
        properties.setEndday(LocalDate.of(2099, 12, 31));
        properties.getGit().setExclude(List.of("target/**", "*.lock"));
        properties.getReview().setMaxRegionsPerBatch(8);
        properties.getReview().setMaxHunkLines(24);
        return properties;
    }

    private Path writeGitRepo() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("src/main/java"));
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("src/main/java/Foo.java"), """
                class Foo {
                    String parse(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """);
        GitTestSupport.run(repo, "git", "add", ".");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add parser");
        return repo;
    }

    private Map<String, Object> readMap(Path path) throws Exception {
        return objectMapper.readValue(path.toFile(), new TypeReference<>() {});
    }
}
