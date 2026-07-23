# Native Database MCP SQL Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the MyBatis SQL review workflow use the native Database MCP tools registered through AgentBridge Custom MCP, with current argument schemas, task-scoped tool evidence, and read-only validation.

**Architecture:** Introduce one `DatabaseMcpContract` as the source of truth for tool names, binding fields, and argument construction. Keep AgentBridge as the only MCP endpoint, normalize its Web tool-call records, and let preflight, task prompts, auditing, validation, and reports consume the same native contract.

**Tech Stack:** Java 25, Spring Boot 4, Jackson, JUnit 5, AssertJ, AgentBridge MCP, Database MCP, Maven, Node test runner.

## Global Constraints

- Application and review agents connect to AgentBridge MCP at `http://127.0.0.1:8642/mcp`; Database MCP remains behind AgentBridge Custom MCP.
- Native read tools are `cmcp_db_database_list_datasources`, `cmcp_db_database_list_databases`, `cmcp_db_database_list_table_schema`, and `cmcp_db_database_execute_sql_query`.
- SQL query calls always bind `dataSource`, `sql`, `maxRows: 20`, absolute `project`, and configured `scope`.
- `scope` is one of `GLOBAL`, `PROJECT`, or `ALL`, and defaults to `ALL`.
- DML, DDL, NoSQL write, NoSQL query, and unknown tools fail the statement task and prevent publication.
- Scenario SQL is a Java-validated read-only query with an integer `LIMIT` from 1 through 20; at most three scenarios are allowed.
- DML mapper statements and `selectKey` statements are static-review-only and cannot execute query scenarios.
- Runtime code, prompts, reports, configuration, tests, and documentation use only the native Database MCP contract and current-tense wording.
- Existing atomic candidate validation and stable-publication preservation remain mandatory.

---

### Task 1: Centralize the native Database MCP contract

**Files:**
- Create: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/DatabaseMcpContract.java`
- Create: `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/DatabaseMcpContractTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper`/`ObjectNode`, normalized repository `Path`, configured data source/catalog/schema/scope.
- Produces: `DatabaseMcpContract.Binding`, `DatabaseMcpContract.Scope`, tool constants, `readTools()`, `prohibitedTools()`, `dataSourceArguments()`, `databaseArguments()`, `tableSchemaArguments()`, and `queryArguments(String sql)`.

- [ ] **Step 1: Write failing binding and argument tests**

```java
@Test
void queryArgumentsUseTheNativeDatabaseMcpSchema() throws Exception {
    DatabaseMcpContract.Binding binding = new DatabaseMcpContract.Binding(
            "GaussDB-ReadOnly", "orders", "audit",
            Path.of("/workspace/example"), DatabaseMcpContract.Scope.ALL
    );

    JsonNode arguments = new DatabaseMcpContract(new ObjectMapper(), binding)
            .queryArguments("SELECT id FROM audit.orders LIMIT 20");

    assertThat(arguments).isEqualTo(new ObjectMapper().readTree("""
            {"dataSource":"GaussDB-ReadOnly","sql":"SELECT id FROM audit.orders LIMIT 20",
             "maxRows":20,"project":"/workspace/example","scope":"ALL"}
            """));
}

@Test
void scopeRejectsUnknownValues() {
    assertThatThrownBy(() -> DatabaseMcpContract.Scope.parse("LOCAL"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("GLOBAL, PROJECT, or ALL");
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run: `mvn -q -Dtest=DatabaseMcpContractTest test`

Expected: compilation fails because `DatabaseMcpContract` does not exist.

- [ ] **Step 3: Implement the contract**

```java
public final class DatabaseMcpContract {
    public static final String LIST_DATASOURCES = "cmcp_db_database_list_datasources";
    public static final String LIST_DATABASES = "cmcp_db_database_list_databases";
    public static final String LIST_TABLE_SCHEMA = "cmcp_db_database_list_table_schema";
    public static final String EXECUTE_QUERY = "cmcp_db_database_execute_sql_query";
    public static final int MAX_ROWS = 20;

    public enum Scope {
        GLOBAL, PROJECT, ALL;

        public static Scope parse(String value) {
            try {
                return valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("database.scope must be GLOBAL, PROJECT, or ALL", exception);
            }
        }
    }

    public record Binding(String dataSource, String catalog, String schema, Path project, Scope scope) {
        public Binding {
            dataSource = requireText(dataSource, "dataSource");
            catalog = requireText(catalog, "catalog");
            schema = requireText(schema, "schema");
            project = project.toAbsolutePath().normalize();
            Objects.requireNonNull(scope, "scope");
        }
    }
}
```

Implement immutable tool sets and exact Jackson argument builders. `tableSchemaArguments()` includes `catalog`, `schema`, `includeColumns: true`, `includeIndexes: true`, and `maxTables: 200`. `queryArguments()` rejects blank SQL.

- [ ] **Step 4: Run tests and verify GREEN**

Run: `mvn -q -Dtest=DatabaseMcpContractTest test`

Expected: all `DatabaseMcpContractTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/DatabaseMcpContract.java \
        src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/DatabaseMcpContractTest.java
git commit -m "feat: define native Database MCP contract"
```

### Task 2: Normalize AgentBridge Custom MCP evidence

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClient.java`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClientTest.java`

**Interfaces:**
- Consumes: AgentBridge `/info`, MCP session responses, `tools/list`, `tools/call`, and Web `/tool-calls` responses.
- Produces: `List<ToolDefinition>`, `ToolResponse.structured()`, and `List<ToolCallRecord>` whose `arguments` and `result` are immutable structured JSON values.

- [ ] **Step 1: Write failing tests for the current AgentBridge shapes**

```java
@Test
void toolCallsParseStringEncodedArgumentsAndResults() throws Exception {
    server.toolCalls("""
            {"items":[{"id":"17","title":"Query","toolName":"cmcp_db_database_execute_sql_query",
            "kind":"execute","status":"success","timestamp":"2026-07-23T00:00:00Z",
            "arguments":"{\"dataSource\":\"GaussDB-ReadOnly\",\"sql\":\"SELECT id FROM audit.orders LIMIT 1\",\"maxRows\":20,\"project\":\"/workspace/example\",\"scope\":\"ALL\"}",
            "result":"[{\"id\":1}]","durationMs":25}]}
            """);

    AgentBridgeClient.ToolCallRecord call = client.getToolCalls(server.webUri()).getFirst();

    assertThat(call.arguments().path("maxRows").intValue()).isEqualTo(20);
    assertThat(call.result().isArray()).isTrue();
}

@Test
void malformedToolCallJsonFailsClosed() {
    server.toolCalls("""
            {"items":[{"id":"17","toolName":"cmcp_db_database_execute_sql_query",
            "timestamp":"2026-07-23T00:00:00Z","arguments":"not-json","result":"[]"}]}
            """);

    assertThatThrownBy(() -> client.getToolCalls(server.webUri()))
            .hasMessageContaining("arguments must contain JSON");
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `mvn -q -Dtest=AgentBridgeClientTest#toolCallsParseStringEncodedArgumentsAndResults+malformedToolCallJsonFailsClosed test`

Expected: the first assertion fails because arguments/results remain strings; the malformed record is not rejected with the expected message.

- [ ] **Step 3: Implement native endpoint checks and JSON normalization**

Add a bounded helper:

```java
private JsonNode structuredHistoryField(JsonNode value, String label) {
    if (value.isObject() || value.isArray()) {
        return value.deepCopy();
    }
    if (!value.isTextual() || value.textValue().isBlank()) {
        throw new IllegalStateException(label + " must contain JSON");
    }
    try {
        JsonNode parsed = objectMapper.readTree(value.textValue());
        if (!parsed.isObject() && !parsed.isArray()) {
            throw new IllegalStateException(label + " must contain an object or array");
        }
        return parsed;
    } catch (JsonProcessingException exception) {
        throw new IllegalStateException(label + " must contain JSON", exception);
    }
}
```

Make `/tool-calls` accept exactly one `items` array, reject missing/duplicate IDs, and normalize every record. Keep response byte limits, timestamps, duration, status, and duplicate conflict checks. Require AgentBridge version `1.202.0` or later from `/info`, but do not require fields absent from the native endpoints. Keep MCP session negotiation and loopback-only endpoint validation.

- [ ] **Step 4: Run focused and class tests**

Run: `mvn -q -Dtest=AgentBridgeClientTest test`

Expected: all `AgentBridgeClientTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClient.java \
        src/test/java/com/sonnet/wyf/gitreport/agentbridge/AgentBridgeClientTest.java
git commit -m "feat: normalize AgentBridge Custom MCP evidence"
```

### Task 3: Rebuild database preflight around native tools

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisDatabasePreflight.java`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisDatabasePreflightTest.java`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/VerifiedMyBatisDatabaseFixture.java`

**Interfaces:**
- Consumes: `DatabaseMcpContract`, `AgentBridgeClient`, `DatabaseContract`, repository path, and scope.
- Produces: verified `Result` with immutable `DatabaseMcpContract.Binding`, database system/environment, statement timeout, and safe base relations.

- [ ] **Step 1: Write failing preflight tests**

```java
@Test
void verifiesConfiguredDataSourceAndUsesNativeQueryArguments() throws Exception {
    bridge.installNativeDatabaseMcpTools();

    MyBatisDatabasePreflight.Result result = preflight.verify(
            bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL"
    );

    assertThat(result.binding().dataSource()).isEqualTo("GaussDB-ReadOnly");
    assertThat(bridge.calledTools()).containsExactly(
            DatabaseMcpContract.LIST_DATASOURCES,
            DatabaseMcpContract.LIST_DATABASES,
            DatabaseMcpContract.LIST_TABLE_SCHEMA,
            DatabaseMcpContract.EXECUTE_QUERY
    );
    assertThat(bridge.argumentsFor(DatabaseMcpContract.EXECUTE_QUERY).path("maxRows").intValue())
            .isEqualTo(20);
}

@Test
void rejectsDatabaseMcpCatalogWithoutTheNativeTools() {
    bridge.installTools(DatabaseMcpContract.LIST_DATASOURCES);

    assertThatThrownBy(() -> preflight.verify(
            bridge.mcpUri(), bridge.webUri(), contract(), Path.of("/workspace/example"), "ALL"))
            .hasMessageContaining("Database MCP tools unavailable");
}
```

- [ ] **Step 2: Run tests and verify RED**

Run: `mvn -q -Dtest=MyBatisDatabasePreflightTest test`

Expected: compilation fails because the new verification signature and native fixture API do not exist.

- [ ] **Step 3: Implement native preflight**

Change the public API to:

```java
public Result verify(
        URI mcpUri,
        URI webBaseUri,
        DatabaseContract contract,
        Path projectPath,
        String scope
) throws Exception
```

Build one `DatabaseMcpContract.Binding`, require its four read tools, validate the live input schemas, select exactly one data source by name, verify the catalog/schema metadata, and execute the fixed GaussDB safety probe through `DatabaseMcpContract.EXECUTE_QUERY`. Parse Database MCP results whether represented as an array or as `{columns, rows}`. Preserve every privilege, RLS, physical-standby, timeout, and safe-table check already enforced by the probe.

Change `recheck(...)` to repeat data-source resolution and the safety probe using the verified binding. Remove transport fields that are not part of the native Database MCP result.

- [ ] **Step 4: Run preflight tests and verify GREEN**

Run: `mvn -q -Dtest=MyBatisDatabasePreflightTest test`

Expected: all preflight tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisDatabasePreflight.java \
        src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisDatabasePreflightTest.java \
        src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/VerifiedMyBatisDatabaseFixture.java
git commit -m "feat: verify native Database MCP data sources"
```

### Task 4: Audit native Database MCP calls and evidence

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisToolCallAudit.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlOutputValidator.java`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisToolCallAuditTest.java`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlOutputValidatorTest.java`
- Modify: `src/test/resources/mybatis-sql-review-fixtures/tool-calls.json`
- Modify: `src/test/resources/mybatis-sql-review-fixtures/database-evidence-valid.json`

**Interfaces:**
- Consumes: normalized `ToolCallRecord`, task boundary, verified database binding, statement context, and candidate evidence JSON.
- Produces: immutable audited facts and validated database evidence using `data_source`, `catalog`, `schema`, `project`, `scope`, `sql`, and `max_rows`.

- [ ] **Step 1: Write failing allowed and prohibited call tests**

```java
@Test
void acceptsBoundedNativeQueryCall() {
    ObjectNode arguments = nativeQueryArguments("SELECT id FROM audit.orders LIMIT 2");

    MyBatisToolCallAudit.Result result = audit.audit(
            List.of(call("query-1", DatabaseMcpContract.EXECUTE_QUERY, arguments, rows(2))),
            boundary(), database(), selectStatement()
    );

    assertThat(result.facts()).singleElement()
            .extracting(MyBatisToolCallAudit.AuditedCallFact::toolName)
            .isEqualTo(DatabaseMcpContract.EXECUTE_QUERY);
}

@ParameterizedTest
@ValueSource(strings = {
        "cmcp_db_database_execute_sql_dml",
        "cmcp_db_database_execute_sql_ddl",
        "cmcp_db_database_execute_nosql_write_delete",
        "cmcp_db_database_execute_nosql_query"
})
void rejectsEveryWriteOrNosqlTool(String toolName) {
    assertThatThrownBy(() -> audit.audit(
            List.of(call("unsafe", toolName, objectMapper.createObjectNode(), rows(0))),
            boundary(), database(), selectStatement()))
            .hasMessageContaining("unapproved tool");
}
```

- [ ] **Step 2: Run audit tests and verify RED**

Run: `mvn -q -Dtest=MyBatisToolCallAuditTest,MyBatisSqlOutputValidatorTest test`

Expected: assertions fail because the audit still recognizes non-native names and fields.

- [ ] **Step 3: Implement native audit and evidence fields**

Use `DatabaseMcpContract.readTools()` as the only allowed set. Validate common arguments against `binding.dataSource()`, normalized project path, and scope. Validate catalog/schema for table metadata. For queries require exactly `dataSource`, `sql`, `maxRows`, `project`, `scope`, require `maxRows == 20`, validate SQL through `ReadOnlySqlPolicy`, enforce safe relations and at most three scenarios, and reject any query for DML/selectKey statements.

Normalize result arrays and `{columns,rows}` objects, retain at most 20 rows, and preserve the evidence byte limit. Change output validation and fixtures to the native field names. Unknown fields or malformed values fail closed.

- [ ] **Step 4: Run audit and validator tests**

Run: `mvn -q -Dtest=MyBatisToolCallAuditTest,MyBatisSqlOutputValidatorTest test`

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisToolCallAudit.java \
        src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlOutputValidator.java \
        src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisToolCallAuditTest.java \
        src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlOutputValidatorTest.java \
        src/test/resources/mybatis-sql-review-fixtures/tool-calls.json \
        src/test/resources/mybatis-sql-review-fixtures/database-evidence-valid.json
git commit -m "feat: audit native Database MCP calls"
```

### Task 5: Wire workflow configuration, tasks, prompts, and reports

**Files:**
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewWorkflowChain.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReviewTaskRunner.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlPromptBuilder.java`
- Modify: `src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview/MyBatisSqlReportRenderer.java`
- Modify: `src/main/resources/chains/mybatis-sql-review.yml`
- Modify: `src/main/resources/mybatis-sql-review-prompt-pack/prompts/review-sql.md`
- Modify: `src/main/resources/mybatis-sql-review-prompt-pack/templates/sql-detail-report.md`
- Modify: `src/main/resources/mybatis-sql-review-prompt-pack/schemas/sql-summary.schema.json`
- Modify: corresponding `MyBatisSqlReviewWorkflowChainTest`, `MyBatisSqlReviewTaskRunnerTest`, `MyBatisSqlPromptBuilderTest`, and `MyBatisSqlReportRendererTest` files.

**Interfaces:**
- Consumes: chain YAML `database.scope`, repository path, verified binding, task statement, prompt pack, and audited facts.
- Produces: native prompts, task metadata, candidate files, detailed SQL reports, mapper indexes, and project report.

- [ ] **Step 1: Write failing prompt and workflow tests**

```java
@Test
void promptUsesOnlyNativeDatabaseMcpToolsAndArguments() {
    String prompt = builder.build(task(), database(), paths());

    assertThat(prompt)
            .contains("`cmcp_db_database_list_table_schema`")
            .contains("`cmcp_db_database_execute_sql_query`")
            .contains("\"maxRows\": 20")
            .contains("\"scope\": \"ALL\"");
}

@Test
void chainDefaultsDatabaseScopeToAll() {
    ChainDefinition definition = loader.load("mybatis-sql-review");
    assertThat(definition.database().getScope()).isEqualTo("ALL");
}
```

- [ ] **Step 2: Run workflow-focused tests and verify RED**

Run: `mvn -q -Dtest=MyBatisSqlReviewWorkflowChainTest,MyBatisSqlReviewTaskRunnerTest,MyBatisSqlPromptBuilderTest,MyBatisSqlReportRendererTest test`

Expected: assertions fail because configuration, prompts, and reports still use non-native database calls.

- [ ] **Step 3: Implement workflow wiring and native wording**

Add `scope` to `DatabaseDefinition`, validate with `DatabaseMcpContract.Scope.parse`, pass repository path/scope to preflight, and set MCP URL to port 8642. Update task JSON and evidence/report rendering to use `data_source`, `catalog`, `schema`, `project`, and `scope`.

Rewrite the prompt and report template as direct native Database MCP instructions. Describe only current tool names and input fields. Ensure DML/selectKey tasks state that query execution is prohibited, and every SELECT scenario includes `LIMIT 1..20` plus `maxRows: 20`.

- [ ] **Step 4: Run workflow-focused tests and verify GREEN**

Run: `mvn -q -Dtest=MyBatisSqlReviewWorkflowChainTest,MyBatisSqlReviewTaskRunnerTest,MyBatisSqlPromptBuilderTest,MyBatisSqlReportRendererTest test`

Expected: all workflow-focused tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview \
        src/main/resources/chains/mybatis-sql-review.yml \
        src/main/resources/mybatis-sql-review-prompt-pack \
        src/test/java/com/sonnet/wyf/gitreport/workflow/mybatissqlreview
git commit -m "feat: run SQL reviews with native Database MCP"
```

### Task 6: Align console copy, documentation, and contract tests

**Files:**
- Modify: `README.md`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-example.yml`
- Modify: `src/main/resources/static/js/console-common.js`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/PromptPackContractTest.java`
- Modify: `src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java`
- Modify: `src/test/js/run-form.test.js`

**Interfaces:**
- Consumes: final native configuration and report contract.
- Produces: current console field help, example configuration, operator documentation, and residual-semantic contract tests.

- [ ] **Step 1: Write failing contract assertions**

```java
@Test
void myBatisPromptPackUsesNativeDatabaseMcpVocabulary() throws Exception {
    String prompt = Files.readString(Path.of(
            "src/main/resources/mybatis-sql-review-prompt-pack/prompts/review-sql.md"));
    assertThat(prompt)
            .contains("cmcp_db_database_execute_sql_query")
            .contains("dataSource")
            .contains("maxRows")
            .contains("scope");
}
```

Add console tests that require scope help text and the 8642 MCP default.

- [ ] **Step 2: Run contract/UI tests and verify RED**

Run: `mvn -q -Dtest=PromptPackContractTest,ConsoleMvcTest test && node --test src/test/js/run-form.test.js`

Expected: native vocabulary/default assertions fail.

- [ ] **Step 3: Rewrite documentation and configuration copy**

Document AgentBridge Custom MCP routing, four native read tools, prohibited write tools, `scope`, `maxRows: 20`, read-only GaussDB account requirements, and the normal run command. Use current-tense operational wording only. Update application and console defaults to `http://127.0.0.1:8642/mcp`.

- [ ] **Step 4: Run contract/UI tests and verify GREEN**

Run: `mvn -q -Dtest=PromptPackContractTest,ConsoleMvcTest test && node --test src/test/js/run-form.test.js`

Expected: Maven focused tests and all Node tests pass.

- [ ] **Step 5: Commit**

```bash
git add README.md src/main/resources/application.yml src/main/resources/application-example.yml \
        src/main/resources/static/js/console-common.js \
        src/test/java/com/sonnet/wyf/gitreport/PromptPackContractTest.java \
        src/test/java/com/sonnet/wyf/gitreport/console/ConsoleMvcTest.java \
        src/test/js/run-form.test.js
git commit -m "docs: describe native Database MCP SQL review"
```

### Task 7: Residual sweep and complete verification

**Files:**
- Modify only files identified by the residual scan or failing tests.

**Interfaces:**
- Consumes: completed Tasks 1-6.
- Produces: a repository state with one native Database MCP vocabulary and a fully passing verification suite.

- [ ] **Step 1: Scan every runtime, prompt, report, config, test, and document surface**

Run targeted `rg` scans over `src/main`, `src/test`, and `README.md` for identifiers outside the native database tool and argument contract, plus wording that does not describe the current runtime. Inspect every match and keep legitimate SQL/domain vocabulary only.

Expected: every database-tool and transport-field match belongs to the native Database MCP contract.

- [ ] **Step 2: Run focused MyBatis suite**

Run: `mvn -q -Dtest='*MyBatis*Test,AgentBridgeClientTest,PromptPackContractTest,ConsoleMvcTest' test`

Expected: all focused tests pass with zero failures and errors.

- [ ] **Step 3: Run complete verification**

Run:

```bash
mvn -q -DskipTests=false test
node --check src/main/resources/static/js/console-common.js
node --test src/test/js/run-form.test.js
git diff --check
```

Expected: Maven has zero failures/errors/skips, Node has zero failures, JavaScript syntax passes, and `git diff --check` emits no output.

- [ ] **Step 4: Review final diff and workspace isolation**

Run:

```bash
git status --short
git diff --stat master...HEAD
git diff master...HEAD -- src/main src/test README.md docs/superpowers
```

Expected: only planned source, test, resource, README, spec, and plan changes are present. Local IDEA configuration is not staged or committed.

- [ ] **Step 5: Commit residual corrections**

```bash
git add README.md src/main src/test docs/superpowers
git commit -m "test: verify native Database MCP SQL review"
```
