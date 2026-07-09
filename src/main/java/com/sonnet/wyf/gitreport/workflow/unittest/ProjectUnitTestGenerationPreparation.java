package com.sonnet.wyf.gitreport.workflow.unittest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ReferenceType;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;

public class ProjectUnitTestGenerationPreparation {
    public static final String SCHEMA_VERSION = "project-unit-test-generation/v1";

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)\\s*;");
    private static final Pattern TYPE_PATTERN = Pattern.compile("(?m)\\b(class|interface|record|enum)\\s+([A-Za-z0-9_]+)");

    private final ObjectMapper objectMapper;
    private final JavaParser javaParser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_22));

    public ProjectUnitTestGenerationPreparation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path prepare(ProjectUnitTestGenerationProperties properties, boolean overwrite) throws Exception {
        Path repo = properties.getProject().getRepo().toAbsolutePath().normalize();
        Path out = properties.getPaths().getOut().toAbsolutePath().normalize();
        if (Files.exists(out) && !overwrite && hasChildren(out)) {
            throw new IllegalStateException("project-unit-test-generation output already exists and is not empty: " + out);
        }
        Files.createDirectories(out);
        Files.createDirectories(out.resolve("test-batches"));

        List<Path> sourceFiles = collectSourceFiles(properties, repo);
        List<Map<String, Object>> types = new ArrayList<>();
        for (Path sourceFile : sourceFiles) {
            types.addAll(parseSource(repo, sourceFile));
        }
        Map<String, Object> docs = docs(properties, repo);
        List<Map<String, Object>> batches = buildBatches(properties, out, types, docs);
        writeBatchInputs(out, batches);

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schema_version", SCHEMA_VERSION);
        plan.put("generated_at", OffsetDateTime.now().toString());
        plan.put("project", Map.of(
                "id", string(properties.getProject().getId()),
                "name", string(properties.getProject().getName()),
                "repo", repo.toString()
        ));
        plan.put("docs", docs);
        plan.put("source_filters", Map.of(
                "package_paths", properties.getSource().getPackagePaths(),
                "include", properties.getSource().getInclude(),
                "exclude", properties.getSource().getExclude()
        ));
        plan.put("source_files", sourceFiles.stream().map(path -> relative(repo, path)).toList());
        plan.put("types", types);
        plan.put("test_batches_json", out.resolve("test-batches.json").toString());
        plan.put("batch_count", batches.size());

        Path planPath = out.resolve("unit-test-plan.json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(planPath.toFile(), plan);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(out.resolve("test-batches.json").toFile(), Map.of("batches", batches));
        return planPath;
    }

    private List<Path> collectSourceFiles(ProjectUnitTestGenerationProperties properties, Path repo) throws Exception {
        List<PathMatcher> includeMatchers = matchers(properties.getSource().getInclude());
        List<PathMatcher> excludeMatchers = matchers(properties.getSource().getExclude());
        List<String> packagePaths = properties.getSource().getPackagePaths();
        List<Path> sourceFiles = new ArrayList<>();
        for (Path sourceRoot : sourceRoots(repo)) {
            try (Stream<Path> stream = Files.walk(sourceRoot)) {
                sourceFiles.addAll(stream.filter(Files::isRegularFile)
                        .filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> matchesPackagePath(repo, path, packagePaths))
                        .filter(path -> includeMatchers.isEmpty() || matches(repo, path, includeMatchers))
                        .filter(path -> !matches(repo, path, excludeMatchers))
                        .toList());
            }
        }
        return sourceFiles.stream().sorted().toList();
    }

    private List<Path> sourceRoots(Path repo) throws Exception {
        List<Path> roots = new ArrayList<>();
        Path rootSource = repo.resolve("src/main/java");
        if (Files.exists(rootSource)) {
            roots.add(rootSource);
        }
        for (String module : mavenModules(repo)) {
            Path moduleSource = repo.resolve(module).resolve("src/main/java").normalize();
            if (Files.exists(moduleSource) && !roots.contains(moduleSource)) {
                roots.add(moduleSource);
            }
        }
        return roots;
    }

    private List<String> mavenModules(Path repo) throws Exception {
        Path pom = repo.resolve("pom.xml");
        if (!Files.exists(pom)) {
            return List.of();
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        Document document = factory.newDocumentBuilder().parse(pom.toFile());
        NodeList modules = document.getElementsByTagName("module");
        List<String> result = new ArrayList<>();
        for (int index = 0; index < modules.getLength(); index++) {
            String module = modules.item(index).getTextContent().trim();
            if (!module.isBlank()) {
                result.add(module.replace('\\', '/'));
            }
        }
        return result;
    }

    private boolean matchesPackagePath(Path repo, Path sourceFile, List<String> packagePaths) {
        if (packagePaths.isEmpty()) {
            return true;
        }
        String relative = relative(repo, sourceFile);
        String sourceRelative = javaSourceRelative(relative);
        for (String packagePath : packagePaths) {
            String value = packagePath.trim().replace('\\', '/');
            if (value.isBlank()) {
                continue;
            }
            String normalizedPath = value.endsWith("/") ? value : value + "/";
            if (value.contains("src/main/java/") && relative.startsWith(normalizedPath)) {
                return true;
            }
            String packagePrefix = value.replace('.', '/');
            packagePrefix = packagePrefix.endsWith("/") ? packagePrefix : packagePrefix + "/";
            if (sourceRelative.startsWith(packagePrefix)) {
                return true;
            }
        }
        return false;
    }

    private String javaSourceRelative(String relativePath) {
        String marker = "src/main/java/";
        int index = relativePath.indexOf(marker);
        if (index < 0) {
            return relativePath;
        }
        return relativePath.substring(index + marker.length());
    }

    private List<PathMatcher> matchers(List<String> patterns) {
        return patterns.stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> FileSystems.getDefault().getPathMatcher("glob:" + value))
                .toList();
    }

    private boolean matches(Path repo, Path path, List<PathMatcher> matchers) {
        Path relativePath = repo.relativize(path.toAbsolutePath().normalize());
        return matchers.stream().anyMatch(matcher -> matcher.matches(relativePath));
    }

    private List<Map<String, Object>> parseSource(Path repo, Path sourceFile) throws Exception {
        String source = Files.readString(sourceFile);
        ParseResult<CompilationUnit> result = javaParser.parse(source);
        if (result.isSuccessful() && result.getResult().isPresent()) {
            return parseTypes(repo, sourceFile, source, result.getResult().get());
        }
        Map<String, Object> fallback = fallbackType(repo, sourceFile, source, result.getProblems().toString());
        return List.of(fallback);
    }

    private List<Map<String, Object>> parseTypes(Path repo, Path sourceFile, String source, CompilationUnit unit) {
        String packageName = unit.getPackageDeclaration().map(declaration -> declaration.getNameAsString()).orElse("");
        Map<String, String> imports = importMap(unit);
        List<Map<String, Object>> types = new ArrayList<>();
        for (TypeDeclaration<?> declaration : unit.getTypes()) {
            types.add(typeInfo(repo, sourceFile, source, packageName, imports, declaration, ""));
        }
        if (types.isEmpty()) {
            types.add(fallbackType(repo, sourceFile, source, "no top-level type declarations"));
        }
        return types;
    }

    private Map<String, String> importMap(CompilationUnit unit) {
        Map<String, String> imports = new LinkedHashMap<>();
        for (ImportDeclaration declaration : unit.getImports()) {
            if (!declaration.isAsterisk()) {
                String name = declaration.getNameAsString();
                imports.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
        }
        return imports;
    }

    private Map<String, Object> typeInfo(
            Path repo,
            Path sourceFile,
            String source,
            String packageName,
            Map<String, String> imports,
            TypeDeclaration<?> declaration,
            String parseError
    ) {
        String typeName = declaration.getNameAsString();
        String qualifiedName = packageName.isBlank() ? typeName : packageName + "." + typeName;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("qualified_name", qualifiedName);
        row.put("package", packageName);
        row.put("type", typeKind(declaration));
        row.put("role", role(declaration));
        row.put("source_file", relative(repo, sourceFile));
        row.put("target_test_file", targetTestFile(repo, sourceFile, packageName, typeName));
        row.put("existing_test_files", existingTests(repo, sourceFile, packageName, typeName));
        row.put("annotations", declaration.getAnnotations().stream().map(annotation -> annotation.getNameAsString()).toList());
        row.put("fields", declaration.getMembers().stream()
                .filter(FieldDeclaration.class::isInstance)
                .map(FieldDeclaration.class::cast)
                .flatMap(field -> field.getVariables().stream()
                        .map(variable -> Map.of("name", variable.getNameAsString(), "type", variable.getTypeAsString())))
                .toList());
        row.put("constructors", declaration.getMembers().stream()
                .filter(ConstructorDeclaration.class::isInstance)
                .map(ConstructorDeclaration.class::cast)
                .map(constructor -> callableInfo(constructor, imports))
                .toList());
        row.put("public_methods", declaration.getMethods().stream()
                .filter(method -> method.isPublic())
                .map(method -> methodInfo(method, imports))
                .toList());
        row.put("source_chars", source.length());
        if (!parseError.isBlank()) {
            row.put("parse_error", parseError);
        }
        return row;
    }

    private Map<String, Object> methodInfo(MethodDeclaration method, Map<String, String> imports) {
        Map<String, Object> info = callableInfo(method, imports);
        info.put("return_type", method.getTypeAsString());
        return info;
    }

    private Map<String, Object> callableInfo(CallableDeclaration<?> callable, Map<String, String> imports) {
        List<String> exceptions = callable.getThrownExceptions().stream()
                .map(ReferenceType::asString)
                .map(exception -> imports.getOrDefault(exception, exception))
                .toList();
        String signature = callable.getNameAsString()
                + "("
                + callable.getParameters().stream()
                .map(parameter -> parameter.getTypeAsString() + " " + parameter.getNameAsString())
                .reduce((left, right) -> left + ", " + right)
                .orElse("")
                + ")"
                + (exceptions.isEmpty() ? "" : " throws " + String.join(", ", exceptions));
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", callable.getNameAsString());
        info.put("signature", signature);
        info.put("parameters", callable.getParameters().stream()
                .map(parameter -> Map.of("name", parameter.getNameAsString(), "type", parameter.getTypeAsString()))
                .toList());
        info.put("throws", exceptions);
        return info;
    }

    private String typeKind(TypeDeclaration<?> declaration) {
        if (declaration instanceof ClassOrInterfaceDeclaration classOrInterface) {
            return classOrInterface.isInterface() ? "interface" : "class";
        }
        if (declaration instanceof RecordDeclaration) {
            return "record";
        }
        if (declaration instanceof EnumDeclaration) {
            return "enum";
        }
        return "type";
    }

    private String role(TypeDeclaration<?> declaration) {
        Set<String> annotations = new LinkedHashSet<>(declaration.getAnnotations().stream()
                .map(annotation -> annotation.getNameAsString().toLowerCase(Locale.ROOT))
                .toList());
        if (annotations.contains("restcontroller") || annotations.contains("controller")) {
            return "controller";
        }
        if (annotations.contains("service")) {
            return "service";
        }
        if (annotations.contains("repository")) {
            return "repository";
        }
        if (annotations.contains("mapper")) {
            return "mapper";
        }
        if (annotations.contains("configuration")) {
            return "configuration";
        }
        if (declaration.isClassOrInterfaceDeclaration()
                && declaration.asClassOrInterfaceDeclaration().isFinal()
                && declaration.getMembers().stream().filter(BodyDeclaration::isMethodDeclaration)
                .map(BodyDeclaration::asMethodDeclaration).anyMatch(MethodDeclaration::isStatic)) {
            return "utility";
        }
        return "pojo";
    }

    private Map<String, Object> fallbackType(Path repo, Path sourceFile, String source, String parseError) {
        String packageName = find(PACKAGE_PATTERN, source).orElse(packageFromPath(repo, sourceFile));
        String typeName = find(TYPE_PATTERN, source).orElseGet(() -> stripJavaExtension(sourceFile.getFileName().toString()));
        return typeInfo(repo, sourceFile, source, packageName, Map.of(), new ClassOrInterfaceDeclaration().setName(typeName), parseError);
    }

    private Optional<String> find(Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(matcher.group(matcher.groupCount()));
    }

    private String packageFromPath(Path repo, Path sourceFile) {
        String relative = relative(repo, sourceFile);
        String prefix = "src/main/java/";
        int index = relative.indexOf(prefix);
        if (index < 0 || !relative.endsWith(".java")) {
            return "";
        }
        String parent = relative.substring(index + prefix.length(), relative.lastIndexOf('/'));
        return parent.replace('/', '.');
    }

    private List<String> existingTests(Path repo, Path sourceFile, String packageName, String typeName) {
        Path testFile = repo.resolve(targetTestFile(repo, sourceFile, packageName, typeName));
        return Files.exists(testFile) ? List.of(normalize(repo.relativize(testFile).toString())) : List.of();
    }

    private String targetTestFile(Path repo, Path sourceFile, String packageName, String typeName) {
        String packagePath = packageName == null || packageName.isBlank() ? "" : packageName.replace('.', '/') + "/";
        return modulePrefix(repo, sourceFile) + "src/test/java/" + packagePath + typeName + "Test.java";
    }

    private String modulePrefix(Path repo, Path sourceFile) {
        String relative = relative(repo, sourceFile);
        String marker = "src/main/java/";
        int index = relative.indexOf(marker);
        if (index <= 0) {
            return "";
        }
        return relative.substring(0, index);
    }

    private List<Map<String, Object>> buildBatches(ProjectUnitTestGenerationProperties properties, Path out, List<Map<String, Object>> types, Map<String, Object> docs) {
        List<Map<String, Object>> batches = new ArrayList<>();
        for (Map<String, Object> type : types.stream().sorted(Comparator.comparing(row -> string(row.get("qualified_name")))).toList()) {
            batches.add(batch(out, batches.size() + 1, type, docs, properties));
        }
        return batches;
    }

    private Map<String, Object> batch(Path out, int index, Map<String, Object> type, Map<String, Object> docs, ProjectUnitTestGenerationProperties properties) {
        String qualifiedName = string(type.get("qualified_name"));
        String sourceFile = string(type.get("source_file"));
        String batchId = "test-batch-%03d-%s".formatted(index, slug(simpleName(qualifiedName)));
        Path batchDir = out.resolve("test-batches").resolve(batchId);
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batch_id", batchId);
        batch.put("scope", Map.of(
                "type", "class",
                "qualified_name", qualifiedName,
                "source_file", sourceFile
        ));
        batch.put("source_files", List.of(sourceFile));
        batch.put("target_test_files", List.of(string(type.get("target_test_file"))));
        batch.put("existing_test_files", listOfStrings(type.get("existing_test_files")).stream()
                .distinct()
                .toList());
        batch.put("types", List.of(type));
        batch.put("docs", docs);
        batch.put("coverage", coveragePolicy(properties));
        batch.put("input_json", batchDir.resolve("input.json").toString());
        batch.put("allowed_write_globs", allowedWriteGlobs(listOfStrings(batch.get("target_test_files"))));
        return batch;
    }

    private Map<String, Object> coveragePolicy(ProjectUnitTestGenerationProperties properties) {
        return Map.of(
                "required", properties.getTest().isRequireCoverage(),
                "threshold_percent", clampPercent(properties.getTest().getCoverageThresholdPercent()),
                "scope", "class"
        );
    }

    private int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private List<String> allowedWriteGlobs(List<String> targetTestFiles) {
        return targetTestFiles.stream()
                .map(this::testRootGlob)
                .distinct()
                .sorted()
                .toList();
    }

    private String testRootGlob(String targetTestFile) {
        String marker = "src/test/";
        int index = targetTestFile.indexOf(marker);
        if (index <= 0) {
            return "src/test/**";
        }
        return targetTestFile.substring(0, index) + "src/test/**";
    }

    private void writeBatchInputs(Path out, List<Map<String, Object>> batches) throws Exception {
        for (Map<String, Object> batch : batches) {
            Path input = Path.of(string(batch.get("input_json")));
            Files.createDirectories(input.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(input.toFile(), batch);
        }
    }

    private Map<String, Object> docs(ProjectUnitTestGenerationProperties properties, Path repo) {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> docs = new LinkedHashMap<>();
        docs.put("agents", docPath(repo, properties.getDocs().getAgents(), warnings));
        docs.put("project_map", docPath(repo, properties.getDocs().getProjectMap(), warnings));
        docs.put("reconstructed_design", docPath(repo, properties.getDocs().getReconstructedDesign(), warnings));
        docs.put("warnings", warnings);
        return docs;
    }

    private String docPath(Path repo, Path doc, List<String> warnings) {
        Path resolved = doc.isAbsolute() ? doc : repo.resolve(doc).normalize();
        if (!Files.exists(resolved)) {
            warnings.add("missing doc: " + doc);
        }
        return doc.toString();
    }

    private boolean hasChildren(Path out) throws Exception {
        try (Stream<Path> stream = Files.list(out)) {
            return stream.findAny().isPresent();
        }
    }

    private List<String> listOfStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(Object::toString).toList();
    }

    private String relative(Path repo, Path path) {
        return normalize(repo.relativize(path.toAbsolutePath().normalize()).toString());
    }

    private String normalize(String path) {
        return path.replace('\\', '/');
    }

    private String stripJavaExtension(String value) {
        return value.endsWith(".java") ? value.substring(0, value.length() - 5) : value;
    }

    private String simpleName(String qualifiedName) {
        int index = qualifiedName.lastIndexOf('.');
        return index < 0 ? qualifiedName : qualifiedName.substring(index + 1);
    }

    private String slug(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return normalized.isBlank() ? "root" : normalized.substring(0, Math.min(80, normalized.length()));
    }

    private String string(Object value) {
        return value == null ? "" : value.toString();
    }

}
