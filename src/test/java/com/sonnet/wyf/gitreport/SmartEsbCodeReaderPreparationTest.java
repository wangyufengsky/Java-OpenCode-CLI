package com.sonnet.wyf.gitreport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderPreparation;
import com.sonnet.wyf.gitreport.workflow.smartesbreader.SmartEsbCodeReaderProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SmartEsbCodeReaderPreparationTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void preparesDedupedModuleAndTransactionTasksFromMultipleServiceIdentifyFiles() throws Exception {
        Path project = tempDir.resolve("project");
        Path xmlRoot = project.resolve("xml");
        Path javaRoot = project.resolve("src/main/java");
        Path bizRoot = project.resolve("biz");
        Files.createDirectories(xmlRoot);
        Files.createDirectories(javaRoot.resolve("com/example"));
        Files.createDirectories(bizRoot);
        Files.writeString(project.resolve("serviceIdentify-a.xml"), """
                <channels>
                  <switch mode="8583">
                    <case target="CaConsumeCUPS2ECI"/>
                    <case target="CaRefundCUPS2ECI"/>
                  </switch>
                  <switch mode="json">
                    <case target="IgnoredJson"/>
                  </switch>
                </channels>
                """);
        Files.writeString(project.resolve("serviceIdentify-b.xml"), """
                <channels>
                  <switch mode="8583">
                    <case target="CaConsumeCUPS2ECI"/>
                  </switch>
                </channels>
                """);
        Files.writeString(xmlRoot.resolve("CaConsume.xml"), """
                <proxyEngine>
                  <route>
                    <to serviceId="BaseConvert8583CUPS"/>
                    <process ref="BaseRiskCheck"/>
                  </route>
                </proxyEngine>
                """);
        Files.writeString(xmlRoot.resolve("CaRefund.xml"), """
                <proxyEngine>
                  <route>
                    <to serviceId="BaseConvert8583CUPS"/>
                  </route>
                </proxyEngine>
                """);
        Files.writeString(xmlRoot.resolve("BaseConvert8583CUPS.xml"), "<base class=\"BaseConvert8583CUPS\"/>");
        Files.writeString(bizRoot.resolve("BaseConvert8583CUPS.biz"), "biz");
        Files.writeString(javaRoot.resolve("com/example/BaseConvert8583CUPS.java"), "class BaseConvert8583CUPS {}\n");
        Files.writeString(javaRoot.resolve("com/example/BaseRiskCheck.java"), "class BaseRiskCheck {}\n");

        SmartEsbCodeReaderProperties properties = new SmartEsbCodeReaderProperties();
        properties.setOut(tempDir.resolve("logical-out").toString());
        properties.setLocalOut(tempDir.resolve("local-out"));
        properties.setServiceIdentify(List.of(project.resolve("serviceIdentify-a.xml"), project.resolve("serviceIdentify-b.xml")));
        properties.setXmlRoot(xmlRoot);
        properties.setBizRoot(bizRoot);
        properties.setJavaRoot(javaRoot);

        Path out = new SmartEsbCodeReaderPreparation(objectMapper).prepare(properties, true);

        assertThat(out).isEqualTo(tempDir.resolve("local-out"));
        assertThat(out.resolve("tasks/batches")).doesNotExist();
        assertThat(out.resolve("tasks/transaction-CaConsume.json")).exists();
        assertThat(out.resolve("tasks/transaction-CaRefund.json")).exists();
        assertThat(out.resolve("tasks/module-BaseConvert8583CUPS.json")).exists();
        assertThat(out.resolve("tasks/module-BaseRiskCheck.json")).exists();
        assertThat(out.resolve("modules/BaseConvert8583CUPS/analysis.md")).content()
                .contains("# 模块阅读：BaseConvert8583CUPS", "{{MODULE_ANALYSIS}}");
        assertThat(out.resolve("transactions/CaConsume/analysis.md")).content()
                .contains("# 交易阅读：CaConsume", "{{TRANSACTION_ANALYSIS}}");

        JsonNode summary = objectMapper.readTree(out.resolve("summary.json").toFile());
        assertThat(summary.path("raw_case_count").asInt()).isEqualTo(3);
        assertThat(summary.path("deduped_transaction_count").asInt()).isEqualTo(2);
        assertThat(summary.path("unique_module_count").asInt()).isEqualTo(2);
        assertThat(summary.path("module_batch_count").asInt()).isZero();
        assertThat(summary.path("module_batch_paths")).isEmpty();
        assertThat(summary.path("transaction_task_paths")).hasSize(2);

        JsonNode indexInputs = objectMapper.readTree(out.resolve("index_inputs.json").toFile());
        assertThat(indexInputs.path("transaction_task_paths")).hasSize(2);
        assertThat(indexInputs.path("module_task_paths")).hasSize(2);
        assertThat(fieldValues(indexInputs.path("transactions"), "transaction_key"))
                .containsExactly("CaConsume", "CaRefund");
        assertThat(fieldValues(indexInputs.path("modules"), "serviceId"))
                .containsExactly("BaseConvert8583CUPS", "BaseRiskCheck");
        assertThat(indexInputs.path("modules").get(0).path("document_link").asText())
                .isEqualTo("modules/BaseConvert8583CUPS/analysis.md");

        JsonNode transactionTask = objectMapper.readTree(out.resolve("tasks/transaction-CaConsume.json").toFile());
        assertThat(transactionTask.path("review_type").asText()).isEqualTo("transaction");
        assertThat(transactionTask.path("transaction_key").asText()).isEqualTo("CaConsume");
        assertThat(transactionTask.path("transaction_xml").asText()).endsWith("CaConsume.xml");
        assertThat(textValues(transactionTask.path("module_service_ids"))).contains("BaseConvert8583CUPS", "BaseRiskCheck");
        assertThat(transactionTask.path("module_document_links").path("BaseConvert8583CUPS").asText())
                .isEqualTo("../../modules/BaseConvert8583CUPS/analysis.md");
        assertThat(transactionTask.path("flow_summary").path("nodes")).hasSize(2);

        JsonNode moduleTask = objectMapper.readTree(out.resolve("tasks/module-BaseConvert8583CUPS.json").toFile());
        assertThat(moduleTask.path("review_type").asText()).isEqualTo("module");
        assertThat(moduleTask.path("serviceId").asText()).isEqualTo("BaseConvert8583CUPS");
        assertThat(textValues(moduleTask.path("base_xml_candidates"))).anyMatch(value -> value.endsWith("BaseConvert8583CUPS.xml"));
        assertThat(textValues(moduleTask.path("biz_candidates"))).anyMatch(value -> value.endsWith("BaseConvert8583CUPS.biz"));
        assertThat(textValues(moduleTask.path("java_candidates"))).anyMatch(value -> value.endsWith("BaseConvert8583CUPS.java"));
        assertThat(textValues(moduleTask.path("used_by_transactions"))).containsExactly("CaConsume", "CaRefund");
    }

    private List<String> textValues(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asText()));
        return values;
    }

    private List<String> fieldValues(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.path(field).asText()));
        return values;
    }
}
