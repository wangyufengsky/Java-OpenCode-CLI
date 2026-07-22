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
    private static final String REPORT_TEMPLATE_RESOURCE =
            "/mybatis-sql-review-prompt-pack/templates/sql-detail-report.md";
    private static final String SUMMARY_SCHEMA_RESOURCE =
            "/mybatis-sql-review-prompt-pack/schemas/sql-summary.schema.json";
    private final ObjectMapper objectMapper;

    public MyBatisSqlPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String build(Context context) throws IOException {
        Objects.requireNonNull(context, "context");
        ObjectNode runtime = objectMapper.createObjectNode();
        runtime.put("statement_key", context.statementKey());
        runtime.put("mapper_relative_path", context.mapperRelativePath());
        runtime.put("namespace", context.namespace());
        runtime.put("statement_id", context.statementId());
        runtime.put("command_type", context.commandType());
        runtime.put("select_key", context.selectKey());
        runtime.put("source_start_line", context.sourceStartLine());
        runtime.put("source_end_line", context.sourceEndLine());
        runtime.put("raw_mapper_xml", context.rawMapperXml());
        runtime.put("normalized_sql", context.normalizedSql());
        runtime.putPOJO("dynamic_nodes", context.dynamicNodes());
        runtime.putPOJO("parameter_placeholders", context.parameterPlaceholders());
        runtime.put("connection_id", context.connectionId());
        runtime.put("database_name", context.databaseName());
        runtime.put("schema_name", context.schemaName());
        Path candidateDirectory = context.candidateDirectory().toAbsolutePath().normalize();
        runtime.put("candidate_directory", candidateDirectory.toString());
        runtime.put("report_path", candidateDirectory.resolve("report.md").toString());
        runtime.put("summary_path", candidateDirectory.resolve("summary.json").toString());
        runtime.put("database_evidence_path", candidateDirectory.resolve("database-evidence.json").toString());

        return readResource(PROMPT_RESOURCE)
                + "\n\n## Complete report template\n\n```markdown\n"
                + readResource(REPORT_TEMPLATE_RESOURCE).strip()
                + "\n```\n\n## Complete summary JSON schema\n\n```json\n"
                + readResource(SUMMARY_SCHEMA_RESOURCE).strip()
                + "\n```"
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
            String mapperRelativePath,
            String namespace,
            String statementId,
            String commandType,
            boolean selectKey,
            int sourceStartLine,
            int sourceEndLine,
            String rawMapperXml,
            String normalizedSql,
            List<String> dynamicNodes,
            List<String> parameterPlaceholders,
            String connectionId,
            String databaseName,
            String schemaName,
            Path candidateDirectory
    ) {
        public Context {
            requireNonBlank(statementKey, "statementKey");
            requireNonBlank(mapperRelativePath, "mapperRelativePath");
            requireNonBlank(namespace, "namespace");
            requireNonBlank(statementId, "statementId");
            requireNonBlank(commandType, "commandType");
            if (sourceStartLine < 1 || sourceEndLine < sourceStartLine) {
                throw new IllegalArgumentException("source line range must be positive and ordered");
            }
            requireNonBlank(rawMapperXml, "rawMapperXml");
            requireNonBlank(normalizedSql, "normalizedSql");
            dynamicNodes = List.copyOf(dynamicNodes == null ? List.of() : dynamicNodes);
            parameterPlaceholders = List.copyOf(parameterPlaceholders == null ? List.of() : parameterPlaceholders);
            requireNonBlank(connectionId, "connectionId");
            requireNonBlank(databaseName, "databaseName");
            requireNonBlank(schemaName, "schemaName");
            Objects.requireNonNull(candidateDirectory, "candidateDirectory");
        }

        private static void requireNonBlank(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must be non-blank");
            }
        }
    }
}
