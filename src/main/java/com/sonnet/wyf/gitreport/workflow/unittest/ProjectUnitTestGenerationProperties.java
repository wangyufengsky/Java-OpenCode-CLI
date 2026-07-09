package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ProjectUnitTestGenerationProperties {
    private final Project project = new Project();
    private final Paths paths = new Paths();
    private final Docs docs = new Docs();
    private final Source source = new Source();
    private final Test test = new Test();
    private final AgentBridge agentbridge = new AgentBridge();

    public Project getProject() {
        return project;
    }

    public Paths getPaths() {
        return paths;
    }

    public Docs getDocs() {
        return docs;
    }

    public Source getSource() {
        return source;
    }

    public Test getTest() {
        return test;
    }

    public AgentBridge getAgentbridge() {
        return agentbridge;
    }

    public static class Project {
        private String id = "";
        private String name = "";
        private Path repo = Path.of(".");

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
    }

    public static class Paths {
        private Path out = Path.of("project-unit-tests");

        public Path getOut() {
            return out;
        }

        public void setOut(Path out) {
            this.out = out;
        }
    }

    public static class Docs {
        private Path agents = Path.of("AGENTS.md");
        private Path projectMap = Path.of("project-map.md");
        private Path reconstructedDesign = Path.of("重构项目详细设计文档.md");

        public Path getAgents() {
            return agents;
        }

        public void setAgents(Path agents) {
            this.agents = agents;
        }

        @JsonProperty("project-map")
        public Path getProjectMap() {
            return projectMap;
        }

        @JsonProperty("project-map")
        public void setProjectMap(Path projectMap) {
            this.projectMap = projectMap;
        }

        @JsonProperty("reconstructed-design")
        public Path getReconstructedDesign() {
            return reconstructedDesign;
        }

        @JsonProperty("reconstructed-design")
        public void setReconstructedDesign(Path reconstructedDesign) {
            this.reconstructedDesign = reconstructedDesign;
        }
    }

    public static class Source {
        private List<String> packagePaths = new ArrayList<>();
        private List<String> include = new ArrayList<>();
        private List<String> exclude = new ArrayList<>();

        @JsonProperty("package-paths")
        public List<String> getPackagePaths() {
            return packagePaths;
        }

        @JsonProperty("package-paths")
        public void setPackagePaths(List<String> packagePaths) {
            this.packagePaths = packagePaths == null ? new ArrayList<>() : new ArrayList<>(packagePaths);
        }

        public List<String> getInclude() {
            return include;
        }

        public void setInclude(List<String> include) {
            this.include = include == null ? new ArrayList<>() : new ArrayList<>(include);
        }

        public List<String> getExclude() {
            return exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = exclude == null ? new ArrayList<>() : new ArrayList<>(exclude);
        }
    }

    public static class Test {
        private boolean requireCoverage = false;
        private int coverageThresholdPercent = 90;
        private String jacocoVersion = "0.8.15";
        private String jacocoJvmArgProperty = "argLine";
        private String jacocoJvmArgBase = "";

        @JsonProperty("require-coverage")
        public boolean isRequireCoverage() {
            return requireCoverage;
        }

        @JsonProperty("require-coverage")
        public void setRequireCoverage(boolean requireCoverage) {
            this.requireCoverage = requireCoverage;
        }

        @JsonProperty("coverage-threshold-percent")
        public int getCoverageThresholdPercent() {
            return coverageThresholdPercent;
        }

        @JsonProperty("coverage-threshold-percent")
        public void setCoverageThresholdPercent(int coverageThresholdPercent) {
            this.coverageThresholdPercent = coverageThresholdPercent;
        }

        @JsonProperty("jacoco-version")
        public String getJacocoVersion() {
            return jacocoVersion;
        }

        @JsonProperty("jacoco-version")
        public void setJacocoVersion(String jacocoVersion) {
            this.jacocoVersion = jacocoVersion == null || jacocoVersion.isBlank() ? "0.8.15" : jacocoVersion;
        }

        @JsonProperty("jacoco-jvm-arg-property")
        public String getJacocoJvmArgProperty() {
            return jacocoJvmArgProperty;
        }

        @JsonProperty("jacoco-jvm-arg-property")
        public void setJacocoJvmArgProperty(String jacocoJvmArgProperty) {
            this.jacocoJvmArgProperty = jacocoJvmArgProperty == null || jacocoJvmArgProperty.isBlank()
                    ? "argLine"
                    : jacocoJvmArgProperty;
        }

        @JsonProperty("jacoco-jvm-arg-base")
        public String getJacocoJvmArgBase() {
            return jacocoJvmArgBase;
        }

        @JsonProperty("jacoco-jvm-arg-base")
        public void setJacocoJvmArgBase(String jacocoJvmArgBase) {
            this.jacocoJvmArgBase = jacocoJvmArgBase == null ? "" : jacocoJvmArgBase;
        }

    }

    public static class AgentBridge {
        private String webBaseUrl = "https://127.0.0.1:9642";
        private String mcpUrl = "http://127.0.0.1:8642/mcp";
        private int timeoutMinutes = 40;
        private int maxAttempts = 5;

        @JsonProperty("web-base-url")
        public String getWebBaseUrl() {
            return webBaseUrl;
        }

        @JsonProperty("web-base-url")
        public void setWebBaseUrl(String webBaseUrl) {
            this.webBaseUrl = webBaseUrl == null || webBaseUrl.isBlank() ? "https://127.0.0.1:9642" : webBaseUrl;
        }

        @JsonProperty("mcp-url")
        public String getMcpUrl() {
            return mcpUrl;
        }

        @JsonProperty("mcp-url")
        public void setMcpUrl(String mcpUrl) {
            this.mcpUrl = mcpUrl == null || mcpUrl.isBlank() ? "http://127.0.0.1:8642/mcp" : mcpUrl;
        }

        @JsonProperty("timeout-minutes")
        public int getTimeoutMinutes() {
            return timeoutMinutes;
        }

        @JsonProperty("timeout-minutes")
        public void setTimeoutMinutes(int timeoutMinutes) {
            this.timeoutMinutes = timeoutMinutes;
        }

        @JsonProperty("max-attempts")
        public int getMaxAttempts() {
            return maxAttempts;
        }

        @JsonProperty("max-attempts")
        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
