package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseMcpContractTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DatabaseMcpContract.Binding binding = new DatabaseMcpContract.Binding(
            "GaussDB-ReadOnly", "orders", "audit",
            Path.of("/workspace/example"), DatabaseMcpContract.Scope.ALL
    );
    private final DatabaseMcpContract contract = new DatabaseMcpContract(objectMapper, binding);

    @Test
    void queryArgumentsUseTheNativeDatabaseMcpSchema() throws Exception {
        JsonNode arguments = contract.queryArguments("SELECT id FROM audit.orders LIMIT 20");

        assertThat(arguments).isEqualTo(objectMapper.readTree("""
                {"dataSource":"GaussDB-ReadOnly","sql":"SELECT id FROM audit.orders LIMIT 20",
                 "maxRows":20,"project":"/workspace/example","scope":"ALL"}
                """));
    }

    @Test
    void metadataArgumentsUseTheConfiguredNativeBinding() throws Exception {
        assertThat(contract.dataSourceArguments()).isEqualTo(objectMapper.readTree("""
                {"project":"/workspace/example","scope":"ALL"}
                """));
        assertThat(contract.databaseArguments()).isEqualTo(objectMapper.readTree("""
                {"dataSource":"GaussDB-ReadOnly","project":"/workspace/example","scope":"ALL"}
                """));
        assertThat(contract.tableSchemaArguments()).isEqualTo(objectMapper.readTree("""
                {"dataSource":"GaussDB-ReadOnly","catalog":"orders","schema":"audit",
                 "includeColumns":true,"includeIndexes":true,"maxTables":200,
                 "project":"/workspace/example","scope":"ALL"}
                """));
    }

    @Test
    void nativeToolSetsAreExactAndImmutable() {
        assertThat(contract.readTools()).containsExactlyInAnyOrder(
                "cmcp_db_database_list_datasources",
                "cmcp_db_database_list_databases",
                "cmcp_db_database_list_table_schema",
                "cmcp_db_database_execute_sql_query"
        );
        assertThat(contract.prohibitedTools()).containsExactlyInAnyOrder(
                "cmcp_db_database_execute_sql_dml",
                "cmcp_db_database_execute_sql_ddl",
                "cmcp_db_database_execute_nosql_write_delete",
                "cmcp_db_database_execute_nosql_query"
        );
        assertThatThrownBy(() -> contract.readTools().add("unexpected"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> contract.prohibitedTools().add("unexpected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scopeParsesCaseInsensitiveConfiguredValues() {
        assertThat(DatabaseMcpContract.Scope.parse(" project "))
                .isEqualTo(DatabaseMcpContract.Scope.PROJECT);
    }

    @Test
    void scopeRejectsUnknownValues() {
        assertThatThrownBy(() -> DatabaseMcpContract.Scope.parse("LOCAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GLOBAL, PROJECT, or ALL");
    }

    @Test
    void bindingAndQueryRejectBlankRequiredValues() {
        assertThatThrownBy(() -> new DatabaseMcpContract.Binding(
                " ", "orders", "audit", Path.of("/workspace/example"), DatabaseMcpContract.Scope.ALL
        )).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dataSource");
        assertThatThrownBy(() -> contract.queryArguments(" \t "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sql");
    }
}
