package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class MyBatisSqlPromptBuilder {
    private static final String PROMPT_RESOURCE =
            "/mybatis-sql-review-prompt-pack/prompts/review-sql.md";
    private final ObjectMapper objectMapper;

    public MyBatisSqlPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String build(Context context) throws IOException {
        Objects.requireNonNull(context, "context");
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.put("statement_key", context.statementKey());
        runtime.put("command_type", context.commandType());
        runtime.put("select_key", context.selectKey());
        runtime.put("normalized_sql", context.normalizedSql());
        runtime.putPOJO("dynamic_nodes", context.dynamicNodes());
        runtime.putPOJO("parameter_placeholders", context.parameterPlaceholders());
        runtime.put("connection_id", context.connectionId());
        runtime.put("database_name", context.databaseName());
        runtime.put("schema_name", context.schemaName());
        runtime.put("candidate_directory", context.candidateDirectory().toAbsolutePath().normalize().toString());

        return readResource(PROMPT_RESOURCE)
                + "\n\n## Runtime task context\n\n```json\n"
                + objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(runtime)
                + "\n```\n";
    }

    private String readResource(String resource) throws IOException {
        try (InputStream input = MyBatisSqlPromptBuilder.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("missing MyBatis SQL prompt resource: " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public record Context(
            String statementKey,
            String commandType,
            boolean selectKey,
            String normalizedSql,
            List<String> dynamicNodes,
            List<String> parameterPlaceholders,
            String connectionId,
            String databaseName,
            String schemaName,
            Path candidateDirectory
    ) {
        public Context {
            Objects.requireNonNull(statementKey, "statementKey");
            Objects.requireNonNull(commandType, "commandType");
            Objects.requireNonNull(normalizedSql, "normalizedSql");
            dynamicNodes = List.copyOf(dynamicNodes == null ? List.of() : dynamicNodes);
            parameterPlaceholders = List.copyOf(parameterPlaceholders == null ? List.of() : parameterPlaceholders);
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(databaseName, "databaseName");
            Objects.requireNonNull(schemaName, "schemaName");
            Objects.requireNonNull(candidateDirectory, "candidateDirectory");
        }
    }
}
