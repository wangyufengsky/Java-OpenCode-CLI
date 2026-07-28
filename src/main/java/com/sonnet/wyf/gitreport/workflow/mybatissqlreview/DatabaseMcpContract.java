package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Native Database MCP tool names and the binding shared by SQL review calls.
 */
public final class DatabaseMcpContract {
    public static final String LIST_DATASOURCES = "cmcp_db_database_list_datasources";
    public static final String LIST_DATABASES = "cmcp_db_database_list_databases";
    public static final String LIST_TABLE_SCHEMA = "cmcp_db_database_list_table_schema";
    public static final String EXECUTE_QUERY = "cmcp_db_database_execute_sql_query";
    public static final String EXECUTE_DML = "cmcp_db_database_execute_sql_dml";
    public static final String EXECUTE_DDL = "cmcp_db_database_execute_sql_ddl";
    public static final String EXECUTE_NOSQL_WRITE_DELETE = "cmcp_db_database_execute_nosql_write_delete";
    public static final String EXECUTE_NOSQL_QUERY = "cmcp_db_database_execute_nosql_query";
    public static final int MAX_ROWS = 20;
    public static final int MAX_TABLES = 200;

    private static final Set<String> READ_TOOLS = Set.of(
            LIST_DATASOURCES,
            LIST_DATABASES,
            LIST_TABLE_SCHEMA,
            EXECUTE_QUERY
    );
    private static final Set<String> PROHIBITED_TOOLS = Set.of(
            EXECUTE_DML,
            EXECUTE_DDL,
            EXECUTE_NOSQL_WRITE_DELETE,
            EXECUTE_NOSQL_QUERY
    );
    private static final Set<String> OPTIONAL_INVOCATION_METADATA = Set.of("title");
    private static final Set<String> LIST_TABLE_SCHEMA_OPTIONAL_ARGUMENTS = Set.of(
            "includeColumns",
            "includeIndexes",
            "keywords",
            "maxTables",
            "tablePrefix"
    );

    private final ObjectMapper objectMapper;
    private final Binding binding;

    public DatabaseMcpContract(ObjectMapper objectMapper, Binding binding) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    public static Set<String> readTools() {
        return READ_TOOLS;
    }

    public static Set<String> prohibitedTools() {
        return PROHIBITED_TOOLS;
    }

    public static boolean isReadTool(String toolName) {
        return READ_TOOLS.contains(toolName);
    }

    public static boolean isProhibitedTool(String toolName) {
        return !isReadTool(toolName);
    }

    static boolean isOptionalInvocationMetadata(String field) {
        return OPTIONAL_INVOCATION_METADATA.contains(field);
    }

    static boolean isOptionalToolArgument(String toolName, String field) {
        return LIST_TABLE_SCHEMA.equals(toolName)
                && LIST_TABLE_SCHEMA_OPTIONAL_ARGUMENTS.contains(field);
    }

    static void requireToolSpecificArgumentTypes(String toolName, JsonNode arguments, String callId) {
        if (!LIST_TABLE_SCHEMA.equals(toolName)) {
            return;
        }
        JsonNode keywords = arguments.path("keywords");
        if (!keywords.isMissingNode()) {
            if (!keywords.isArray()) {
                throw new IllegalStateException(
                        "tool call " + callId + " keywords must be an array of strings"
                );
            }
            for (JsonNode keyword : keywords) {
                if (!keyword.isTextual()) {
                    throw new IllegalStateException(
                            "tool call " + callId + " keywords must be an array of strings"
                    );
                }
            }
        }
        JsonNode tablePrefix = arguments.path("tablePrefix");
        if (!tablePrefix.isMissingNode() && !tablePrefix.isTextual()) {
            throw new IllegalStateException("tool call " + callId + " tablePrefix must be a string");
        }
    }

    public ObjectNode dataSourceArguments() {
        return commonArguments();
    }

    public ObjectNode databaseArguments() {
        return commonArguments().put("dataSource", binding.dataSource());
    }

    public ObjectNode tableSchemaArguments() {
        return commonArguments()
                .put("dataSource", binding.dataSource())
                .put("catalog", binding.catalog())
                .put("schema", binding.schema())
                .put("includeColumns", true)
                .put("includeIndexes", true)
                .put("maxTables", MAX_TABLES);
    }

    public ObjectNode tableInventoryArguments() {
        return commonArguments()
                .put("dataSource", binding.dataSource())
                .put("catalog", binding.catalog())
                .put("schema", binding.schema())
                .put("includeColumns", false)
                .put("includeIndexes", false)
                .put("maxTables", MAX_TABLES);
    }

    public ObjectNode queryArguments(String sql) {
        return commonArguments()
                .put("dataSource", binding.dataSource())
                .put("sql", requireText(sql, "sql"))
                .put("maxRows", MAX_ROWS);
    }

    private ObjectNode commonArguments() {
        return objectMapper.createObjectNode()
                .put("project", binding.project().toString())
                .put("scope", binding.scope().name());
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value.strip();
    }

    public enum Scope {
        GLOBAL, PROJECT, ALL;

        public static Scope parse(String value) {
            try {
                return valueOf(value == null ? "" : value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "database.scope must be GLOBAL, PROJECT, or ALL", exception
                );
            }
        }
    }

    public record Binding(String dataSource, String catalog, String schema, Path project, Scope scope) {
        public Binding {
            dataSource = requireText(dataSource, "dataSource");
            catalog = requireText(catalog, "catalog");
            schema = requireText(schema, "schema");
            project = Objects.requireNonNull(project, "project").toAbsolutePath().normalize();
            Objects.requireNonNull(scope, "scope");
        }
    }
}
