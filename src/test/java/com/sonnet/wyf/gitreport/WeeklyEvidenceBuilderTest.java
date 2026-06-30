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
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        assertThat(Path.of(((Map<String, Object>) sourceRuns.get("weekly_git")).get("review_units_json").toString())).exists();
        assertThat(properties.getPaths().getOut().resolve("sources/weekly-git/index_inputs.json")).doesNotExist();
        assertThat(properties.getPaths().getOut().resolve("sources/weekly-git/reports")).doesNotExist();

        Map<String, Object> weeklyGit = (Map<String, Object>) evidence.get("weekly_git");
        assertThat((List<Map<String, Object>>) weeklyGit.get("authors"))
                .extracting(row -> row.get("author_key"))
                .containsExactly("author-001-alice-alice-example-com");

        List<Map<String, Object>> batches = (List<Map<String, Object>>) evidence.get("review_batches");
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0))
                .containsEntry("batch_id", "review-unit-001-src-main-java-author-001-alice-alice-example-com")
                .containsEntry("unit_id", "review-unit-001-src-main-java-author-001-alice-alice-example-com");
        assertThat((Map<String, Object>) batches.get(0).get("group"))
                .containsEntry("module", "src/main/java")
                .containsEntry("author_key", "author-001-alice-alice-example-com");
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

    @Test
    void contractsReviewTasksByModuleAuthorAndCapacityInsteadOfSingleFileBatches() throws Exception {
        WeeklyEngineeringReportProperties properties = properties(tempDir.resolve("repo"), tempDir.resolve("weekly"));
        properties.getReview().getGrouping().setMaxRegionsPerTask(4);
        properties.getReview().getGrouping().setMaxFilesPerTask(10);
        properties.getReview().getGrouping().setMaxHunkCharsPerTask(10_000);
        properties.getReview().getGrouping().setMaxCommitsPerTask(10);

        Path evidencePath = new WeeklyEvidenceBuilder(objectMapper, fakeCollector(syntheticWeeklyGit())).build(properties, LocalDate.of(2026, 6, 26));

        Map<String, Object> evidence = readMap(evidencePath);
        List<Map<String, Object>> batches = (List<Map<String, Object>>) evidence.get("review_batches");
        assertThat(batches).hasSize(2);
        assertThat(batches).extracting(row -> row.get("batch_id"))
                .containsExactly(
                        "review-unit-001-upfs-cup-src-main-java-com-spdb-upfs-cup-service-esf-author-001-alice-alice-example-com",
                        "review-unit-002-upfs-cup-src-main-resources-mapper-author-001-alice-alice-example-com"
                );
        assertThat((List<Map<String, Object>>) batches.get(0).get("changed_regions"))
                .extracting(row -> row.get("file"))
                .containsExactly(
                        "upfs-cup/src/main/java/com/spdb/upfs/cup/service/esf/CnsA.java",
                        "upfs-cup/src/main/java/com/spdb/upfs/cup/service/esf/CnsB.java",
                        "upfs-cup/src/main/java/com/spdb/upfs/cup/service/esf/CnsC.java"
                );
        assertThat((Map<String, Object>) batches.get(0).get("group"))
                .containsEntry("module", "upfs-cup/src/main/java/com/spdb/upfs/cup/service/esf")
                .containsEntry("author_key", "author-001-alice-alice-example-com")
                .containsEntry("region_count", 3)
                .containsEntry("file_count", 3);
        assertThat(Path.of((String) batches.get(0).get("input_json")).toString()).contains("review-units");
        assertThat(properties.getPaths().getOut().resolve("review-units.json")).exists();
    }

    private WeeklyEvidenceBuilder newBuilder() {
        return new WeeklyEvidenceBuilder(objectMapper, new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper));
    }

    private GitStatsCollector fakeCollector(Map<String, Object> weeklyGit) {
        return new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper) {
            @Override
            public Map<String, Object> collect(com.sonnet.wyf.gitreport.GitReportProperties properties) {
                return weeklyGit;
            }
        };
    }

    private Map<String, Object> syntheticWeeklyGit() {
        List<Map<String, Object>> regions = new ArrayList<>();
        regions.add(region("a1", "upfs-cup/src/main/java/com/spdb/upfs/cup/service/esf/CnsA.java", 10));
        regions.add(region("a2", "upfs-cup/src/main/java/com/spdb/upfs/cup/service/esf/CnsB.java", 20));
        regions.add(region("a3", "upfs-cup/src/main/java/com/spdb/upfs/cup/service/esf/CnsC.java", 30));
        regions.add(region("a4", "upfs-cup/src/main/resources/mapper/CnsMapper.xml", 40));
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("rank", 1);
        author.put("author", "Alice <alice@example.com>");
        author.put("commit_count", 4);
        author.put("non_comment_churn", 80);
        author.put("workload_score", 100.0);
        author.put("top_files", List.of());
        author.put("commits", List.of());
        author.put("changed_regions", regions);
        return new LinkedHashMap<>(Map.of(
                "totals", Map.of("commit_count", 4),
                "authors", List.of(author)
        ));
    }

    private Map<String, Object> region(String commit, String file, int line) {
        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("commit", commit),
                Map.entry("short_hash", commit),
                Map.entry("file", file),
                Map.entry("line_start", line),
                Map.entry("line_end", line + 1),
                Map.entry("hunk", "@@ -" + line + " +" + line + " @@\n+changed();")
        ));
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
