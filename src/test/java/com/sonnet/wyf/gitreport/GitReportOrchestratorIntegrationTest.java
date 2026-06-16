package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitReportOrchestratorIntegrationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void orchestratesPreparationAuthorWorkerQualityScoresAndSynthesisWithFakeOpenCode() throws Exception {
        Path repo = tempDir.resolve("repo");
        Path out = tempDir.resolve("out");
        Files.createDirectories(repo);
        GitTestSupport.run(repo, "git", "init", "-q");
        GitTestSupport.run(repo, "git", "config", "user.name", "Alice");
        GitTestSupport.run(repo, "git", "config", "user.email", "alice@example.com");
        Files.writeString(repo.resolve("Demo.java"), "class Demo {\n  int a = 1;\n}\n");
        GitTestSupport.run(repo, "git", "add", "Demo.java");
        GitTestSupport.run(repo, "git", "commit", "-q", "-m", "add demo");

        Path fakeOpenCode = tempDir.resolve("fake-opencode.sh");
        Files.writeString(fakeOpenCode, """
                #!/bin/sh
                prompt=""
                while [ "$#" -gt 0 ]; do
                  if [ "$1" = "--file" ]; then
                    prompt="$2"
                    shift 2
                  elif echo "$1" | grep -q '^--file='; then
                    prompt="${1#--file=}"
                    shift
                  else
                    shift
                  fi
                done
                if grep -q '^detail_json:' "$prompt"; then
                  detail=$(awk -F': ' '/^detail_json: /{print $2}' "$prompt" | tail -1)
                  python3 - "$detail" <<'PY'
                import json, pathlib, sys
                detail = json.loads(pathlib.Path(sys.argv[1]).read_text())
                pathlib.Path(detail["output"]["person_report_md"]).write_text("个人报告内容\\n", encoding="utf-8")
                pathlib.Path(detail["output"]["quality_summary_json"]).write_text(json.dumps({
                  "author": detail["author"],
                  "status": "completed",
                  "findings": [],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [],
                  "unverified": [],
                  "summary": "无"
                }, ensure_ascii=False), encoding="utf-8")
                PY
                else
                  index_inputs=$(awk -F': ' '/^index_inputs_json: /{print $2}' "$prompt" | tail -1)
                  python3 - "$index_inputs" <<'PY'
                import json, pathlib, sys
                index_inputs = json.loads(pathlib.Path(sys.argv[1]).read_text())
                pathlib.Path(index_inputs["final_report"]).write_text("最终中文总报告\\n", encoding="utf-8")
                PY
                fi
                echo '{"type":"done"}'
                """);
        fakeOpenCode.toFile().setExecutable(true);

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getPaths().setOpencodeBin(fakeOpenCode.toString());
        properties.getGit().setSince(LocalDate.of(2000, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2099, 12, 31));

        GitReportOrchestrator orchestrator = new GitReportOrchestrator(
                new GitReportPreparation(new GitStatsCollector(new CommandExecutor(), new CommentLineCounter()), new ReportPreparationWriter(objectMapper)),
                objectMapper,
                new PromptBuilder(),
                new OpenCodeCommandBuilder(),
                new OpenCodeProcessRunner(),
                new AuthorOutputValidator(objectMapper),
                new QualityScoresWriter(objectMapper, new QualityScoreCalculator(), new WorkloadScoreCalculator()),
                new RunStatusRepository(objectMapper)
        );
        orchestrator.run(properties);

        assertThat(out.resolve("quality-scores.json")).exists();
        assertThat(out.resolve("code-contribution-report.md")).hasContent("最终中文总报告\n");
        JsonNode qualityScores = objectMapper.readTree(out.resolve("quality-scores.json").toFile());
        assertThat(qualityScores.get("rankings")).hasSize(1);
        assertThat(out.resolve("runs/author-001-alice-alice-example-com/status.json")).exists();
        assertThat(out.resolve("runs/synthesis/status.json")).exists();
    }

    @Test
    void synthesisOnlyUsesExistingAuthorOutputsWithoutPreparationOrAuthorWorkers() throws Exception {
        Path repo = tempDir.resolve("repo-synthesis-only");
        Path out = tempDir.resolve("out-synthesis-only");
        Files.createDirectories(repo);
        Files.createDirectories(out.resolve("reports/author-001-alice"));
        Path personReport = out.resolve("reports/author-001-alice/person-report.md");
        Path qualitySummary = out.resolve("reports/author-001-alice/quality-summary.json");
        Files.writeString(personReport, "个人报告内容\n");
        Files.writeString(qualitySummary, """
                {
                  "author": "Alice <alice@example.com>",
                  "status": "completed",
                  "findings": [],
                  "positive_signals": [],
                  "risk_signals": [],
                  "code_snippets": [],
                  "unverified": [],
                  "summary": "无"
                }
                """);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("summary.json").toFile(), Map.of(
                "ranking", List.of(Map.of(
                        "author_key", "author-001-alice",
                        "author", "Alice <alice@example.com>",
                        "rank", 1,
                        "base_workload_score", 100.0
                ))
        ));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("index_inputs.json").toFile(), Map.of(
                "final_report", out.resolve("code-contribution-report.md").toString(),
                "tasks", List.of(Map.of(
                        "author_key", "author-001-alice",
                        "author", "Alice <alice@example.com>",
                        "rank", 1,
                        "report_md", personReport.toString(),
                        "quality_summary_json", qualitySummary.toString()
                ))
        ));
        Files.writeString(out.resolve("code-contribution-report.md"), GitReportConstants.REPORT_MARKER + "\n");

        Path authorWorkerCalled = tempDir.resolve("author-worker-called.txt");
        Path fakeOpenCode = tempDir.resolve("fake-opencode-synthesis.sh");
        Files.writeString(fakeOpenCode, """
                #!/bin/sh
                prompt=""
                while [ "$#" -gt 0 ]; do
                  if echo "$1" | grep -q '^--file='; then
                    prompt="${1#--file=}"
                  fi
                  shift
                done
                if grep -q '^detail_json:' "$prompt"; then
                  printf called > "%s"
                  exit 2
                fi
                index_inputs=$(awk -F': ' '/^index_inputs_json: /{print $2}' "$prompt" | tail -1)
                python3 - "$index_inputs" <<'PY'
                import json, pathlib, sys
                index_inputs = json.loads(pathlib.Path(sys.argv[1]).read_text())
                pathlib.Path(index_inputs["final_report"]).write_text("补跑后的最终中文总报告\\n", encoding="utf-8")
                PY
                """.formatted(authorWorkerCalled));
        fakeOpenCode.toFile().setExecutable(true);

        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getPaths().setOut(out);
        properties.getPaths().setOpencodeBin(fakeOpenCode.toString());
        GitReportPreparation preparation = new GitReportPreparation(null, null) {
            @Override
            void prepare(GitReportProperties ignored) {
                throw new AssertionError("preparation must not run in synthesis-only mode");
            }
        };
        GitReportOrchestrator orchestrator = new GitReportOrchestrator(
                preparation,
                objectMapper,
                new PromptBuilder(),
                new OpenCodeCommandBuilder(),
                new OpenCodeProcessRunner(),
                new AuthorOutputValidator(objectMapper),
                new QualityScoresWriter(objectMapper, new QualityScoreCalculator(), new WorkloadScoreCalculator()),
                new RunStatusRepository(objectMapper)
        );

        orchestrator.runSynthesisOnly(properties);

        assertThat(authorWorkerCalled).doesNotExist();
        assertThat(out.resolve("quality-scores.json")).exists();
        assertThat(out.resolve("code-contribution-report.md")).hasContent("补跑后的最终中文总报告\n");
    }
}
