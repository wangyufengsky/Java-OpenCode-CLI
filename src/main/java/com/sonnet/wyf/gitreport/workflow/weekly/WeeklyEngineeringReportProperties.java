package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sonnet.wyf.gitreport.GitReportProperties;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;

public class WeeklyEngineeringReportProperties {
    private final Project project = new Project();
    private final Paths paths = new Paths();
    private final Week week = new Week();
    private LocalDate startday;
    private LocalDate endday;
    private final Git git = new Git();
    private final Review review = new Review();
    private final GitReportProperties.OpenCode opencode = new GitReportProperties.OpenCode();

    public Project getProject() {
        return project;
    }

    public Paths getPaths() {
        return paths;
    }

    public Week getWeek() {
        return week;
    }

    public LocalDate getStartday() {
        return startday;
    }

    public void setStartday(LocalDate startday) {
        this.startday = startday;
    }

    public LocalDate getEndday() {
        return endday;
    }

    public void setEndday(LocalDate endday) {
        this.endday = endday;
    }

    public Git getGit() {
        return git;
    }

    public Review getReview() {
        return review;
    }

    public GitReportProperties.OpenCode getOpencode() {
        return opencode;
    }

    public LocalDate effectiveWeekStart(LocalDate runDate) {
        validateExplicitPeriod();
        if (startday != null) {
            return startday;
        }
        if (week.start != null) {
            return week.start;
        }
        LocalDate base = runDate == null ? LocalDate.now() : runDate;
        return base.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    }

    public LocalDate effectiveWeekEnd(LocalDate runDate) {
        validateExplicitPeriod();
        if (endday != null) {
            return endday;
        }
        if (week.end != null) {
            return week.end;
        }
        return effectiveWeekStart(runDate).plusDays(6);
    }

    public String effectiveWeekLabel(LocalDate runDate) {
        validateExplicitPeriod();
        if (week.label != null && !week.label.isBlank()) {
            return week.label;
        }
        LocalDate start = effectiveWeekStart(runDate);
        LocalDate end = effectiveWeekEnd(runDate);
        if (startday != null || endday != null || week.start != null || week.end != null) {
            return start + "_to_" + end;
        }
        WeekFields fields = WeekFields.ISO;
        return "%d-W%02d".formatted(start.get(fields.weekBasedYear()), start.get(fields.weekOfWeekBasedYear()));
    }

    private void validateExplicitPeriod() {
        if ((startday == null) != (endday == null)) {
            throw new IllegalArgumentException("weekly-engineering-report startday and endday must be configured together");
        }
        if (startday != null && endday.isBefore(startday)) {
            throw new IllegalArgumentException("weekly-engineering-report endday must not be before startday");
        }
    }

    public static class Project {
        private String id = "";
        private String name = "";
        private Path repo = Path.of(".");
        private String revision = "HEAD";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Path getRepo() {
            return repo;
        }

        public void setRepo(Path repo) {
            this.repo = repo;
        }

        public String getRevision() {
            return revision;
        }

        public void setRevision(String revision) {
            this.revision = revision;
        }
    }

    public static class Paths {
        private Path out = Path.of("weekly-report");

        public Path getOut() {
            return out;
        }

        public void setOut(Path out) {
            this.out = out;
        }
    }

    public static class Week {
        private LocalDate start;
        private LocalDate end;
        private String label;

        public LocalDate getStart() {
            return start;
        }

        public void setStart(LocalDate start) {
            this.start = start;
        }

        public LocalDate getEnd() {
            return end;
        }

        public void setEnd(LocalDate end) {
            this.end = end;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    public static class Git {
        private boolean includeMerges;
        private Path authorMap;
        private List<String> include = new ArrayList<>();
        private List<String> exclude = new ArrayList<>();

        @JsonProperty("include-merges")
        public boolean isIncludeMerges() {
            return includeMerges;
        }

        public void setIncludeMerges(boolean includeMerges) {
            this.includeMerges = includeMerges;
        }

        @JsonProperty("author-map")
        public Path getAuthorMap() {
            return authorMap;
        }

        public void setAuthorMap(Path authorMap) {
            this.authorMap = authorMap;
        }

        public List<String> getInclude() {
            return include;
        }

        public void setInclude(List<String> include) {
            this.include = include == null ? new ArrayList<>() : include;
        }

        public List<String> getExclude() {
            return exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = exclude == null ? new ArrayList<>() : exclude;
        }
    }

    public static class Review {
        private int maxRegionsPerBatch = 8;
        private int maxHunkLines = 24;
        private int concurrency = 3;

        @JsonProperty("max-regions-per-batch")
        public int getMaxRegionsPerBatch() {
            return maxRegionsPerBatch;
        }

        public void setMaxRegionsPerBatch(int maxRegionsPerBatch) {
            this.maxRegionsPerBatch = maxRegionsPerBatch;
        }

        @JsonProperty("max-hunk-lines")
        public int getMaxHunkLines() {
            return maxHunkLines;
        }

        public void setMaxHunkLines(int maxHunkLines) {
            this.maxHunkLines = maxHunkLines;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }
    }
}
