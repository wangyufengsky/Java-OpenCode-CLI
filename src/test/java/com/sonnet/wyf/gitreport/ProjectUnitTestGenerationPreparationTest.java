package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationPreparation;
import com.sonnet.wyf.gitreport.workflow.unittest.ProjectUnitTestGenerationProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectUnitTestGenerationPreparationTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @TempDir
    Path tempDir;

    @Test
    void fullScanBuildsJavaParserBackedBatchesAndDiscoversExistingTests() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getDocs().setAgents(Path.of("docs/custom-agents.md"));
        writeProjectFiles(properties.getProject().getRepo());
        Files.createDirectories(properties.getProject().getRepo().resolve("src/test/java/com/acme/order"));
        Files.writeString(properties.getProject().getRepo().resolve("src/test/java/com/acme/order/OrderServiceTest.java"), "class OrderServiceTest {}\n");

        Path planPath = new ProjectUnitTestGenerationPreparation(objectMapper).prepare(properties, true);

        JsonNode plan = objectMapper.readTree(planPath.toFile());
        assertThat(plan.path("schema_version").asText()).isEqualTo("project-unit-test-generation/v1");
        assertThat(plan.path("docs").path("warnings").get(0).asText()).contains("missing");
        assertThat(plan.path("source_files")).hasSize(3);
        JsonNode service = firstType(plan, "com.acme.order.OrderService");
        assertThat(service.path("role").asText()).isEqualTo("service");
        assertThat(service.path("public_methods").get(0).path("signature").asText()).isEqualTo("place(String sku) throws java.io.IOException");
        assertThat(service.path("target_test_file").asText()).isEqualTo("src/test/java/com/acme/order/OrderServiceTest.java");
        assertThat(service.path("existing_test_files").get(0).asText()).isEqualTo("src/test/java/com/acme/order/OrderServiceTest.java");

        JsonNode batches = objectMapper.readTree(properties.getPaths().getOut().resolve("test-batches.json").toFile());
        assertThat(batches.path("batches")).hasSize(2);
        assertThat(batches.path("batches").get(0).path("docs").path("agents").asText()).isEqualTo("docs/custom-agents.md");
        assertThat(batches.path("batches").get(0).path("skill").path("preferred_diagnostics").asText()).isEqualTo("AgentBridge");
        assertThat(batches.path("batches").get(0).path("skill").path("test_tools")).extracting(JsonNode::asText)
                .containsExactly("list_tests", "run_tests", "get_coverage", "get_compilation_errors", "build_project");
        assertThat(batches.path("batches").get(0).path("rules").path("diagnostics_policy").asText())
                .contains("get_compilation_errors", "写完或修改测试文件后");
        assertThat(batches.path("batches").get(0).path("rules").path("test_feedback_policy").asText())
                .contains("list_tests", "get_coverage")
                .contains("不要调用 run_tests 或 build_project");
        assertThat(Files.exists(properties.getPaths().getOut().resolve("test-batches")
                .resolve(batches.path("batches").get(0).path("batch_id").asText())
                .resolve("input.json"))).isTrue();
    }

    @Test
    void packagePathsAcceptPackageNameSourcePathAndSlashPath() throws Exception {
        for (String packagePath : List.of("com.acme.order", "src/main/java/com/acme/order", "com/acme/order")) {
            ProjectUnitTestGenerationProperties properties = properties();
            properties.getPaths().setOut(tempDir.resolve("out-" + packagePath.replaceAll("[^A-Za-z0-9]+", "-")));
            properties.getSource().setPackagePaths(List.of(packagePath));
            writeProjectFiles(properties.getProject().getRepo());

            Path planPath = new ProjectUnitTestGenerationPreparation(objectMapper).prepare(properties, true);

            JsonNode plan = objectMapper.readTree(planPath.toFile());
            assertThat(plan.path("source_files")).extracting(JsonNode::asText)
                    .containsExactlyInAnyOrder(
                            "src/main/java/com/acme/order/OrderController.java",
                            "src/main/java/com/acme/order/OrderService.java"
                    );
        }
    }

    @Test
    void fullScanDiscoversMavenModulesAndKeepsModuleTestPaths() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        writeMultiModuleProject(properties.getProject().getRepo());

        Path planPath = new ProjectUnitTestGenerationPreparation(objectMapper).prepare(properties, true);

        JsonNode plan = objectMapper.readTree(planPath.toFile());
        assertThat(plan.path("source_files")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(
                        "upfs-common/src/main/java/com/spdb/upfs/common/CommonService.java",
                        "upfs-cup/src/main/java/com/spdb/upfs/cup/CupService.java"
                );
        assertThat(firstType(plan, "com.spdb.upfs.cup.CupService").path("target_test_file").asText())
                .isEqualTo("upfs-cup/src/test/java/com/spdb/upfs/cup/CupServiceTest.java");
        assertThat(firstType(plan, "com.spdb.upfs.common.CommonService").path("target_test_file").asText())
                .isEqualTo("upfs-common/src/test/java/com/spdb/upfs/common/CommonServiceTest.java");

        JsonNode batches = objectMapper.readTree(properties.getPaths().getOut().resolve("test-batches.json").toFile());
        assertThat(batches.path("batches").get(0).path("allowed_write_globs")).extracting(JsonNode::asText)
                .containsExactly("upfs-common/src/test/**", "upfs-cup/src/test/**");
    }

    @Test
    void packagePathsCanLimitSpecificMavenModule() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        properties.getSource().setPackagePaths(List.of("upfs-cup/src/main/java/com/spdb/upfs/cup"));
        writeMultiModuleProject(properties.getProject().getRepo());

        Path planPath = new ProjectUnitTestGenerationPreparation(objectMapper).prepare(properties, true);

        JsonNode plan = objectMapper.readTree(planPath.toFile());
        assertThat(plan.path("source_files")).extracting(JsonNode::asText)
                .containsExactly("upfs-cup/src/main/java/com/spdb/upfs/cup/CupService.java");
    }

    @Test
    void parseFailureStillCreatesFallbackTask() throws Exception {
        ProjectUnitTestGenerationProperties properties = properties();
        Path broken = properties.getProject().getRepo().resolve("src/main/java/com/acme/Broken.java");
        Files.createDirectories(broken.getParent());
        Files.writeString(broken, "package com.acme; class Broken { void nope( }\n");

        Path planPath = new ProjectUnitTestGenerationPreparation(objectMapper).prepare(properties, true);

        JsonNode plan = objectMapper.readTree(planPath.toFile());
        JsonNode type = plan.path("types").get(0);
        assertThat(type.path("qualified_name").asText()).isEqualTo("com.acme.Broken");
        assertThat(type.path("parse_error").asText()).isNotBlank();
        assertThat(type.path("target_test_file").asText()).isEqualTo("src/test/java/com/acme/BrokenTest.java");
    }

    private JsonNode firstType(JsonNode plan, String qualifiedName) {
        for (JsonNode type : plan.path("types")) {
            if (qualifiedName.equals(type.path("qualified_name").asText())) {
                return type;
            }
        }
        throw new AssertionError("missing type: " + qualifiedName);
    }

    private ProjectUnitTestGenerationProperties properties() {
        ProjectUnitTestGenerationProperties properties = new ProjectUnitTestGenerationProperties();
        properties.getProject().setId("demo");
        properties.getProject().setName("Demo");
        properties.getProject().setRepo(tempDir.resolve("repo"));
        properties.getPaths().setOut(tempDir.resolve("out"));
        properties.getTest().setMaxTypesPerTask(2);
        properties.getTest().setMaxMethodsPerTask(8);
        properties.getTest().setMaxSourceCharsPerTask(4000);
        return properties;
    }

    private void writeProjectFiles(Path repo) throws Exception {
        Files.createDirectories(repo.resolve("src/main/java/com/acme/order"));
        Files.createDirectories(repo.resolve("src/main/java/com/acme/user"));
        Files.writeString(repo.resolve("src/main/java/com/acme/order/OrderService.java"), """
                package com.acme.order;

                import org.springframework.stereotype.Service;
                import java.io.IOException;

                @Service
                public class OrderService {
                    private final OrderRepository repository;

                    public OrderService(OrderRepository repository) {
                        this.repository = repository;
                    }

                    public String place(String sku) throws IOException {
                        return repository.save(sku);
                    }
                }
                """);
        Files.writeString(repo.resolve("src/main/java/com/acme/order/OrderController.java"), """
                package com.acme.order;

                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class OrderController {
                    public String ping() {
                        return "ok";
                    }
                }
                """);
        Files.writeString(repo.resolve("src/main/java/com/acme/user/UserHelper.java"), """
                package com.acme.user;

                public final class UserHelper {
                    private UserHelper() {
                    }

                    public static String normalize(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """);
    }

    private void writeMultiModuleProject(Path repo) throws Exception {
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.spdb</groupId>
                  <artifactId>upfs-nl-json</artifactId>
                  <version>1.0.0</version>
                  <packaging>pom</packaging>
                  <modules>
                    <module>upfs-common</module>
                    <module>upfs-cup</module>
                  </modules>
                </project>
                """);
        Files.createDirectories(repo.resolve("upfs-common/src/main/java/com/spdb/upfs/common"));
        Files.createDirectories(repo.resolve("upfs-cup/src/main/java/com/spdb/upfs/cup"));
        Files.writeString(repo.resolve("upfs-common/src/main/java/com/spdb/upfs/common/CommonService.java"), """
                package com.spdb.upfs.common;
                public class CommonService {
                    public String normalize(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """);
        Files.writeString(repo.resolve("upfs-cup/src/main/java/com/spdb/upfs/cup/CupService.java"), """
                package com.spdb.upfs.cup;
                public class CupService {
                    public String handle(String value) {
                        return value;
                    }
                }
                """);
    }
}
