package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

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

    private final ObjectMapper objectMapper;
    private final Binding binding;

    public DatabaseMcpContract(ObjectMapper objectMapper, Binding binding) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.binding = Objects.requireNonNull(binding, "binding");
    }

    public Set<String> readTools() {
        return READ_TOOLS;
    }

    public Set<String> prohibitedTools() {
        return PROHIBITED_TOOLS;
    }

    public boolean isReadTool(String toolName) {
        return READ_TOOLS.contains(toolName);
    }

    public boolean isProhibitedTool(String toolName) {
        return !isReadTool(toolName);
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
