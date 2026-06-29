package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.preparation.CommandExecutor;
import com.sonnet.wyf.gitreport.preparation.CommentLineCounter;
import com.sonnet.wyf.gitreport.preparation.GitReportPreparation;
import com.sonnet.wyf.gitreport.preparation.GitStatsCollector;
import com.sonnet.wyf.gitreport.preparation.ReportPreparationWriter;
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
    void buildsWeeklyEvidenceFromFreshGitStats() throws Exception {
        Path repo = writeGitRepo();
        WeeklyEngineeringReportProperties properties = properties(repo, tempDir.resolve("weekly"));

        Path evidencePath = newBuilder().build(properties, LocalDate.of(2026, 6, 26));

        Map<String, Object> evidence = readMap(evidencePath);
        assertThat(evidence.get("schema_version")).isEqualTo("weekly-engineering-report/v1");
        assertThat(evidence).containsKeys("week", "project", "source_runs", "project_weekly", "team_risk", "people", "risks", "action_items", "data_quality");
        assertThat((Map<String, Object>) evidence.get("week"))
                .containsEntry("start", "2000-01-01")
                .containsEntry("end", "2099-12-31")
                .containsEntry("label", "test-week");

        Map<String, Object> sourceRuns = (Map<String, Object>) evidence.get("source_runs");
        assertThat((Map<String, Object>) sourceRuns.get("weekly_git"))
                .containsEntry("status", "generated");
        assertThat(sourceRuns).doesNotContainKeys("git_report", "smartesb_rewrite_review", "smartesb_code_reader");
        assertThat(Path.of(((Map<String, Object>) sourceRuns.get("weekly_git")).get("summary_json").toString())).exists();

        Map<String, Object> projectWeekly = (Map<String, Object>) evidence.get("project_weekly");
        assertThat((List<Map<String, Object>>) projectWeekly.get("completed_scope"))
                .extracting(row -> row.get("name"))
                .contains("src/main/java/Foo.java");

        Map<String, Object> teamRisk = (Map<String, Object>) evidence.get("team_risk");
        assertThat((List<Map<String, Object>>) teamRisk.get("contribution_distribution"))
                .extracting(row -> row.get("author_key"))
                .containsExactly("author-001-alice-alice-example-com");

        List<Map<String, Object>> people = (List<Map<String, Object>>) evidence.get("people");
        assertThat(people).hasSize(1);
        assertThat(people.get(0).get("author_key")).isEqualTo("author-001-alice-alice-example-com");
        assertThat((List<Map<String, Object>>) people.get(0).get("contribution_highlights"))
                .singleElement()
                .satisfies(highlight -> assertThat((List<String>) highlight.get("evidence_refs"))
                        .allMatch(ref -> ref.startsWith("git:author-001-alice-alice-example-com:")));
        assertThat((Map<String, Object>) evidence.get("data_quality")).containsEntry("status", "clean");
    }

    private WeeklyEvidenceBuilder newBuilder() {
        return new WeeklyEvidenceBuilder(objectMapper, new GitReportPreparation(
                new GitStatsCollector(new CommandExecutor(), new CommentLineCounter(), new WorkloadScoreCalculator(), objectMapper),
                new ReportPreparationWriter(objectMapper)
        ));
    }

    private WeeklyEngineeringReportProperties properties(Path repo, Path out) {
        WeeklyEngineeringReportProperties properties = new WeeklyEngineeringReportProperties();
        properties.getProject().setId("upfs-production");
        properties.getProject().setName("UPFS Production");
        properties.getProject().setRepo(repo);
        properties.getProject().setRevision("HEAD");
        properties.getPaths().setOut(out);
        properties.getWeek().setStart(LocalDate.of(2000, 1, 1));
        properties.getWeek().setEnd(LocalDate.of(2099, 12, 31));
        properties.getWeek().setLabel("test-week");
        properties.getGit().setExclude(List.of("target/**", "*.lock"));
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
