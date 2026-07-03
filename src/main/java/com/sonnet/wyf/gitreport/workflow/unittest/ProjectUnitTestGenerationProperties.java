package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sonnet.wyf.gitreport.GitReportProperties;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ProjectUnitTestGenerationProperties {
    private final Project project = new Project();
    private final Paths paths = new Paths();
    private final Docs docs = new Docs();
    private final Source source = new Source();
    private final Test test = new Test();
    private final GitReportProperties.OpenCode opencode = new GitReportProperties.OpenCode();

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

    public GitReportProperties.OpenCode getOpencode() {
        return opencode;
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
        private int concurrency = 3;
        private int maxTypesPerTask = 6;
        private int maxMethodsPerTask = 40;
        private int maxSourceCharsPerTask = 80_000;
        private List<String> verifyCommand = new ArrayList<>(List.of("./mvnw", "test"));

        public int getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(int concurrency) {
            this.concurrency = concurrency;
        }

        @JsonProperty("max-types-per-task")
        public int getMaxTypesPerTask() {
            return maxTypesPerTask;
        }

        @JsonProperty("max-types-per-task")
        public void setMaxTypesPerTask(int maxTypesPerTask) {
            this.maxTypesPerTask = maxTypesPerTask;
        }

        @JsonProperty("max-methods-per-task")
        public int getMaxMethodsPerTask() {
            return maxMethodsPerTask;
        }

        @JsonProperty("max-methods-per-task")
        public void setMaxMethodsPerTask(int maxMethodsPerTask) {
            this.maxMethodsPerTask = maxMethodsPerTask;
        }

        @JsonProperty("max-source-chars-per-task")
        public int getMaxSourceCharsPerTask() {
            return maxSourceCharsPerTask;
        }

        @JsonProperty("max-source-chars-per-task")
        public void setMaxSourceCharsPerTask(int maxSourceCharsPerTask) {
            this.maxSourceCharsPerTask = maxSourceCharsPerTask;
        }

        @JsonProperty("verify-command")
        public List<String> getVerifyCommand() {
            return verifyCommand;
        }

        @JsonProperty("verify-command")
        public void setVerifyCommand(List<String> verifyCommand) {
            this.verifyCommand = verifyCommand == null || verifyCommand.isEmpty()
                    ? new ArrayList<>(List.of("./mvnw", "test"))
                    : new ArrayList<>(verifyCommand);
        }
    }
}
