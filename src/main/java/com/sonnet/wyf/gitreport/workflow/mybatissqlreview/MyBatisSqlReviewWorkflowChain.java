package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.artifact.RepositoryExecutionLock;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactContext;
import com.sonnet.wyf.gitreport.artifact.WorkflowArtifactWorkspace;
import com.sonnet.wyf.gitreport.runner.AgentBridgeRunnerProperties;
import com.sonnet.wyf.gitreport.runner.AgentBridgeSettings;
import com.sonnet.wyf.gitreport.runner.ChainConfigLoader;
import com.sonnet.wyf.gitreport.runner.WorkflowChain;
import com.sonnet.wyf.gitreport.runner.WorkflowRunRequest;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class MyBatisSqlReviewWorkflowChain implements WorkflowChain {
    public static final String ID = "mybatis-sql-review";
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[(?:\\\\.|[^]])*]\\(([^)]+)\\)");
    private static final Pattern REFERENCE_DEFINITION = Pattern.compile(
            "(?m)^[\\t ]{0,3}\\[[^]\\r\\n]+]:"
                    + "(?:[\\t ]*(?:\\r\\n|\\n|\\r)[\\t ]{0,3}|[\\t ]*)"
                    + "(?:<([^>\\r\\n]+)>|(\\S+))"
    );
    private static final Pattern EXTERNAL_SCHEME = Pattern.compile("(?i)^[a-z][a-z0-9+.-]*:");
    private static final Pattern AUTOLINK = Pattern.compile("(?i)<(?:https?|mailto):[^>]+>");
    private static final Pattern RAW_HTML = Pattern.compile("(?i)<\\s*/?\\s*[a-z][^>]*>");

    private final ChainConfigLoader configLoader;
    private final AgentBridgeRunnerProperties runnerProperties;
    private final MyBatisSqlInventoryBuilder inventoryBuilder;
    private final MyBatisDatabasePreflight databasePreflight;
    private final MyBatisSqlReviewTaskRunner taskRunner;
    private final MyBatisSqlReportRenderer reportRenderer;
    private final ObjectMapper objectMapper;

    public MyBatisSqlReviewWorkflowChain(
            ChainConfigLoader configLoader,
            AgentBridgeRunnerProperties runnerProperties,
            MyBatisSqlInventoryBuilder inventoryBuilder,
            MyBatisDatabasePreflight databasePreflight,
            MyBatisSqlReviewTaskRunner taskRunner,
            MyBatisSqlReportRenderer reportRenderer,
            ObjectMapper objectMapper
    ) {
        this.configLoader = Objects.requireNonNull(configLoader, "configLoader");
        this.runnerProperties = Objects.requireNonNull(runnerProperties, "runnerProperties");
        this.inventoryBuilder = Objects.requireNonNull(inventoryBuilder, "inventoryBuilder");
        this.databasePreflight = Objects.requireNonNull(databasePreflight, "databasePreflight");
        this.taskRunner = Objects.requireNonNull(taskRunner, "taskRunner");
        this.reportRenderer = Objects.requireNonNull(reportRenderer, "reportRenderer");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void run(WorkflowRunRequest request) throws Exception {
        Objects.requireNonNull(request, "request");
        WorkflowArtifactWorkspace workspace = null;
        Path failureManifestRoot = null;
        try {
            Configuration configuration = configLoader.load(configDir(request), id(), Configuration.class);
            if (configuration.getPaths() != null) {
                failureManifestRoot = configuration.getPaths().getOut();
            }
            configuration.validate();
            AgentBridgeSettings settings = request.agentBridge() == null
                    ? configuration.getAgentbridge()
                    : request.agentBridge();
            requireSerialSettings(settings);
            String mode = normalizeMode(request.mode());
            boolean rerun = "rerun".equals(mode);
            workspace = WorkflowArtifactWorkspace.start(
                    objectMapper,
                    id(),
                    request,
                    configuration.getPaths().getOut(),
                    rerun
            );
            WorkflowArtifactWorkspace activeWorkspace = workspace;
            try (var ignored = WorkflowArtifactContext.open(activeWorkspace);
                 var repositoryLock = RepositoryExecutionLock.acquire(configuration.getProject().getRepo())) {
                MyBatisSqlInventory inventory = rerun
                        ? publishedInventory(activeWorkspace)
                        : inventoryBuilder.build(
                                configuration.getProject().getRepo(),
                                configuration.getSource().getInclude(),
                                configuration.getSource().getExclude()
                        );
                reportRenderer.writeInventorySnapshot(activeWorkspace.bundleRoot(), inventory);
                List<MyBatisSqlStatement> selected = selectTasks(mode, request, inventory);
                MyBatisDatabasePreflight.Result database = databasePreflight.verify(
                        URI.create(settings.getMcpUrl()),
                        URI.create(settings.getWebBaseUrl()),
                        configuration.getDatabase().toContract()
                );
                List<MyBatisSqlReviewTaskRunner.PreparedTask> prepared = new ArrayList<>();
                for (MyBatisSqlStatement statement : selected) {
                    prepared.add(taskRunner.prepare(activeWorkspace, statement, database, settings));
                }
                if (!prepared.isEmpty()) {
                    Path repository = configuration.getProject().getRepo().toAbsolutePath().normalize();
                    List<Path> mapperFiles = inventory.mappers().stream()
                            .map(mapper -> repository.resolve(mapper.mapperRelativePath()).normalize())
                            .toList();
                    List<Path> candidates = prepared.stream()
                            .map(task -> task.layout().candidate())
                            .toList();
                    try (MyBatisSqlReviewFilesystemGuard guard =
                                 MyBatisSqlReviewFilesystemGuard.protectRun(
                                         objectMapper,
                                         repository,
                                         activeWorkspace.stableRoot(),
                                         activeWorkspace.runRoot(),
                                         mapperFiles,
                                         candidates
                                 )) {
                        for (MyBatisSqlReviewTaskRunner.PreparedTask task : prepared) {
                            taskRunner.runPrepared(activeWorkspace, task, guard);
                        }
                    }
                }
                reportRenderer.render(
                        activeWorkspace.bundleRoot(),
                        new MyBatisSqlReportRenderer.Project(
                                configuration.getProject().getId(),
                                configuration.getProject().getName(),
                                configuration.getProject().getRepo()
                        ),
                        inventory
                );
                validateCompleteBundle(activeWorkspace.bundleRoot(), inventory);
                activeWorkspace.publish(MyBatisSqlReportRenderer.MAIN_REPORT);
            }
        } catch (Exception exception) {
            if (workspace == null) {
                WorkflowArtifactWorkspace.markStartFailedBestEffort(
                        objectMapper, id(), request, failureManifestRoot, exception.getMessage());
            } else {
                workspace.markFailed(exception.getMessage());
            }
            throw exception;
        }
    }

    private MyBatisSqlInventory publishedInventory(WorkflowArtifactWorkspace workspace) throws Exception {
        Path snapshot = workspace.bundleRoot().resolve(MyBatisSqlReportRenderer.INVENTORY);
        if (!Files.isRegularFile(snapshot)) {
            throw new IllegalStateException(
                    "targeted MyBatis SQL rerun requires the last published sql-inventory.json snapshot"
            );
        }
        return reportRenderer.readInventory(snapshot);
    }

    private List<MyBatisSqlStatement> selectTasks(
            String mode,
            WorkflowRunRequest request,
            MyBatisSqlInventory inventory
    ) {
        if ("full".equals(mode)) {
            return inventory.statements();
        }
        if (!"rerun".equals(mode)) {
            throw new IllegalArgumentException("mybatis-sql-review mode must be one of: full, rerun");
        }
        String rerunType = request.rerunType() == null ? "" : request.rerunType().trim().toLowerCase(Locale.ROOT);
        return switch (rerunType) {
            case "sql" -> statementsByIds(inventory, request.rerunIds());
            case "xml" -> statementsByMapperIds(inventory, request.rerunIds());
            case "index" -> {
                if (!request.rerunIds().isEmpty()) {
                    throw new IllegalArgumentException("mybatis-sql-review index rerun does not accept ids");
                }
                yield List.of();
            }
            default -> throw new IllegalArgumentException(
                    "mybatis-sql-review rerun.type must be one of: sql, xml, index"
            );
        };
    }

    private List<MyBatisSqlStatement> statementsByIds(
            MyBatisSqlInventory inventory,
            List<String> ids
    ) {
        requireTargetIds(ids, "SQL");
        Set<String> requested = new LinkedHashSet<>(ids);
        List<MyBatisSqlStatement> selected = inventory.statements().stream()
                .filter(statement -> requested.contains(statement.statementKey()))
                .toList();
        Set<String> known = inventory.statements().stream()
                .map(MyBatisSqlStatement::statementKey)
                .collect(Collectors.toSet());
        List<String> missing = requested.stream().filter(id -> !known.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("unknown SQL statement key: " + String.join(", ", missing));
        }
        return selected;
    }

    private List<MyBatisSqlStatement> statementsByMapperIds(
            MyBatisSqlInventory inventory,
            List<String> ids
    ) {
        requireTargetIds(ids, "XML");
        Set<String> requested = new LinkedHashSet<>(ids);
        Set<String> known = inventory.mappers().stream()
                .map(MyBatisMapperInventory::mapperKey)
                .collect(Collectors.toSet());
        List<String> missing = requested.stream().filter(id -> !known.contains(id)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("unknown mapper key: " + String.join(", ", missing));
        }
        return inventory.statements().stream()
                .filter(statement -> requested.contains(statement.mapperKey()))
                .toList();
    }

    private void requireTargetIds(List<String> ids, String label) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("mybatis-sql-review " + label + " rerun requires at least one id");
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("mybatis-sql-review rerun ids must be unique");
        }
    }

    private void validateCompleteBundle(Path bundleRoot, MyBatisSqlInventory inventory) throws Exception {
        Path root = bundleRoot.toAbsolutePath().normalize();
        Set<String> expected = new LinkedHashSet<>(List.of(
                MyBatisSqlReportRenderer.MAIN_REPORT,
                MyBatisSqlReportRenderer.INVENTORY,
                MyBatisSqlReportRenderer.TASKS,
                MyBatisSqlReportRenderer.TRACEABILITY,
                MyBatisSqlReportRenderer.DATA_QUALITY
        ));
        for (MyBatisMapperInventory mapper : inventory.mappers()) {
            expected.add("reports/" + mapper.mapperKey() + "/index.md");
        }
        for (MyBatisSqlStatement statement : inventory.statements()) {
            String base = "reports/" + statement.mapperKey() + "/sql/" + statement.statementKey() + "/";
            expected.add(base + "report.md");
            expected.add(base + "summary.json");
            expected.add(base + "database-evidence.json");
        }
        Set<String> actual = new LinkedHashSet<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                actual.add(relative(root, path));
            }
        }
        if (!actual.equals(expected)) {
            Set<String> missing = new LinkedHashSet<>(expected);
            missing.removeAll(actual);
            Set<String> unexpected = new LinkedHashSet<>(actual);
            unexpected.removeAll(expected);
            throw new IllegalStateException(
                    "MyBatis SQL review bundle tree is incomplete: missing=" + missing + ", unexpected=" + unexpected
            );
        }
        validateMarkdownLinks(root);
    }

    static void validateMarkdownLinks(Path bundleRoot) throws Exception {
        Path root = bundleRoot.toAbsolutePath().normalize();
        Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        List<String> invalid = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path markdown : paths.filter(path -> path.toString().endsWith(".md")).toList()) {
                if (Files.isSymbolicLink(markdown)
                        || !Files.isRegularFile(markdown, LinkOption.NOFOLLOW_LINKS)
                        || !markdown.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(realRoot)) {
                    invalid.add(relative(root, markdown) + " -> unsafe Markdown file");
                    continue;
                }
                String prose = withoutFencedCode(Files.readString(markdown));
                Matcher autolink = AUTOLINK.matcher(prose);
                while (autolink.find()) {
                    invalid.add(relative(root, markdown) + " -> external autolink " + autolink.group());
                }
                Matcher html = RAW_HTML.matcher(prose);
                while (html.find()) {
                    if (!AUTOLINK.matcher(html.group()).matches()) {
                        invalid.add(relative(root, markdown) + " -> raw HTML " + html.group());
                    }
                }
                Matcher referenceDefinition = REFERENCE_DEFINITION.matcher(prose);
                while (referenceDefinition.find()) {
                    String link = referenceDefinition.group(1) == null
                            ? referenceDefinition.group(2)
                            : referenceDefinition.group(1);
                    link = link.trim();
                    if (EXTERNAL_SCHEME.matcher(link).find() || link.startsWith("//")) {
                        invalid.add(
                                relative(root, markdown) + " -> external reference definition " + link
                        );
                    }
                }
                Matcher matcher = MARKDOWN_LINK.matcher(prose);
                while (matcher.find()) {
                    String link = matcher.group(1).trim();
                    if (link.isBlank() || link.startsWith("#")) {
                        continue;
                    }
                    if (EXTERNAL_SCHEME.matcher(link).find() || link.startsWith("//")) {
                        invalid.add(relative(root, markdown) + " -> external link " + link);
                        continue;
                    }
                    int fragment = link.indexOf('#');
                    String fileLink = fragment < 0 ? link : link.substring(0, fragment);
                    if (fileLink.isBlank() || fileLink.indexOf('?') >= 0) {
                        invalid.add(relative(root, markdown) + " -> invalid relative link " + link);
                        continue;
                    }
                    Path raw;
                    try {
                        raw = Path.of(fileLink);
                    } catch (RuntimeException exception) {
                        invalid.add(relative(root, markdown) + " -> " + link);
                        continue;
                    }
                    Path target = markdown.getParent().resolve(raw).normalize();
                    if (raw.isAbsolute() || !target.startsWith(root)
                            || Files.isSymbolicLink(target)
                            || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                            || !target.toRealPath(LinkOption.NOFOLLOW_LINKS).startsWith(realRoot)) {
                        invalid.add(relative(root, markdown) + " -> " + link);
                    }
                }
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("MyBatis SQL review contains invalid relative links: " + invalid);
        }
    }

    private static String withoutFencedCode(String markdown) {
        StringBuilder prose = new StringBuilder(markdown.length());
        boolean fenced = false;
        char fenceCharacter = 0;
        for (String line : markdown.split("\\R", -1)) {
            String stripped = line.stripLeading();
            boolean marker = stripped.startsWith("```") || stripped.startsWith("~~~");
            if (marker && (!fenced || stripped.charAt(0) == fenceCharacter)) {
                fenced = !fenced;
                fenceCharacter = fenced ? stripped.charAt(0) : 0;
                prose.append('\n');
            } else if (fenced) {
                prose.append('\n');
            } else {
                prose.append(line).append('\n');
            }
        }
        return prose.toString();
    }

    private void requireSerialSettings(AgentBridgeSettings settings) {
        Objects.requireNonNull(settings, "AgentBridge settings");
        if (settings.getConcurrency() != 1 || settings.getMaxConcurrency() != 1) {
            throw new IllegalArgumentException("mybatis-sql-review AgentBridge concurrency must be 1");
        }
    }

    private String normalizeMode(String mode) {
        return mode == null || mode.isBlank() ? "full" : mode.trim().toLowerCase(Locale.ROOT);
    }

    private String configDir(WorkflowRunRequest request) {
        return request.configDir() == null || request.configDir().isBlank()
                ? runnerProperties.getConfigDir()
                : request.configDir();
    }

    private static String relative(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    public static final class Configuration {
        private Project project = new Project();
        private Paths paths = new Paths();
        private Source source = new Source();
        private Database database = new Database();
        private AgentBridgeSettings agentbridge = new AgentBridgeSettings();

        public Project getProject() {
            return project;
        }

        public void setProject(Project project) {
            this.project = project;
        }

        public Paths getPaths() {
            return paths;
        }

        public void setPaths(Paths paths) {
            this.paths = paths;
        }

        public Source getSource() {
            return source;
        }

        public void setSource(Source source) {
            this.source = source;
        }

        public Database getDatabase() {
            return database;
        }

        public void setDatabase(Database database) {
            this.database = database;
        }

        public AgentBridgeSettings getAgentbridge() {
            return agentbridge;
        }

        public void setAgentbridge(AgentBridgeSettings agentbridge) {
            this.agentbridge = agentbridge;
        }

        private void validate() {
            Objects.requireNonNull(project, "project").validate();
            Objects.requireNonNull(paths, "paths").validate();
            Objects.requireNonNull(source, "source").validate();
            Objects.requireNonNull(database, "database").validate();
            Objects.requireNonNull(agentbridge, "agentbridge");
        }
    }

    public static final class Project {
        private String id;
        private String name;
        private Path repo;

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

        private void validate() {
            requireNonBlank(id, "project.id");
            requireNonBlank(name, "project.name");
            Objects.requireNonNull(repo, "project.repo");
            if (!Files.isDirectory(repo)) {
                throw new IllegalArgumentException("project.repo must be an existing directory: " + repo);
            }
            repo = repo.toAbsolutePath().normalize();
        }
    }

    public static final class Paths {
        private Path out;

        public Path getOut() {
            return out;
        }

        public void setOut(Path out) {
            this.out = out;
        }

        private void validate() {
            Objects.requireNonNull(out, "paths.out");
            out = out.toAbsolutePath().normalize();
        }
    }

    public static final class Source {
        private List<String> include = List.of("**/*.xml");
        private List<String> exclude = List.of();

        public List<String> getInclude() {
            return include;
        }

        public void setInclude(List<String> include) {
            this.include = include == null ? List.of() : List.copyOf(include);
        }

        public List<String> getExclude() {
            return exclude;
        }

        public void setExclude(List<String> exclude) {
            this.exclude = exclude == null ? List.of() : List.copyOf(exclude);
        }

        private void validate() {
            if (include == null || include.isEmpty() || include.stream().anyMatch(value -> value == null || value.isBlank())) {
                throw new IllegalArgumentException("source.include must contain at least one non-blank XML glob");
            }
            include = List.copyOf(include);
            exclude = List.copyOf(exclude == null ? List.of() : exclude);
        }
    }

    public static final class Database {
        private String connectionName;
        private String databaseName;
        private String schemaName;
        private String environment;
        private boolean nonOwnerNonAdminReadOnlyAccount;
        private boolean rowLevelSecurityDisabledForSafeBaseTables;
        private boolean userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic;
        private long statementTimeoutSeconds;
        private String statementTimeoutScope;
        private int maxRows = MyBatisToolCallAudit.MAX_ROWS_PER_CALL;
        private int maxScenariosPerSql = MyBatisToolCallAudit.MAX_QUERY_SCENARIOS;
        private int maxEvidenceBytes = MyBatisSqlOutputValidator.MAX_EVIDENCE_BYTES;
        private boolean retainRawRows = true;
        private boolean allowAgentSelect = true;

        public String getConnectionName() {
            return connectionName;
        }

        public void setConnectionName(String connectionName) {
            this.connectionName = connectionName;
        }

        public String getDatabaseName() {
            return databaseName;
        }

        public void setDatabaseName(String databaseName) {
            this.databaseName = databaseName;
        }

        public String getSchemaName() {
            return schemaName;
        }

        public void setSchemaName(String schemaName) {
            this.schemaName = schemaName;
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }

        public boolean isNonOwnerNonAdminReadOnlyAccount() {
            return nonOwnerNonAdminReadOnlyAccount;
        }

        public void setNonOwnerNonAdminReadOnlyAccount(boolean value) {
            this.nonOwnerNonAdminReadOnlyAccount = value;
        }

        public boolean isRowLevelSecurityDisabledForSafeBaseTables() {
            return rowLevelSecurityDisabledForSafeBaseTables;
        }

        public void setRowLevelSecurityDisabledForSafeBaseTables(boolean value) {
            this.rowLevelSecurityDisabledForSafeBaseTables = value;
        }

        public boolean isUserDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic() {
            return userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic;
        }

        public void setUserDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic(boolean value) {
            this.userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic = value;
        }

        public long getStatementTimeoutSeconds() {
            return statementTimeoutSeconds;
        }

        public void setStatementTimeoutSeconds(long statementTimeoutSeconds) {
            this.statementTimeoutSeconds = statementTimeoutSeconds;
        }

        public String getStatementTimeoutScope() {
            return statementTimeoutScope;
        }

        public void setStatementTimeoutScope(String statementTimeoutScope) {
            this.statementTimeoutScope = statementTimeoutScope;
        }

        public int getMaxRows() {
            return maxRows;
        }

        public void setMaxRows(int maxRows) {
            this.maxRows = maxRows;
        }

        public int getMaxScenariosPerSql() {
            return maxScenariosPerSql;
        }

        public void setMaxScenariosPerSql(int maxScenariosPerSql) {
            this.maxScenariosPerSql = maxScenariosPerSql;
        }

        public int getMaxEvidenceBytes() {
            return maxEvidenceBytes;
        }

        public void setMaxEvidenceBytes(int maxEvidenceBytes) {
            this.maxEvidenceBytes = maxEvidenceBytes;
        }

        public boolean isRetainRawRows() {
            return retainRawRows;
        }

        public void setRetainRawRows(boolean retainRawRows) {
            this.retainRawRows = retainRawRows;
        }

        public boolean isAllowAgentSelect() {
            return allowAgentSelect;
        }

        public void setAllowAgentSelect(boolean allowAgentSelect) {
            this.allowAgentSelect = allowAgentSelect;
        }

        private void validate() {
            requireNonBlank(connectionName, "database.connection-name");
            requireNonBlank(databaseName, "database.database-name");
            requireNonBlank(schemaName, "database.schema-name");
            requireNonBlank(environment, "database.environment");
            requireNonBlank(statementTimeoutScope, "database.statement-timeout-scope");
            if (statementTimeoutSeconds < 1 || statementTimeoutSeconds > 30) {
                throw new IllegalArgumentException("database.statement-timeout-seconds must be in 1..30");
            }
            if (maxRows != MyBatisToolCallAudit.MAX_ROWS_PER_CALL
                    || maxScenariosPerSql != MyBatisToolCallAudit.MAX_QUERY_SCENARIOS
                    || maxEvidenceBytes != MyBatisSqlOutputValidator.MAX_EVIDENCE_BYTES
                    || !retainRawRows
                    || !allowAgentSelect) {
                throw new IllegalArgumentException(
                        "database review limits must remain max-rows=20, max-scenarios-per-sql=3, "
                                + "max-evidence-bytes=262144, retain-raw-rows=true, allow-agent-select=true"
                );
            }
        }

        private MyBatisDatabasePreflight.DatabaseContract toContract() {
            return new MyBatisDatabasePreflight.DatabaseContract(
                    connectionName,
                    databaseName,
                    schemaName,
                    parseEnum(MyBatisDatabasePreflight.Environment.class, environment, "database.environment"),
                    nonOwnerNonAdminReadOnlyAccount,
                    rowLevelSecurityDisabledForSafeBaseTables,
                    userDefinedAndSecurityDefinerFunctionExecutionRevokedIncludingPublic,
                    new MyBatisDatabasePreflight.StatementTimeoutContract(
                            Duration.ofSeconds(statementTimeoutSeconds),
                            parseEnum(
                                    MyBatisDatabasePreflight.StatementTimeoutScope.class,
                                    statementTimeoutScope,
                                    "database.statement-timeout-scope"
                            ),
                            true
                    )
            );
        }
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String name) {
        try {
            return Enum.valueOf(type, value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(name + " has unsupported value: " + value, exception);
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
