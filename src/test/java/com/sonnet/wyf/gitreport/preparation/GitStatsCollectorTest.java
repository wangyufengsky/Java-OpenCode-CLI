package com.sonnet.wyf.gitreport.preparation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.GitReportProperties;
import com.sonnet.wyf.gitreport.scoring.WorkloadScoreCalculator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitStatsCollectorTest {
    @Test
    void skipsHunkLevelParsingForLargeCommits() throws Exception {
        CapturingCommandExecutor executor = new CapturingCommandExecutor();
        GitStatsCollector collector = new GitStatsCollector(
                executor,
                new CommentLineCounter(),
                new WorkloadScoreCalculator(),
                new ObjectMapper()
        );
        GitReportProperties properties = new GitReportProperties();
        properties.getPaths().setRepo(Path.of("."));
        properties.getGit().setSince(LocalDate.of(2026, 7, 1));
        properties.getGit().setUntil(LocalDate.of(2026, 7, 9));

        Map<String, Object> data = collector.collect(properties);

        assertThat(executor.commands).noneSatisfy(command ->
                assertThat(command).contains("--unified=0"));
        assertThat(data.get("authors")).asList().hasSize(1);
        Map<?, ?> author = (Map<?, ?>) ((List<?>) data.get("authors")).get(0);
        assertThat(author.get("non_comment_added")).isEqualTo(60_001);
        assertThat(author.get("changed_regions")).asList().isEmpty();
    }

    private static final class CapturingCommandExecutor extends CommandExecutor {
        private final List<List<String>> commands = new ArrayList<>();

        @Override
        String run(Path cwd, List<String> command) {
            commands.add(List.copyOf(command));
            if (command.contains("rev-parse")) {
                return "true\n";
            }
            if (command.contains("log")) {
                return "865301b28894\u001fAlice\u001falice@example.com\u001f2026-07-07T14:51:18+08:00\u001fbig json change\n";
            }
            if (command.contains("--numstat")) {
                return "60001\t0\tHuge.java\n";
            }
            throw new AssertionError("unexpected command: " + command);
        }
    }
}
