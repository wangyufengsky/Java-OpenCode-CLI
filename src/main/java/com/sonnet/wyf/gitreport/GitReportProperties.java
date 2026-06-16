package com.sonnet.wyf.gitreport;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "git-report")
public class GitReportProperties {
    private final Project project = new Project();
    private final Paths paths = new Paths();
    private final Git git = new Git();
    private final OpenCode opencode = new OpenCode();
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

    public OpenCode getOpencode() {
        return opencode;
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
        private String serverUrl = "http://127.0.0.1:4096";
        private boolean manageServer = true;
        private int serverStartTimeoutSeconds = 30;
        private int requestTimeoutSeconds = 60;
        private int concurrency = 6;
        private int timeoutMinutes = 40;
        private int outputWaitSeconds = 30;
        private int maxRetries = 1;
        private int maxConcurrency = 6;
        private String workerMessage = "严格执行附件 worker-prompt.md 中的任务，只输出 DONE 或 BLOCKED。";
        private String synthesisMessage = "严格执行附件 synthesis-prompt.md 中的任务，生成最终中文总报告。";
        private Map<String, String> environment = defaultEnvironment();

        private static Map<String, String> defaultEnvironment() {
            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("OPENCODE_DISABLE_MODELS_FETCH", "true");
            return environment;
        }

        public String getServerUrl() {
            return serverUrl;
        }

        public void setServerUrl(String serverUrl) {
            this.serverUrl = serverUrl;
        }

        public boolean isManageServer() {
            return manageServer;
        }

        public void setManageServer(boolean manageServer) {
            this.manageServer = manageServer;
        }

        public int getServerStartTimeoutSeconds() {
            return serverStartTimeoutSeconds;
        }

        public void setServerStartTimeoutSeconds(int serverStartTimeoutSeconds) {
            this.serverStartTimeoutSeconds = serverStartTimeoutSeconds;
        }

        public int getRequestTimeoutSeconds() {
            return requestTimeoutSeconds;
        }

        public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
            this.requestTimeoutSeconds = requestTimeoutSeconds;
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

        public Map<String, String> getEnvironment() {
            return environment;
        }

        public void setEnvironment(Map<String, String> environment) {
            this.environment = environment == null ? new LinkedHashMap<>() : new LinkedHashMap<>(environment);
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

}
