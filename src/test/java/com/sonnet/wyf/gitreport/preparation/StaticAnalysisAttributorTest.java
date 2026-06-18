package com.sonnet.wyf.gitreport.preparation;

import com.sonnet.wyf.gitreport.GitReportProperties;
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

class StaticAnalysisAttributorTest {
    @TempDir
    Path tempDir;

    @Test
    void attributesScannerFindingOnlyWhenLineOverlapsOwnedHunk() throws Exception {
        Path repo = tempDir.resolve("repo");
        Files.createDirectories(repo.resolve("target/site"));
        Files.writeString(repo.resolve("Demo.java"), """
                class Demo {
                  void a() {
                    System.out.println("unsafe");
                  }
                }
                """);
        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(repo);
        properties.getGit().setSince(LocalDate.of(2026, 1, 1));
        properties.getGit().setUntil(LocalDate.of(2026, 1, 2));
        properties.getStaticAnalysis().setSpotbugsEnabled(false);

        Map<String, Object> alice = author("author-001-alice", "Alice <alice@example.com>", "Demo.java", 3, 3);
        Map<String, Object> bob = author("author-002-bob", "Bob <bob@example.com>", "Demo.java", 10, 10);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("authors", List.of(alice, bob));

        new StaticAnalysisAttributor(new PmdReportCommandExecutor()).apply(properties, data);

        assertThat((List<?>) alice.get("attributed_findings")).hasSize(1);
        Map<?, ?> finding = (Map<?, ?>) ((List<?>) alice.get("attributed_findings")).get(0);
        assertThat(finding.get("source")).isEqualTo("scanner");
        assertThat(finding.get("attribution")).isEqualTo("owned_hunk");
        assertThat(finding.get("owned_hunk_id")).isEqualTo("h-alice");
        assertThat((List<?>) alice.get("code_snippets")).hasSize(1);

        assertThat((List<?>) bob.get("attributed_findings")).isEmpty();
        assertThat((List<?>) bob.get("context_findings")).hasSize(1);
    }

    private Map<String, Object> author(String key, String name, String file, int start, int end) {
        Map<String, Object> author = new LinkedHashMap<>();
        author.put("author_key", key);
        author.put("author", name);
        author.put("unique_files", List.of(file));
        author.put("owned_hunks", List.of(Map.of(
                "hunk_id", key.contains("alice") ? "h-alice" : "h-bob",
                "file", file,
                "line_start", start,
                "line_end", end
        )));
        author.put("attributed_findings", new ArrayList<>());
        author.put("context_findings", new ArrayList<>());
        author.put("code_snippets", new ArrayList<>());
        return author;
    }

    private static class PmdReportCommandExecutor extends CommandExecutor {
        @Override
        String run(Path cwd, String... command) throws java.io.IOException {
            Files.writeString(cwd.resolve("target/site/pmd.xml"), """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <pmd>
                      <file name="Demo.java">
                        <violation beginline="3" endline="3" priority="2" rule="SystemPrintln">Avoid console output</violation>
                      </file>
                    </pmd>
                    """);
            return "";
        }
    }
}
