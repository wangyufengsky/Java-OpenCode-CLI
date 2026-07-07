package com.sonnet.wyf.gitreport;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "git-report")
public class GitReportProperties {
    private final Project project = new Project();
    private final Paths paths = new Paths();
    private final Git git = new Git();
    private final AgentBridge agentbridge = new AgentBridge();
    private final DetailInput detailInput = new DetailInput();
    private final SynthesisInput synthesisInput = new SynthesisInput();

    public Project getProject() {
        return project;
    }

    public Paths getPaths() {
        return paths;
    }

    public Git getGit() {
        return git;
    }

    public AgentBridge getAgentbridge() {
        return agentbridge;
    }

    public DetailInput getDetailInput() {
        return detailInput;
    }

    public SynthesisInput getSynthesisInput() {
        return synthesisInput;
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

    public static class AgentBridge {
        private String webBaseUrl = "https://127.0.0.1:9642";
        private String mcpUrl = "http://127.0.0.1:8642/mcp";
        private int concurrency = 1;
        private int timeoutMinutes = 40;
        private int pollMillis = 1000;
        private int validationSettleSeconds = 30;
        private int validationMaxCorrections = 2;
        private int maxRetries = 1;
        private int maxConcurrency = 1;
        private String taskMessage = "严格执行附件 worker-prompt.md 中的任务，写入要求的文件；完成后回复简短完成信息即可，Java 会校验输出。";
        private String synthesisTaskMessage = "严格执行附件 synthesis-prompt.md 中的任务，生成最终中文总报告；完成后回复简短完成信息即可，Java 会校验输出。";

        public String getWebBaseUrl() {
            return webBaseUrl;
        }

        public void setWebBaseUrl(String webBaseUrl) {
            this.webBaseUrl = webBaseUrl;
        }

        public String getMcpUrl() {
            return mcpUrl;
        }

        public void setMcpUrl(String mcpUrl) {
            this.mcpUrl = mcpUrl;
        }

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

        public int getPollMillis() {
            return pollMillis;
        }

        public void setPollMillis(int pollMillis) {
            this.pollMillis = pollMillis;
        }

        public int getValidationSettleSeconds() {
            return validationSettleSeconds;
        }

        public void setValidationSettleSeconds(int validationSettleSeconds) {
            this.validationSettleSeconds = validationSettleSeconds;
        }

        public int getValidationMaxCorrections() {
            return validationMaxCorrections;
        }

        public void setValidationMaxCorrections(int validationMaxCorrections) {
            this.validationMaxCorrections = validationMaxCorrections;
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

        public String getTaskMessage() {
            return taskMessage;
        }

        public void setTaskMessage(String taskMessage) {
            this.taskMessage = taskMessage;
        }

        public String getSynthesisTaskMessage() {
            return synthesisTaskMessage;
        }

        public void setSynthesisTaskMessage(String synthesisTaskMessage) {
            this.synthesisTaskMessage = synthesisTaskMessage;
        }
    }

    public static class SynthesisInput {
        private int personReportExcerptChars = 8_000;
        private int snippetsPerAuthor = 5;
        private int snippetsTotal = 30;
        private int snippetLines = 20;

        public int getPersonReportExcerptChars() {
            return personReportExcerptChars;
        }

        public void setPersonReportExcerptChars(int personReportExcerptChars) {
            this.personReportExcerptChars = personReportExcerptChars;
        }

        public int getSnippetsPerAuthor() {
            return snippetsPerAuthor;
        }

        public void setSnippetsPerAuthor(int snippetsPerAuthor) {
            this.snippetsPerAuthor = snippetsPerAuthor;
        }

        public int getSnippetsTotal() {
            return snippetsTotal;
        }

        public void setSnippetsTotal(int snippetsTotal) {
            this.snippetsTotal = snippetsTotal;
        }

        public int getSnippetLines() {
            return snippetLines;
        }

        public void setSnippetLines(int snippetLines) {
            this.snippetLines = snippetLines;
        }
    }

    public static class DetailInput {
        private int topFiles = 10;
        private int commits = 20;
        private int changedRegions = 40;
        private int changedRegionLines = 24;

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

        public int getChangedRegions() {
            return changedRegions;
        }

        public void setChangedRegions(int changedRegions) {
            this.changedRegions = changedRegions;
        }

        public int getChangedRegionLines() {
            return changedRegionLines;
        }

        public void setChangedRegionLines(int changedRegionLines) {
            this.changedRegionLines = changedRegionLines;
        }
    }

}
