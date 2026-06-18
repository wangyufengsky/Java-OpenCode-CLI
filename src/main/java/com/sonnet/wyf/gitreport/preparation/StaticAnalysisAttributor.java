package com.sonnet.wyf.gitreport.preparation;

import com.sonnet.wyf.gitreport.GitReportProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class StaticAnalysisAttributor {
    private static final Logger log = LoggerFactory.getLogger(StaticAnalysisAttributor.class);
    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key|credential|密钥|令牌|密码)"
    );

    private final CommandExecutor commandExecutor;

    public StaticAnalysisAttributor(CommandExecutor commandExecutor) {
        this.commandExecutor = commandExecutor;
    }

    public void apply(GitReportProperties properties, Map<String, Object> data) throws Exception {
        List<Map<String, Object>> authors = listOfMaps(data.get("authors"));
        if (!properties.getStaticAnalysis().isEnabled()) {
            attachDisabledStatus(authors);
            return;
        }
        Path repo = properties.getPaths().getRepo().toAbsolutePath().normalize();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", true);
        List<ScannerFinding> findings = new ArrayList<>();
        if (properties.getStaticAnalysis().isPmdEnabled()) {
            findings.addAll(runPmd(repo, properties, status));
        }
        if (properties.getStaticAnalysis().isSpotbugsEnabled()) {
            findings.addAll(runSpotBugs(repo, properties, status));
        }
        attribute(repo, authors, findings, status);
    }

    private List<ScannerFinding> runPmd(Path repo, GitReportProperties properties, Map<String, Object> status) throws Exception {
        Map<String, Object> pmd = new LinkedHashMap<>();
        status.put("pmd", pmd);
        try {
            commandExecutor.run(repo,
                    properties.getStaticAnalysis().getMavenBin(),
                    "-q",
                    "org.apache.maven.plugins:maven-pmd-plugin:3.28.0:pmd",
                    "-DskipTests",
                    "-Dpmd.failOnViolation=false");
            Path report = firstExisting(repo.resolve("target/site/pmd.xml"), repo.resolve("target/pmd.xml"));
            if (report == null) {
                pmd.put("status", "no_report");
                return List.of();
            }
            List<ScannerFinding> findings = parsePmd(report, repo);
            pmd.put("status", "completed");
            pmd.put("report", report.toString());
            pmd.put("finding_count", findings.size());
            return findings;
        } catch (Exception exception) {
            pmd.put("status", "failed");
            pmd.put("error", exception.getMessage());
            if (properties.getStaticAnalysis().isFailOnAnalysisError()) {
                throw exception;
            }
            log.warn("PMD analysis failed and will be recorded as unverified: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<ScannerFinding> runSpotBugs(Path repo, GitReportProperties properties, Map<String, Object> status) throws Exception {
        Map<String, Object> spotbugs = new LinkedHashMap<>();
        status.put("spotbugs", spotbugs);
        try {
            commandExecutor.run(repo,
                    properties.getStaticAnalysis().getMavenBin(),
                    "-q",
                    "-DskipTests",
                    "compile",
                    "com.github.spotbugs:spotbugs-maven-plugin:4.10.2.0:spotbugs",
                    "-Dspotbugs.failOnError=false",
                    "-Dspotbugs.xmlOutput=true");
            Path report = firstExisting(repo.resolve("target/spotbugsXml.xml"), repo.resolve("target/spotbugs.xml"));
            if (report == null) {
                spotbugs.put("status", "no_report");
                return List.of();
            }
            List<ScannerFinding> findings = parseSpotBugs(report, repo);
            spotbugs.put("status", "completed");
            spotbugs.put("report", report.toString());
            spotbugs.put("finding_count", findings.size());
            return findings;
        } catch (Exception exception) {
            spotbugs.put("status", "failed");
            spotbugs.put("error", exception.getMessage());
            if (properties.getStaticAnalysis().isFailOnAnalysisError()) {
                throw exception;
            }
            log.warn("SpotBugs analysis failed and will be recorded as unverified: {}", exception.getMessage());
            return List.of();
        }
    }

    List<ScannerFinding> parsePmd(Path report, Path repo) throws Exception {
        Document document = parseXml(report);
        List<ScannerFinding> findings = new ArrayList<>();
        NodeList files = document.getElementsByTagName("file");
        for (int fileIndex = 0; fileIndex < files.getLength(); fileIndex++) {
            Element file = (Element) files.item(fileIndex);
            String path = normalizePath(repo, file.getAttribute("name"));
            NodeList violations = file.getElementsByTagName("violation");
            for (int index = 0; index < violations.getLength(); index++) {
                Element violation = (Element) violations.item(index);
                String rule = firstNonBlank(violation.getAttribute("rule"), violation.getAttribute("ruleset"), "pmd_violation");
                findings.add(new ScannerFinding(
                        "pmd",
                        rule,
                        path,
                        intAttr(violation, "beginline", 0),
                        intAttr(violation, "endline", intAttr(violation, "beginline", 0)),
                        severityFromPmd(violation.getAttribute("priority")),
                        dimension(rule, violation.getTextContent(), "pmd"),
                        compact(violation.getTextContent())
                ));
            }
        }
        return findings;
    }

    List<ScannerFinding> parseSpotBugs(Path report, Path repo) throws Exception {
        Document document = parseXml(report);
        List<ScannerFinding> findings = new ArrayList<>();
        NodeList bugs = document.getElementsByTagName("BugInstance");
        for (int index = 0; index < bugs.getLength(); index++) {
            Element bug = (Element) bugs.item(index);
            Element sourceLine = firstElement(bug.getElementsByTagName("SourceLine"));
            if (sourceLine == null) {
                continue;
            }
            String path = normalizePath(repo, firstNonBlank(sourceLine.getAttribute("sourcepath"), sourceLine.getAttribute("sourcefile"), ""));
            String type = firstNonBlank(bug.getAttribute("type"), bug.getAttribute("abbrev"), "spotbugs_bug");
            String category = bug.getAttribute("category");
            findings.add(new ScannerFinding(
                    "spotbugs",
                    type,
                    path,
                    intAttr(sourceLine, "start", 0),
                    intAttr(sourceLine, "end", intAttr(sourceLine, "start", 0)),
                    severityFromSpotBugs(bug.getAttribute("priority")),
                    dimension(type, category, "spotbugs"),
                    firstNonBlank(category, type, "SpotBugs finding")
            ));
        }
        return findings;
    }

    private void attribute(Path repo, List<Map<String, Object>> authors, List<ScannerFinding> findings, Map<String, Object> status) {
        for (Map<String, Object> author : authors) {
            author.put("scanner_status", new LinkedHashMap<>(status));
            List<Map<String, Object>> attributed = listOfMaps(author.get("attributed_findings"));
            List<Map<String, Object>> context = listOfMaps(author.get("context_findings"));
            List<Map<String, Object>> snippets = listOfMaps(author.get("code_snippets"));
            List<Map<String, Object>> ownedHunks = listOfMaps(author.get("owned_hunks"));
            Set<String> touchedFiles = listValue(author.get("unique_files")).stream()
                    .map(value -> Objects.toString(value, ""))
                    .filter(value -> !value.isBlank())
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
            for (ScannerFinding finding : findings) {
                Map<String, Object> matchingHunk = matchingHunk(finding, ownedHunks);
                if (matchingHunk != null) {
                    Map<String, Object> mapped = mappedFinding(finding, matchingHunk, attributed.size() + 1);
                    attributed.add(mapped);
                    snippet(repo, mapped).ifPresent(snippets::add);
                } else if (touchedFiles.contains(finding.file())) {
                    context.add(contextFinding(finding));
                }
            }
        }
    }

    private Map<String, Object> matchingHunk(ScannerFinding finding, List<Map<String, Object>> hunks) {
        for (Map<String, Object> hunk : hunks) {
            if (!finding.file().equals(Objects.toString(hunk.get("file"), ""))) {
                continue;
            }
            int start = number(hunk.get("line_start"));
            int end = number(hunk.get("line_end"));
            if (finding.lineStart() > 0 && start <= finding.lineEnd() && end >= finding.lineStart()) {
                return hunk;
            }
        }
        return null;
    }

    private Map<String, Object> mappedFinding(ScannerFinding finding, Map<String, Object> hunk, int index) {
        Map<String, Object> result = baseFinding(finding);
        result.put("id", finding.scanner() + "-" + index);
        result.put("source", "scanner");
        result.put("scanner", finding.scanner());
        result.put("scanner_rule", finding.rule());
        result.put("attribution", "owned_hunk");
        result.put("owned_hunk_id", hunk.get("hunk_id"));
        result.put("confidence", 1.0);
        return result;
    }

    private Map<String, Object> contextFinding(ScannerFinding finding) {
        Map<String, Object> result = baseFinding(finding);
        result.put("source", "scanner");
        result.put("scanner", finding.scanner());
        result.put("scanner_rule", finding.rule());
        result.put("attribution", "context_only");
        return result;
    }

    private Map<String, Object> baseFinding(ScannerFinding finding) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", "");
        result.put("dimension", finding.dimension());
        result.put("polarity", "negative");
        result.put("severity", finding.severity());
        result.put("rule_id", finding.rule());
        result.put("file", finding.file());
        result.put("line_start", finding.lineStart());
        result.put("line_end", finding.lineEnd());
        result.put("evidence", finding.message());
        result.put("reason", finding.message());
        result.put("suggestion", "请按扫描规则修复或补充人工说明。");
        return result;
    }

    private java.util.Optional<Map<String, Object>> snippet(Path repo, Map<String, Object> finding) {
        Path file = repo.resolve(Objects.toString(finding.get("file"), "")).normalize();
        if (!file.startsWith(repo) || !Files.exists(file)) {
            return java.util.Optional.empty();
        }
        try {
            List<String> lines = Files.readAllLines(file);
            int start = Math.max(1, number(finding.get("line_start")));
            int end = Math.min(lines.size(), Math.max(start, number(finding.get("line_end"))));
            List<String> excerpt = new ArrayList<>();
            for (int line = start; line <= end && excerpt.size() < 12; line++) {
                excerpt.add(redact(lines.get(line - 1)));
            }
            if (excerpt.isEmpty()) {
                return java.util.Optional.empty();
            }
            Map<String, Object> snippet = new LinkedHashMap<>();
            snippet.put("file", finding.get("file"));
            snippet.put("line_start", start);
            snippet.put("line_end", end);
            snippet.put("dimension", finding.get("dimension"));
            snippet.put("severity", finding.get("severity"));
            snippet.put("reason", finding.get("reason"));
            snippet.put("suggestion", finding.get("suggestion"));
            snippet.put("snippet", String.join("\n", excerpt));
            return java.util.Optional.of(snippet);
        } catch (Exception ignored) {
            return java.util.Optional.empty();
        }
    }

    private String redact(String value) {
        return SENSITIVE_PATTERN.matcher(value).replaceAll("[REDACTED]");
    }

    private void attachDisabledStatus(List<Map<String, Object>> authors) {
        for (Map<String, Object> author : authors) {
            author.put("scanner_status", Map.of("enabled", false));
        }
    }

    private Document parseXml(Path report) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(report.toFile());
    }

    private Path firstExisting(Path... paths) {
        for (Path path : paths) {
            if (Files.exists(path)) {
                return path;
            }
        }
        return null;
    }

    private Element firstElement(NodeList nodes) {
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private int intAttr(Element element, String name, int fallback) {
        try {
            String value = element.getAttribute(name);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String normalizePath(Path repo, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Path path = Path.of(value.replace('\\', '/'));
        if (path.isAbsolute()) {
            try {
                return repo.relativize(path.normalize()).toString().replace('\\', '/');
            } catch (IllegalArgumentException ignored) {
                return path.normalize().toString().replace('\\', '/');
            }
        }
        return value.replace('\\', '/');
    }

    private String severityFromPmd(String priority) {
        int value = parseInt(priority, 3);
        if (value <= 2) {
            return "high";
        }
        return value == 3 ? "medium" : "low";
    }

    private String severityFromSpotBugs(String priority) {
        int value = parseInt(priority, 2);
        if (value <= 1) {
            return "high";
        }
        return value == 2 ? "medium" : "low";
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String dimension(String rule, String message, String scanner) {
        String text = (rule + " " + message + " " + scanner).toLowerCase(Locale.ROOT);
        if (text.contains("security") || text.contains("null") || text.contains("exception")
                || text.contains("sql") || text.contains("xss") || text.contains("resource")
                || text.contains("correctness") || text.contains("password")) {
            return "risk_control";
        }
        if (text.contains("naming") || text.contains("format") || text.contains("comment")) {
            return "code_standard";
        }
        if (text.contains("complex") || text.contains("duplicate") || text.contains("coupling")
                || text.contains("design") || text.contains("unused")) {
            return "maintainability";
        }
        return "maintainability";
    }

    private String compact(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : new ArrayList<>();
    }

    private List<?> listValue(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private int number(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    record ScannerFinding(String scanner, String rule, String file, int lineStart, int lineEnd, String severity, String dimension, String message) {
    }
}
