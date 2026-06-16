package com.sonnet.wyf.gitreport;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "git-report")
public class GitReportProperties {
    private boolean enabled = false;
    private final Project project = new Project();
    private final Paths paths = new Paths();
    private final Git git = new Git();
    private final OpenCode opencode = new OpenCode();
    private final Runtime runtime = new Runtime();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Project getProject() {
        return project;
    }

    public Paths getPaths() {
        return paths;
    }

    public Git getGit() {
        return git;
    }

    public OpenCode getOpencode() {
        return opencode;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public static class Project {
        private String id;
        private String name;
        private String runId;

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

        public String getRunId() {
            return runId;
        }

        public void setRunId(String runId) {
            this.runId = runId;
        }
    }

    public static class Paths {
        private Path repo = Path.of(".");
        private Path out = Path.of("git-report-output");
        private String opencodeBin = "opencode";

        public Path getRepo() {
            return repo;
        }

        public void setRepo(Path repo) {
            this.repo = repo;
        }

        public Path getOut() {
            return out;
        }

        public void setOut(Path out) {
            this.out = out;
        }

        public String getOpencodeBin() {
            return opencodeBin;
        }

        public void setOpencodeBin(String opencodeBin) {
            this.opencodeBin = opencodeBin;
        }
    }

    public static class Git {
        private LocalDate since;
        private LocalDate until;
        private String revision = "HEAD";
        private boolean includeMerges;
        private Path authorMap;
        private List<String> include = new ArrayList<>();
        private List<String> exclude = new ArrayList<>();

        public LocalDate getSince() {
            return since;
        }

        public void setSince(LocalDate since) {
            this.since = since;
        }

        public LocalDate getUntil() {
            return until;
        }

        public void setUntil(LocalDate until) {
            this.until = until;
        }

        public String getRevision() {
            return revision;
        }

        public void setRevision(String revision) {
            this.revision = revision;
        }

        public boolean isIncludeMerges() {
            return includeMerges;
        }

        public void setIncludeMerges(boolean includeMerges) {
            this.includeMerges = includeMerges;
        }

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
            this.include = include;
        }

        public List<String> getExclude() {
            return exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = exclude;
        }
    }

    public static class OpenCode {
        private int concurrency = 3;
        private int timeoutMinutes = 40;
        private int outputWaitSeconds = 30;
        private int maxRetries = 1;
        private int maxConcurrency = 5;
        private String format = "json";
        private String model;
        private String workerMessage = "严格执行附件 worker-prompt.md 中的任务，只输出 DONE 或 BLOCKED。";
        private String synthesisMessage = "严格执行附件 synthesis-prompt.md 中的任务，生成最终中文总报告。";

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        public int getTimeoutMinutes() {
            return timeoutMinutes;
        }

        public void setTimeoutMinutes(int timeoutMinutes) {
            this.timeoutMinutes = timeoutMinutes;
        }

        public int getOutputWaitSeconds() {
            return outputWaitSeconds;
        }

        public void setOutputWaitSeconds(int outputWaitSeconds) {
            this.outputWaitSeconds = outputWaitSeconds;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getWorkerMessage() {
            return workerMessage;
        }

        public void setWorkerMessage(String workerMessage) {
            this.workerMessage = workerMessage;
        }

        public String getSynthesisMessage() {
            return synthesisMessage;
        }

        public void setSynthesisMessage(String synthesisMessage) {
            this.synthesisMessage = synthesisMessage;
        }
    }

    public static class Runtime {
        private String mode = "full";
        private boolean resume = true;
        private boolean failFast = true;
        private boolean writeCommandToStatus = true;

        public String getMode() {
            return mode;
        }

        public void setMode(String mode) {
            this.mode = mode;
        }

        public boolean isResume() {
            return resume;
        }

        public void setResume(boolean resume) {
            this.resume = resume;
        }

        public boolean isFailFast() {
            return failFast;
        }

        public void setFailFast(boolean failFast) {
            this.failFast = failFast;
        }

        public boolean isWriteCommandToStatus() {
            return writeCommandToStatus;
        }

        public void setWriteCommandToStatus(boolean writeCommandToStatus) {
            this.writeCommandToStatus = writeCommandToStatus;
        }
    }
}
