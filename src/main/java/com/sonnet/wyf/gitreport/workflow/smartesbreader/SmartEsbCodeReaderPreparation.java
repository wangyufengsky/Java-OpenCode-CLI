package com.sonnet.wyf.gitreport.workflow.smartesbreader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sonnet.wyf.gitreport.util.LogicalPaths;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SmartEsbCodeReaderPreparation {
    private static final List<String> REFERENCE_ATTRS = List.of("target", "ref", "route", "service", "serviceId");
    private static final List<String> FALLBACK_REFERENCE_ATTRS = List.of("id", "name", "value");
    private static final List<String> TRANSACTION_REF_SUFFIXES = List.of("CUPS2ECI");
    private static final Pattern NON_KEY_CHARS = Pattern.compile("[^A-Za-z0-9_.-]+");
    private final ObjectMapper objectMapper;
    private final SmartEsbCodeReaderXmlSupport xmlSupport = new SmartEsbCodeReaderXmlSupport();

    public SmartEsbCodeReaderPreparation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path prepare(SmartEsbCodeReaderProperties properties, boolean overwrite) throws Exception {
        if (!isAbsoluteLogicalPath(properties.getOut())) {
            throw new IllegalArgumentException("SmartESB code-reader out must be an absolute path: " + properties.getOut());
        }
        Path out = properties.getLocalOut() == null ? Path.of(properties.getOut()) : properties.getLocalOut();
        if (Files.exists(out) && anyChild(out) && !overwrite) {
            throw new IllegalStateException("SmartESB code-reader output already exists and is not empty: " + out);
        }
        Files.createDirectories(out);
        Files.createDirectories(out.resolve("tasks"));
        Files.createDirectories(out.resolve("modules"));
        Files.createDirectories(out.resolve("transactions"));

        List<Path> xmlFiles = xmlSupport.collectFiles(properties.getXmlRoot(), ".xml");
        List<Path> bizFiles = xmlSupport.collectFiles(properties.getBizRoot(), ".biz");
        List<Path> javaFiles = xmlSupport.collectFiles(properties.getJavaRoot(), ".java");
        List<CaseRef> caseRefs = collectCaseRefs(properties);
        Map<String, TransactionFact> transactions = dedupeTransactions(caseRefs, xmlFiles, properties.getXmlRoot());
        Map<String, ModuleFact> modules = collectModules(transactions, xmlFiles, bizFiles, javaFiles);

        List<Map<String, Object>> moduleTasks = new ArrayList<>();
        for (ModuleFact module : modules.values()) {
            moduleTasks.add(writeModuleTask(properties, out, module, overwrite));
        }
        List<Map<String, Object>> transactionTasks = new ArrayList<>();
        for (TransactionFact transaction : transactions.values()) {
            transactionTasks.add(writeTransactionTask(properties, out, transaction, modules, overwrite));
        }

        writeTopLevelFiles(properties, out, overwrite);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("generated_at", OffsetDateTime.now().toString());
        summary.put("service_identify", properties.getServiceIdentify().size() == 1 ? properties.getServiceIdentify().get(0).toString() : null);
        summary.put("service_identifies", properties.getServiceIdentify().stream().map(Path::toString).toList());
        summary.put("xml_root", properties.getXmlRoot().toString());
        summary.put("biz_root", properties.getBizRoot().toString());
        summary.put("java_root", properties.getJavaRoot().toString());
        summary.put("out", normalizeLogical(properties.getOut()));
        summary.put("local_out", properties.getLocalOut() == null ? null : out.toString());
        summary.put("mode", properties.getMode());
        summary.put("raw_case_count", caseRefs.size());
        summary.put("deduped_transaction_count", transactions.size());
        summary.put("unique_module_count", modules.size());
        summary.put("batch_size", 0);
        summary.put("module_batch_count", 0);
        summary.put("module_batch_paths", List.of());
        summary.put("module_task_paths", moduleTasks.stream().map(task -> task.get("task_path")).toList());
        summary.put("transaction_task_count", transactionTasks.size());
        summary.put("transaction_task_paths", transactionTasks.stream().map(task -> task.get("task_path")).toList());
        summary.put("missing_references", missingReferences(transactions));

        Map<String, Object> indexInputs = new LinkedHashMap<>();
        indexInputs.put("summary", summary);
        indexInputs.put("batch_size", 0);
        indexInputs.put("module_batch_count", 0);
        indexInputs.put("module_batch_paths", List.of());
        indexInputs.put("module_task_paths", moduleTasks.stream().map(task -> task.get("task_path")).toList());
        indexInputs.put("transaction_task_count", transactionTasks.size());
        indexInputs.put("transaction_task_paths", transactionTasks.stream().map(task -> task.get("task_path")).toList());
        indexInputs.put("modules", moduleTasks.stream().map(this::indexModuleEntry).toList());
        indexInputs.put("transactions", transactionTasks.stream().map(this::indexTransactionEntry).toList());
        indexInputs.put("missing_references", summary.get("missing_references"));
        indexInputs.put("output", Map.of("index_md", appendLogical(agentOut(properties, out), "index.md")));

        writeJson(out.resolve("summary.json"), summary);
        writeJson(out.resolve("index_inputs.json"), indexInputs);
        return out;
    }

    private List<CaseRef> collectCaseRefs(SmartEsbCodeReaderProperties properties) throws Exception {
        List<CaseRef> refs = new ArrayList<>();
        for (Path serviceIdentify : properties.getServiceIdentify()) {
            Document document = xmlSupport.parseXml(serviceIdentify);
            NodeList switches = document.getElementsByTagName("switch");
            for (int i = 0; i < switches.getLength(); i++) {
                Element switchNode = (Element) switches.item(i);
                if (!Objects.equals(properties.getMode(), switchNode.getAttribute("mode"))) {
                    continue;
                }
                NodeList children = switchNode.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node child = children.item(j);
                    if (child instanceof Element element && "case".equals(xmlSupport.localName(element))) {
                        String ref = transactionRefFromCase(element);
                        if (!ref.isBlank()) {
                            refs.add(new CaseRef(serviceIdentify, ref));
                        }
                    }
                }
            }
        }
        return refs;
    }

    private Map<String, TransactionFact> dedupeTransactions(List<CaseRef> caseRefs, List<Path> xmlFiles, Path xmlRoot) throws Exception {
        Map<String, TransactionFact> transactions = new LinkedHashMap<>();
        for (CaseRef caseRef : caseRefs) {
            String matchRef = transactionRefForMatch(caseRef.ref());
            String key = normalizeKey(Path.of(matchRef).getFileName().toString());
            TransactionFact fact = transactions.computeIfAbsent(key, ignored -> {
                List<Path> candidates = findXmlCandidates(matchRef, xmlFiles, xmlRoot);
                Path primary = candidates.isEmpty() ? null : candidates.get(0);
                return new TransactionFact(key, caseRef.ref(), matchRef, primary, candidates);
            });
            fact.aliases.add(caseRef.ref());
        }
        for (TransactionFact fact : transactions.values()) {
            if (fact.transactionXml != null) {
                fact.flowNodes.addAll(xmlSupport.flowNodes(fact.transactionXml));
            }
        }
        return transactions;
    }

    private Map<String, ModuleFact> collectModules(Map<String, TransactionFact> transactions, List<Path> xmlFiles, List<Path> bizFiles, List<Path> javaFiles) throws Exception {
        Map<String, ModuleFact> modules = new LinkedHashMap<>();
        for (TransactionFact transaction : transactions.values()) {
            for (SmartEsbFlowNode flowNode : transaction.flowNodes) {
                String serviceId = flowNode.serviceId();
                if (serviceId.isBlank() || serviceId.equals(transaction.transactionKey)) {
                    continue;
                }
                transaction.moduleServiceIds.add(serviceId);
                ModuleFact module = modules.computeIfAbsent(serviceId, ModuleFact::new);
                module.usedByTransactions.add(transaction.transactionKey);
            }
        }
        for (ModuleFact module : modules.values()) {
            module.baseXmlCandidates.addAll(findNamedCandidates(module.serviceId, xmlFiles));
            module.bizCandidates.addAll(findNamedCandidates(module.serviceId, bizFiles));
            module.javaCandidates.addAll(findNamedCandidates(module.serviceId, javaFiles));
            for (Path baseXml : module.baseXmlCandidates) {
                module.javaHints.addAll(xmlSupport.javaHints(baseXml));
            }
            for (String hint : module.javaHints) {
                module.javaCandidates.addAll(findNamedCandidates(hint, javaFiles));
            }
            module.javaCandidates = distinct(module.javaCandidates);
        }
        return modules;
    }

    private Map<String, Object> writeModuleTask(SmartEsbCodeReaderProperties properties, Path out, ModuleFact module, boolean overwrite) throws IOException {
        String slug = slugify(module.serviceId);
        Path moduleDir = out.resolve("modules").resolve(slug);
        Files.createDirectories(moduleDir);
        writeTextIfMissing(moduleDir.resolve("analysis.md"), "# 模块阅读：" + module.serviceId + "\n\n{{MODULE_ANALYSIS}}\n", overwrite);
        writeTextIfMissing(moduleDir.resolve("summary.json"), "{}\n", overwrite);
        String logicalDir = appendLogical(agentOut(properties, out), "modules", slug);
        String logicalTask = appendLogical(agentOut(properties, out), "tasks", "module-" + slug + ".json");
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("review_type", "module");
        task.put("serviceId", module.serviceId);
        task.put("base_xml_candidates", module.baseXmlCandidates.stream().map(Path::toString).toList());
        task.put("biz_candidates", module.bizCandidates.stream().map(Path::toString).toList());
        task.put("java_candidates", module.javaCandidates.stream().map(Path::toString).toList());
        task.put("document_path", appendLogical(logicalDir, "analysis.md"));
        task.put("document_link", "modules/" + slug + "/analysis.md");
        task.put("summary_path", appendLogical(logicalDir, "summary.json"));
        task.put("summary_link", "modules/" + slug + "/summary.json");
        task.put("task_path", logicalTask);
        task.put("used_by_transactions", new ArrayList<>(module.usedByTransactions));
        task.put("rules", taskRules());
        writeJson(out.resolve("tasks").resolve("module-" + slug + ".json"), task);
        return task;
    }

    private Map<String, Object> writeTransactionTask(
            SmartEsbCodeReaderProperties properties,
            Path out,
            TransactionFact transaction,
            Map<String, ModuleFact> modules,
            boolean overwrite
    ) throws IOException {
        String slug = slugify(transaction.transactionKey);
        Path transactionDir = out.resolve("transactions").resolve(slug);
        Files.createDirectories(transactionDir);
        writeTextIfMissing(transactionDir.resolve("analysis.md"), "# 交易阅读：" + transaction.transactionKey + "\n\n{{TRANSACTION_ANALYSIS}}\n", overwrite);
        writeTextIfMissing(transactionDir.resolve("summary.json"), "{}\n", overwrite);
        String logicalDir = appendLogical(agentOut(properties, out), "transactions", slug);
        String logicalTask = appendLogical(agentOut(properties, out), "tasks", "transaction-" + slug + ".json");
        Map<String, String> moduleLinks = new LinkedHashMap<>();
        for (String serviceId : transaction.moduleServiceIds) {
            moduleLinks.put(serviceId, "../../modules/" + slugify(serviceId) + "/analysis.md");
        }
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("review_type", "transaction");
        task.put("transaction_key", transaction.transactionKey);
        task.put("transaction_ref", transaction.transactionRef);
        task.put("transaction_match_ref", transaction.transactionMatchRef);
        task.put("transaction_xml", transaction.transactionXml == null ? null : transaction.transactionXml.toString());
        task.put("transaction_xml_candidates", transaction.transactionXmlCandidates.stream().map(Path::toString).toList());
        task.put("document_path", appendLogical(logicalDir, "analysis.md"));
        task.put("document_link", "transactions/" + slug + "/analysis.md");
        task.put("summary_path", appendLogical(logicalDir, "summary.json"));
        task.put("summary_link", "transactions/" + slug + "/summary.json");
        task.put("task_path", logicalTask);
        task.put("module_service_ids", new ArrayList<>(transaction.moduleServiceIds));
        task.put("module_document_links", moduleLinks);
        task.put("flow_summary", flowSummary(transaction));
        task.put("rules", taskRules());
        writeJson(out.resolve("tasks").resolve("transaction-" + slug + ".json"), task);
        return task;
    }

    private Map<String, Object> flowSummary(TransactionFact transaction) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        int step = 1;
        for (SmartEsbFlowNode node : transaction.flowNodes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("step", step++);
            row.put("tag", node.tag());
            row.put("xml_path", node.xmlPath());
            row.put("serviceId", node.serviceId());
            row.put("attributes", node.attributes());
            nodes.add(row);
        }
        return Map.of(
                "nodes", nodes,
                "service_ids_in_order", new ArrayList<>(transaction.moduleServiceIds),
                "flow_node_count", nodes.size()
        );
    }

    private Map<String, Object> taskRules() {
        return Map.of(
                "reader_preference", "读取任务输入、XML、.biz、Java 候选文件和摘要时，必须使用 `AgentBridge` MCP 文件读取工具：read_file。",
                "writer_preference", "写入 Markdown 和 JSON 报告时，必须使用 `AgentBridge` MCP 文件编辑工具：edit_text 或 write_file。",
                "blocked_policy", "证据不足时写入 risks_or_uncertainties 并完成最小输出，不因分析不完整跳过输出。",
                "external_skill_policy", "不要在执行前搜索、读取、加载或调用任何外部 skill、SKILL.md、超能力规则或通用规划能力。"
        );
    }

    private String agentOut(SmartEsbCodeReaderProperties properties, Path out) {
        return properties.getLocalOut() == null ? properties.getOut() : out.toString();
    }

    private void writeTopLevelFiles(SmartEsbCodeReaderProperties properties, Path out, boolean overwrite) throws IOException {
        writeTextIfMissing(out.resolve("index.md"), "# Sm@rtESB 代码阅读索引\n\n{{INDEX_BODY}}\n", overwrite);
    }

    private Map<String, Object> indexModuleEntry(Map<String, Object> task) {
        return Map.of(
                "serviceId", task.get("serviceId"),
                "task_path", task.get("task_path"),
                "document_link", task.get("document_link"),
                "summary_link", task.get("summary_link"),
                "used_by_transactions", task.get("used_by_transactions")
        );
    }

    private Map<String, Object> indexTransactionEntry(Map<String, Object> task) {
        return Map.of(
                "transaction_key", task.get("transaction_key"),
                "task_path", task.get("task_path"),
                "document_link", task.get("document_link"),
                "summary_link", task.get("summary_link"),
                "module_service_ids", task.get("module_service_ids"),
                "flow_node_count", ((Map<?, ?>) task.get("flow_summary")).get("flow_node_count")
        );
    }

    private List<Map<String, Object>> missingReferences(Map<String, TransactionFact> transactions) {
        return transactions.values().stream()
                .filter(fact -> fact.transactionXml == null)
                .map(fact -> Map.<String, Object>of("transaction_key", fact.transactionKey, "transaction_ref", fact.transactionRef))
                .toList();
    }

    private String transactionRefFromCase(Element element) {
        for (String attr : REFERENCE_ATTRS) {
            String value = element.getAttribute(attr).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        String text = element.getTextContent() == null ? "" : element.getTextContent().trim();
        if (!text.isBlank()) {
            return text;
        }
        for (String attr : FALLBACK_REFERENCE_ATTRS) {
            String value = element.getAttribute(attr).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String transactionRefForMatch(String ref) {
        String raw = ref.replace('\\', '/').trim();
        String body = raw.toLowerCase(Locale.ROOT).endsWith(".xml") ? raw.substring(0, raw.length() - 4) : raw;
        String suffix = raw.toLowerCase(Locale.ROOT).endsWith(".xml") ? raw.substring(raw.length() - 4) : "";
        for (String removable : TRANSACTION_REF_SUFFIXES) {
            if (body.endsWith(removable)) {
                return body.substring(0, body.length() - removable.length()) + suffix;
            }
        }
        return raw;
    }

    private List<Path> findXmlCandidates(String ref, List<Path> xmlFiles, Path xmlRoot) {
        String raw = ref.replace('\\', '/').trim();
        List<Path> candidates = new ArrayList<>();
        Path direct = Path.of(raw);
        List<Path> directPaths = new ArrayList<>();
        if (direct.isAbsolute()) {
            directPaths.add(direct);
        } else {
            directPaths.add(xmlRoot.resolve(raw));
            if (!raw.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                directPaths.add(xmlRoot.resolve(raw + ".xml"));
            }
        }
        for (Path path : directPaths) {
            if (Files.isRegularFile(path) && !candidates.contains(path)) {
                candidates.add(path);
            }
        }
        candidates.addAll(findNamedCandidates(Path.of(raw).getFileName().toString(), xmlFiles));
        return distinct(candidates);
    }

    private List<Path> findNamedCandidates(String name, List<Path> files) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String stem = stripExtension(Path.of(name).getFileName().toString());
        String comparable = comparableName(stem);
        return files.stream()
                .filter(path -> comparableName(stripExtension(path.getFileName().toString())).equals(comparable)
                        || comparableName(stripExtension(path.getFileName().toString())).contains(comparable)
                        || comparable.contains(comparableName(stripExtension(path.getFileName().toString()))))
                .distinct()
                .sorted()
                .toList();
    }

    private boolean anyChild(Path path) throws IOException {
        try (Stream<Path> stream = Files.list(path)) {
            return stream.findAny().isPresent();
        }
    }

    private void writeTextIfMissing(Path path, String content, boolean overwrite) throws IOException {
        if (overwrite || !Files.exists(path)) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
    }

    private void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    static boolean isAbsoluteLogicalPath(String path) {
        return LogicalPaths.isAbsolute(path);
    }

    static String appendLogical(String base, String... segments) {
        return LogicalPaths.append(base, segments);
    }

    private static String normalizeLogical(String value) {
        return LogicalPaths.normalize(value);
    }

    private static String normalizeKey(String value) {
        String normalized = value.replace('\\', '/').trim();
        normalized = normalized.toLowerCase(Locale.ROOT).endsWith(".xml")
                ? normalized.substring(0, normalized.length() - 4)
                : normalized;
        normalized = NON_KEY_CHARS.matcher(normalized).replaceAll("_");
        normalized = normalized.replaceAll("^[._-]+|[._-]+$", "");
        return normalized.isBlank() ? "unknown" : normalized;
    }

    static String slugify(String value) {
        return LogicalPaths.slug(value, "item");
    }

    private String stripExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private String comparableName(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private <T> List<T> distinct(List<T> items) {
        return items.stream().distinct().sorted((left, right) -> {
            if (left instanceof Path leftPath && right instanceof Path rightPath) {
                return leftPath.compareTo(rightPath);
            }
            return left.toString().compareTo(right.toString());
        }).toList();
    }

    private record CaseRef(Path source, String ref) {
    }

    private static class TransactionFact {
        private final String transactionKey;
        private final String transactionRef;
        private final String transactionMatchRef;
        private final Path transactionXml;
        private final List<Path> transactionXmlCandidates;
        private final List<String> aliases = new ArrayList<>();
        private final List<SmartEsbFlowNode> flowNodes = new ArrayList<>();
        private final LinkedHashSet<String> moduleServiceIds = new LinkedHashSet<>();

        private TransactionFact(String transactionKey, String transactionRef, String transactionMatchRef, Path transactionXml, List<Path> transactionXmlCandidates) {
            this.transactionKey = transactionKey;
            this.transactionRef = transactionRef;
            this.transactionMatchRef = transactionMatchRef;
            this.transactionXml = transactionXml;
            this.transactionXmlCandidates = transactionXmlCandidates;
        }
    }

    private static class ModuleFact {
        private final String serviceId;
        private final LinkedHashSet<String> usedByTransactions = new LinkedHashSet<>();
        private final List<Path> baseXmlCandidates = new ArrayList<>();
        private final List<Path> bizCandidates = new ArrayList<>();
        private List<Path> javaCandidates = new ArrayList<>();
        private final List<String> javaHints = new ArrayList<>();

        private ModuleFact(String serviceId) {
            this.serviceId = serviceId;
        }
    }

}
