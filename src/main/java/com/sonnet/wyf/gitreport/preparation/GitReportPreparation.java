package com.sonnet.wyf.gitreport.preparation;

import com.sonnet.wyf.gitreport.GitReportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class GitReportPreparation {
    private static final Logger log = LoggerFactory.getLogger(GitReportPreparation.class);

    private final GitStatsCollector statsCollector;
    private final StaticAnalysisAttributor staticAnalysisAttributor;
    private final ReportPreparationWriter writer;

    public GitReportPreparation(GitStatsCollector statsCollector, ReportPreparationWriter writer) {
        this(statsCollector, null, writer);
    }

    public GitReportPreparation(GitStatsCollector statsCollector, StaticAnalysisAttributor staticAnalysisAttributor, ReportPreparationWriter writer) {
        this.statsCollector = statsCollector;
        this.staticAnalysisAttributor = staticAnalysisAttributor;
        this.writer = writer;
    }

    public void prepare(GitReportProperties properties) throws Exception {
        validate(properties);
        log.info("Preparing git contribution data: repo={}, out={}, since={}, until={}, revision={}, includeMerges={}",
                properties.getPaths().getRepo().toAbsolutePath().normalize(),
                properties.getPaths().getOut().toAbsolutePath().normalize(),
                properties.getGit().getSince(),
                properties.getGit().getUntil(),
                properties.getGit().getRevision(),
                properties.getGit().isIncludeMerges());
        Map<String, Object> data = statsCollector.collect(properties);
        if (staticAnalysisAttributor == null) {
            markStaticAnalysisDisabled(data);
        } else {
            staticAnalysisAttributor.apply(properties, data);
        }
        writer.write(properties.getPaths().getOut().toAbsolutePath().normalize(), data, properties.getDetailInput());
    }

    @SuppressWarnings("unchecked")
    private void markStaticAnalysisDisabled(Map<String, Object> data) {
        Object authors = data.get("authors");
        if (authors instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Map<?, ?> map) {
                    ((Map<String, Object>) map).put("scanner_status", Map.of("enabled", false));
                }
            }
        }
    }

    private void validate(GitReportProperties properties) {
        if (properties.getGit().getSince() == null || properties.getGit().getUntil() == null) {
            throw new IllegalArgumentException("git-report.git.since and git-report.git.until are required");
        }
        if (properties.getGit().getSince().isAfter(properties.getGit().getUntil())) {
            throw new IllegalArgumentException("git-report.git.since must be earlier than or equal to git-report.git.until");
        }
    }
}
