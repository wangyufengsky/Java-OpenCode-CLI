package com.sonnet.wyf.gitreport.workflow.weekly;

import com.fasterxml.jackson.annotation.JsonProperty;

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
    private final Git git = new Git();
    private final DetailInput detailInput = new DetailInput();

    public Project getProject() {
        return project;
    }

    public Paths getPaths() {
        return paths;
    }

    public Week getWeek() {
        return week;
    }

    public Git getGit() {
        return git;
    }

    @JsonProperty("detail-input")
    public DetailInput getDetailInput() {
        return detailInput;
    }

    public LocalDate effectiveWeekStart(LocalDate runDate) {
        if (week.start != null) {
            return week.start;
        }
        LocalDate base = runDate == null ? LocalDate.now() : runDate;
        return base.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
    }

    public LocalDate effectiveWeekEnd(LocalDate runDate) {
        if (week.end != null) {
            return week.end;
        }
        return effectiveWeekStart(runDate).plusDays(6);
    }

    public String effectiveWeekLabel(LocalDate runDate) {
        if (week.label != null && !week.label.isBlank()) {
            return week.label;
        }
        LocalDate start = effectiveWeekStart(runDate);
        WeekFields fields = WeekFields.ISO;
        return "%d-W%02d".formatted(start.get(fields.weekBasedYear()), start.get(fields.weekOfWeekBasedYear()));
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

    public static class DetailInput {
        private int topFiles = 10;
        private int commits = 20;
        private int changedRegions = 40;
        private int changedRegionLines = 24;

        @JsonProperty("top-files")
        public int getTopFiles() {
            return topFiles;
        }

        public void setTopFiles(int topFiles) {
            this.topFiles = topFiles;
        }

        public int getCommits() {
            return commits;
        }

        public void setCommits(int commits) {
            this.commits = commits;
        }

        @JsonProperty("changed-regions")
        public int getChangedRegions() {
            return changedRegions;
        }

        public void setChangedRegions(int changedRegions) {
            this.changedRegions = changedRegions;
        }

        @JsonProperty("changed-region-lines")
        public int getChangedRegionLines() {
            return changedRegionLines;
        }

        public void setChangedRegionLines(int changedRegionLines) {
            this.changedRegionLines = changedRegionLines;
        }
    }
}
