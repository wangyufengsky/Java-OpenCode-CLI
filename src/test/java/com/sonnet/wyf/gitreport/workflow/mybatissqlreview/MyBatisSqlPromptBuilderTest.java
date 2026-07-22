package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MyBatisSqlPromptBuilderTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void embedsCompleteStaticContractsRuntimeStatementAndThreeAbsoluteOutputPaths() throws Exception {
        Path candidateDirectory = tempDir.resolve("candidate");
        MyBatisSqlPromptBuilder.Context context = new MyBatisSqlPromptBuilder.Context(
                "mapper-order-find-open",
                "mappers/OrderMapper.xml",
                "com.example.OrderMapper",
                "findOpen",
                "select",
                false,
                11,
                19,
                "<select id=\"findOpen\">SELECT * FROM orders WHERE status = #{status}</select>",
                "SELECT * FROM orders WHERE status = ?",
                List.of("if:test=status != null"),
                List.of("status"),
                "gauss-readonly",
                "orders",
                "audit",
                candidateDirectory
        );

        String prompt = new MyBatisSqlPromptBuilder(objectMapper).build(context);

        assertThat(prompt).contains(readResource(
                "/mybatis-sql-review-prompt-pack/templates/sql-detail-report.md").strip());
        String schemaText = readResource(
                "/mybatis-sql-review-prompt-pack/schemas/sql-summary.schema.json");
        JsonNode embeddedSchema = extractJsonFenceAfter(prompt, "## Complete summary JSON schema");
        assertThat(embeddedSchema).isEqualTo(objectMapper.readTree(schemaText));
        assertThat(prompt).contains(
                "mapper-order-find-open",
                "mappers/OrderMapper.xml",
                "com.example.OrderMapper",
                "findOpen",
                "\"source_start_line\" : 11",
                "\"source_end_line\" : 19",
                "<select id=\\\"findOpen\\\">SELECT * FROM orders WHERE status = #{status}</select>",
                "SELECT * FROM orders WHERE status = ?",
                candidateDirectory.resolve("report.md").toAbsolutePath().normalize().toString(),
                candidateDirectory.resolve("summary.json").toAbsolutePath().normalize().toString(),
                candidateDirectory.resolve("database-evidence.json").toAbsolutePath().normalize().toString()
        );
        assertThat(prompt).doesNotContain("{{", "}}", "<<", ">>");
    }

    @Test
    void statesTheHardAndPostHocSafetyBoundariesWithoutConflatingNativeWrites() throws Exception {
        String prompt = new MyBatisSqlPromptBuilder(objectMapper).build(context());

        assertThat(prompt)
                .contains("non-owner, non-admin, read-only account")
                .contains("only hard safety boundary")
                .contains("post-hoc audit cannot prevent or undo")
                .contains("confirmed database/server/role statement_timeout")
                .contains("30 seconds")
                .contains("native filesystem writes")
                .contains("exact absolute paths")
                .contains("unknown, quoted, or schema-qualified function")
                .contains("COUNT", "COALESCE", "LOWER");
    }

    private MyBatisSqlPromptBuilder.Context context() {
        return new MyBatisSqlPromptBuilder.Context(
                "mapper-order-find-open",
                "mappers/OrderMapper.xml",
                "com.example.OrderMapper",
                "findOpen",
                "select",
                false,
                11,
                19,
                "<select id=\"findOpen\">SELECT id FROM orders</select>",
                "SELECT id FROM orders",
                List.of(),
                List.of(),
                "gauss-readonly",
                "orders",
                "audit",
                tempDir.resolve("candidate")
        );
    }

    private String readResource(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private JsonNode extractJsonFenceAfter(String content, String heading) throws Exception {
        int headingIndex = content.indexOf(heading);
        int fenceStart = content.indexOf("```json\n", headingIndex) + "```json\n".length();
        int fenceEnd = content.indexOf("\n```", fenceStart);
        return objectMapper.readTree(content.substring(fenceStart, fenceEnd));
    }
}
