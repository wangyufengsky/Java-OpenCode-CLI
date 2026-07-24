package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class MyBatisSqlInventoryBuilder {
    private static final Set<String> STATEMENT_ELEMENTS = Set.of("select", "insert", "update", "delete");
    private static final Pattern MYBATIS_DOCTYPE = Pattern.compile(
            "(?is)<!DOCTYPE\\s+mapper\\s+PUBLIC\\s+(['\"])-//mybatis\\.org//DTD Mapper 3\\.0//EN\\1"
                    + "\\s+(['\"])https?://mybatis\\.org/dtd/mybatis-3-mapper\\.dtd\\2\\s*>");
    private static final Pattern PLACEHOLDER = Pattern.compile("(?:#|\\$)\\{[^}]+}");
    private static final Pattern PROPERTY_PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Comparator<ParsedMapper> MAPPER_ORDER = Comparator
            .comparing(ParsedMapper::relativePath)
            .thenComparing(ParsedMapper::namespace);
    private static final Comparator<MyBatisSqlStatement> STATEMENT_ORDER = Comparator
            .comparing(MyBatisSqlStatement::mapperRelativePath)
            .thenComparing(MyBatisSqlStatement::namespace)
            .thenComparing(MyBatisSqlStatement::id)
            .thenComparingInt(MyBatisSqlStatement::selectKeyOrdinal);

    public MyBatisSqlInventory build(
            Path repository,
            List<String> sourcePaths,
            List<String> includes,
            List<String> excludes) {
        MyBatisSqlSourceScope sourceScope = MyBatisSqlSourceScope.resolve(repository, sourcePaths);
        Path root = sourceScope.repository();
        List<PathMatcher> includeMatchers = matchers(defaultIncludes(includes));
        List<PathMatcher> excludeMatchers = matchers(excludes == null ? List.of() : excludes);
        List<Path> mapperPaths = discoverMappers(sourceScope, includeMatchers, excludeMatchers);
        if (mapperPaths.isEmpty()) {
            throw new IllegalArgumentException(
                    "no MyBatis mapper XML files matched under source.paths " + sourceScope.configuredPaths());
        }

        List<ParsedMapper> parsedMappers = mapperPaths.stream()
                .map(path -> parseMapperCandidate(root, path))
                .flatMap(Optional::stream)
                .sorted(MAPPER_ORDER)
                .toList();
        if (parsedMappers.isEmpty()) {
            throw new IllegalArgumentException(
                    "no MyBatis mapper XML files matched under source.paths " + sourceScope.configuredPaths());
        }
        Map<String, Fragment> fragments = collectFragments(parsedMappers);
        validateFragments(fragments);

        Map<String, StatementOrigin> identities = new LinkedHashMap<>();
        List<MyBatisMapperInventory> mappers = new ArrayList<>();
        List<MyBatisSqlStatement> allStatements = new ArrayList<>();
        for (ParsedMapper mapper : parsedMappers) {
            List<MyBatisSqlStatement> mapperStatements = statementsFor(mapper, fragments, identities);
            mapperStatements.sort(STATEMENT_ORDER);
            String mapperKey = canonicalMapperKey(mapper.relativePath(), mapper.namespace());
            mappers.add(new MyBatisMapperInventory(
                    mapper.relativePath(),
                    mapper.namespace(),
                    mapper.sourceSha256(),
                    mapperKey,
                    mapperStatements));
            allStatements.addAll(mapperStatements);
        }
        allStatements.sort(STATEMENT_ORDER);
        return new MyBatisSqlInventory(mappers, allStatements);
    }

    public MyBatisSqlInventory build(Path repository, List<String> includes, List<String> excludes) {
        return build(repository, List.of("."), includes, excludes);
    }

    private List<String> defaultIncludes(List<String> includes) {
        return includes == null || includes.isEmpty() ? List.of("**/*.xml") : List.copyOf(includes);
    }

    private List<PathMatcher> matchers(List<String> patterns) {
        List<PathMatcher> result = new ArrayList<>();
        for (String pattern : patterns) {
            if (pattern != null && !pattern.isBlank()) {
                LinkedHashSet<String> expandedPatterns = new LinkedHashSet<>();
                expandDoubleStarDirectories(pattern.replace('\\', '/'), 0, expandedPatterns);
                for (String expandedPattern : expandedPatterns) {
                    result.add(FileSystems.getDefault().getPathMatcher("glob:" + expandedPattern));
                }
            }
        }
        return List.copyOf(result);
    }

    private void expandDoubleStarDirectories(String pattern, int fromIndex, Set<String> expandedPatterns) {
        expandedPatterns.add(pattern);
        int marker = pattern.indexOf("**/", fromIndex);
        if (marker < 0) {
            return;
        }
        expandDoubleStarDirectories(pattern, marker + 3, expandedPatterns);
        expandDoubleStarDirectories(pattern.substring(0, marker) + pattern.substring(marker + 3), marker, expandedPatterns);
    }

    private List<Path> discoverMappers(
            MyBatisSqlSourceScope sourceScope,
            List<PathMatcher> includes,
            List<PathMatcher> excludes) {
        Map<String, Path> discoveredByRepositoryPath = new LinkedHashMap<>();
        for (Path discoveryRoot : sourceScope.discoveryRoots()) {
            try (Stream<Path> paths = Files.walk(discoveryRoot)) {
                paths.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xml"))
                        .filter(path -> matches(discoveryRoot.relativize(path), includes))
                        .filter(path -> !matches(discoveryRoot.relativize(path), excludes))
                        .filter(path -> requireRegularMapperFile(sourceScope.repository(), path))
                        .forEach(path -> discoveredByRepositoryPath.putIfAbsent(
                                logicalPath(sourceScope.repository().relativize(path)),
                                path));
            } catch (IOException ex) {
                throw new IllegalArgumentException(
                        "unable to discover MyBatis mapper XML files under " + discoveryRoot, ex);
            }
        }
        return discoveredByRepositoryPath.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    private boolean requireRegularMapperFile(Path root, Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(
                    "symbolic links are forbidden for MyBatis mapper XML: " + logicalPath(root.relativize(path)));
        }
        return Files.isRegularFile(path);
    }

    private boolean matches(Path relativePath, List<PathMatcher> matchers) {
        for (PathMatcher matcher : matchers) {
            if (matcher.matches(relativePath)) {
                return true;
            }
        }
        return false;
    }

    private Optional<ParsedMapper> parseMapperCandidate(Path root, Path path) {
        String relativePath = logicalPath(root.relativize(path));
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException("unable to read MyBatis mapper XML: " + relativePath);
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new IllegalArgumentException("unable to read MyBatis mapper XML: " + relativePath, ex);
        }
        String source = MyBatisXmlSourceDecoder.decodeUtf8(bytes, relativePath);

        RootElement rootElement;
        try {
            rootElement = classifyRootWithoutDtd(source);
        } catch (XMLStreamException | RuntimeException ex) {
            String lexicalRoot = lexicalRootName(source);
            if (lexicalRoot == null || !"mapper".equals(localPart(lexicalRoot))) {
                return Optional.empty();
            }
            throw new IllegalArgumentException("failed to classify MyBatis mapper XML '" + relativePath + "': "
                    + conciseMessage(ex), ex);
        }
        if (rootElement == null || !"mapper".equals(rootElement.localName())) {
            return Optional.empty();
        }
        if (!rootElement.prefix().isEmpty() || !rootElement.namespaceUri().isEmpty()) {
            throw new IllegalArgumentException("failed to parse MyBatis mapper XML '" + relativePath
                    + "': mapper root must be unprefixed and have no namespace");
        }
        validateDoctype(source, relativePath);

        XmlNode rootNode;
        try {
            rootNode = parseXml(source);
        } catch (XMLStreamException | RuntimeException ex) {
            throw new IllegalArgumentException("failed to parse MyBatis mapper XML '" + relativePath + "': "
                    + conciseMessage(ex), ex);
        }
        if (rootNode == null || !"mapper".equals(rootNode.name())) {
            return Optional.empty();
        }
        String namespace = requiredAttribute(rootNode, "namespace", relativePath, "mapper");
        return Optional.of(new ParsedMapper(relativePath, namespace, sha256(bytes), source, rootNode));
    }

    private void validateDoctype(String source, String relativePath) {
        String declaration = prologDoctype(source, relativePath);
        if (declaration == null) {
            return;
        }
        if (!MYBATIS_DOCTYPE.matcher(declaration).matches()) {
            throw new IllegalArgumentException(
                    "external entities are forbidden in MyBatis mapper XML: " + relativePath
                            + "; only the standard MyBatis mapper DOCTYPE is allowed");
        }
    }

    private RootElement classifyRootWithoutDtd(String source) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        setPropertyIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setPropertyIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> {
            throw new XMLStreamException("external entity resolution is forbidden during XML classification");
        });

        XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(source));
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                    return new RootElement(
                            empty(reader.getPrefix()),
                            reader.getLocalName(),
                            empty(reader.getNamespaceURI())
                    );
                }
            }
            return null;
        } finally {
            reader.close();
        }
    }

    private String prologDoctype(String source, String relativePath) {
        int cursor = skipBomAndWhitespace(source, 0);
        String found = null;
        while (cursor < source.length() && source.charAt(cursor) == '<') {
            if (source.startsWith("<!--", cursor)) {
                int end = source.indexOf("-->", cursor + 4);
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "failed to parse MyBatis mapper XML '" + relativePath + "': unterminated comment");
                }
                cursor = skipBomAndWhitespace(source, end + 3);
                continue;
            }
            if (source.startsWith("<?", cursor)) {
                int end = source.indexOf("?>", cursor + 2);
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "failed to parse MyBatis mapper XML '" + relativePath
                                    + "': unterminated processing instruction");
                }
                cursor = skipBomAndWhitespace(source, end + 2);
                continue;
            }
            if (source.regionMatches(true, cursor, "<!DOCTYPE", 0, "<!DOCTYPE".length())) {
                int end = doctypeEnd(source, cursor);
                if (end < 0) {
                    throw new IllegalArgumentException(
                            "failed to parse MyBatis mapper XML '" + relativePath + "': unterminated DOCTYPE");
                }
                if (found != null) {
                    throw new IllegalArgumentException(
                            "external entities are forbidden in MyBatis mapper XML: " + relativePath
                                    + "; multiple DOCTYPE declarations are not allowed");
                }
                found = source.substring(cursor, end + 1);
                cursor = skipBomAndWhitespace(source, end + 1);
                continue;
            }
            break;
        }
        return found;
    }

    private String lexicalRootName(String source) {
        int cursor = skipBomAndWhitespace(source, 0);
        while (cursor < source.length() && source.charAt(cursor) == '<') {
            if (source.startsWith("<!--", cursor)) {
                int end = source.indexOf("-->", cursor + 4);
                if (end < 0) {
                    return null;
                }
                cursor = skipBomAndWhitespace(source, end + 3);
                continue;
            }
            if (source.startsWith("<?", cursor)) {
                int end = source.indexOf("?>", cursor + 2);
                if (end < 0) {
                    return null;
                }
                cursor = skipBomAndWhitespace(source, end + 2);
                continue;
            }
            if (source.regionMatches(true, cursor, "<!DOCTYPE", 0, "<!DOCTYPE".length())) {
                int end = doctypeEnd(source, cursor);
                if (end < 0) {
                    return null;
                }
                cursor = skipBomAndWhitespace(source, end + 1);
                continue;
            }
            int start = cursor + 1;
            int end = start;
            while (end < source.length()) {
                char current = source.charAt(end);
                if (Character.isWhitespace(current) || current == '>' || current == '/') {
                    break;
                }
                end++;
            }
            return end == start ? null : source.substring(start, end);
        }
        return null;
    }

    private int skipBomAndWhitespace(String source, int from) {
        int cursor = from;
        if (cursor < source.length() && source.charAt(cursor) == '\uFEFF') {
            cursor++;
        }
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private String localPart(String qualifiedName) {
        int separator = qualifiedName.indexOf(':');
        return separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private int doctypeEnd(String source, int start) {
        char quote = 0;
        int internalSubsetDepth = 0;
        for (int i = start; i < source.length(); i++) {
            char current = source.charAt(i);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == '[') {
                internalSubsetDepth++;
            } else if (current == ']') {
                internalSubsetDepth--;
            } else if (current == '>' && internalSubsetDepth == 0) {
                return i;
            }
        }
        return -1;
    }

    private XmlNode parseXml(String source) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, true);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        setPropertyIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_DTD, "");
        setPropertyIfSupported(factory, XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXMLResolver((publicId, systemId, baseUri, namespace) -> localMyBatisDtd(publicId, systemId));

        XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(source));
        Deque<MutableXmlNode> stack = new ArrayDeque<>();
        MutableXmlNode root = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    int startOffset = findStartOffset(source, reader.getLocation().getCharacterOffset());
                    Map<String, String> attributes = new LinkedHashMap<>();
                    for (int i = 0; i < reader.getAttributeCount(); i++) {
                        attributes.put(reader.getAttributeLocalName(i), reader.getAttributeValue(i));
                    }
                    MutableXmlNode node = new MutableXmlNode(
                            reader.getLocalName(),
                            attributes,
                            startOffset,
                            lineNumberAt(source, startOffset));
                    if (stack.isEmpty()) {
                        if (root != null) {
                            throw new XMLStreamException("multiple root elements");
                        }
                        root = node;
                    } else {
                        stack.peek().content().add(node);
                    }
                    stack.push(node);
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    MutableXmlNode node = stack.pop();
                    int endOffset = reader.getLocation().getCharacterOffset();
                    node.finish(endOffset, lineNumberAt(source, Math.max(0, endOffset - 1)));
                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA
                        || event == XMLStreamConstants.SPACE) {
                    if (!stack.isEmpty()) {
                        stack.peek().content().add(reader.getText());
                    }
                } else if (event == XMLStreamConstants.ENTITY_REFERENCE) {
                    throw new XMLStreamException("entity references other than predefined XML entities are forbidden");
                }
            }
        } finally {
            reader.close();
        }
        return root == null ? null : root.freeze();
    }

    private Object localMyBatisDtd(String publicId, String systemId) throws XMLStreamException {
        boolean knownPublicId = publicId == null || "-//mybatis.org//DTD Mapper 3.0//EN".equals(publicId);
        boolean knownSystemId = "http://mybatis.org/dtd/mybatis-3-mapper.dtd".equals(systemId)
                || "https://mybatis.org/dtd/mybatis-3-mapper.dtd".equals(systemId);
        if (knownPublicId && knownSystemId) {
            return InputStream.nullInputStream();
        }
        throw new XMLStreamException("external entity resolution is forbidden");
    }

    private void setPropertyIfSupported(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // The resolver and isSupportingExternalEntities=false remain the hard no-network boundary.
        }
    }

    private int findStartOffset(String source, int afterStartTagOffset) throws XMLStreamException {
        int fromIndex = Math.min(afterStartTagOffset - 1, source.length() - 1);
        int offset = source.lastIndexOf('<', fromIndex);
        if (offset < 0) {
            throw new XMLStreamException("cannot locate element start in source");
        }
        return offset;
    }

    private int lineNumberAt(String source, int offset) {
        int line = 1;
        int limit = Math.min(Math.max(offset, 0), source.length());
        for (int i = 0; i < limit; i++) {
            char current = source.charAt(i);
            if (current == '\r') {
                line++;
                if (i + 1 < limit && source.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (current == '\n') {
                line++;
            }
        }
        return line;
    }

    private Map<String, Fragment> collectFragments(List<ParsedMapper> mappers) {
        Map<String, Fragment> fragments = new LinkedHashMap<>();
        for (ParsedMapper mapper : mappers) {
            for (XmlNode child : childElements(mapper.root())) {
                if (!"sql".equals(child.name())) {
                    continue;
                }
                String id = requiredAttribute(child, "id", mapper.relativePath(), "sql fragment");
                String fullId = mapper.namespace() + "." + id;
                Fragment previous = fragments.putIfAbsent(fullId, new Fragment(mapper, child, fullId));
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate SQL fragment identity '" + fullId + "' in "
                            + previous.mapper().relativePath() + " and " + mapper.relativePath());
                }
            }
        }
        return Map.copyOf(fragments);
    }

    private void validateFragments(Map<String, Fragment> fragments) {
        fragments.values().stream()
                .sorted(Comparator.comparing(Fragment::fullId))
                .forEach(fragment -> {
                    Deque<String> includeStack = new ArrayDeque<>();
                    includeStack.push(fragment.fullId());
                    expand(
                            fragment.node(),
                            fragment.mapper().namespace(),
                            fragment.mapper().relativePath(),
                            fragment.fullId(),
                            fragments,
                            includeStack,
                            new LinkedHashSet<>(),
                            new ArrayList<>(),
                            new StringBuilder(),
                            true,
                            false,
                            Map.of(),
                            false,
                            true);
                });
    }

    private List<MyBatisSqlStatement> statementsFor(
            ParsedMapper mapper,
            Map<String, Fragment> fragments,
            Map<String, StatementOrigin> identities) {
        List<MyBatisSqlStatement> statements = new ArrayList<>();
        for (XmlNode node : childElements(mapper.root())) {
            if (!STATEMENT_ELEMENTS.contains(node.name())) {
                continue;
            }
            String id = requiredAttribute(node, "id", mapper.relativePath(), node.name() + " statement");
            registerIdentity(identities, mapper, id, 0);
            statements.add(toStatement(mapper, node, id, node.name().toUpperCase(Locale.ROOT), 0, fragments, true));

            List<XmlNode> selectKeys = new ArrayList<>();
            collectSelectKeys(node, selectKeys);
            for (int i = 0; i < selectKeys.size(); i++) {
                int ordinal = i + 1;
                registerIdentity(identities, mapper, id, ordinal);
                statements.add(toStatement(
                        mapper,
                        selectKeys.get(i),
                        id,
                        "SELECT",
                        ordinal,
                        fragments,
                        false));
            }
        }
        return statements;
    }

    private void registerIdentity(
            Map<String, StatementOrigin> identities,
            ParsedMapper mapper,
            String id,
            int selectKeyOrdinal) {
        String identity = mapper.namespace() + "." + id + (selectKeyOrdinal == 0 ? "" : "#selectKey-" + selectKeyOrdinal);
        StatementOrigin previous = identities.putIfAbsent(
                identity,
                new StatementOrigin(mapper.relativePath(), mapper.namespace(), id, selectKeyOrdinal));
        if (previous != null) {
            throw new IllegalArgumentException("duplicate statement identity '" + identity + "' in "
                    + previous.relativePath() + " and " + mapper.relativePath());
        }
    }

    private MyBatisSqlStatement toStatement(
            ParsedMapper mapper,
            XmlNode node,
            String id,
            String commandType,
            int selectKeyOrdinal,
            Map<String, Fragment> fragments,
            boolean skipSelectKeys) {
        LinkedHashSet<String> resolvedFragments = new LinkedHashSet<>();
        List<String> dynamicNodes = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        expand(
                node,
                mapper.namespace(),
                mapper.relativePath(),
                id,
                fragments,
                new ArrayDeque<>(),
                resolvedFragments,
                dynamicNodes,
                sql,
                skipSelectKeys,
                false,
                Map.of(),
                true,
                false);
        String normalizedSql = normalizeSql(sql.toString());
        List<String> placeholders = placeholders(normalizedSql);
        String mapperKey = canonicalMapperKey(mapper.relativePath(), mapper.namespace());
        String statementKey = canonicalStatementKey(
                mapper.relativePath(), mapper.namespace(), id, selectKeyOrdinal);
        return new MyBatisSqlStatement(
                mapper.relativePath(),
                mapper.namespace(),
                id,
                commandType,
                selectKeyOrdinal > 0,
                selectKeyOrdinal,
                node.startLine(),
                node.endLine(),
                rawXml(mapper.source(), node),
                normalizedSql,
                dynamicNodes,
                List.copyOf(placeholders),
                List.copyOf(resolvedFragments),
                mapper.sourceSha256(),
                mapperKey,
                statementKey);
    }

    private void expand(
            XmlNode node,
            String namespace,
            String mapperRelativePath,
            String statementId,
            Map<String, Fragment> fragments,
            Deque<String> includeStack,
            LinkedHashSet<String> resolvedFragments,
            List<String> dynamicNodes,
            StringBuilder sql,
            boolean skipSelectKeys,
            boolean recordNodeName,
            Map<String, String> variables,
            boolean strictProperties,
            boolean fragmentContext) {
        if (recordNodeName) {
            dynamicNodes.add(node.name());
        }
        for (Object part : node.content()) {
            if (part instanceof String text) {
                sql.append(fragmentContext
                        ? resolveProperties(text, variables, false, statementId, mapperRelativePath)
                        : text);
                continue;
            }
            XmlNode child = (XmlNode) part;
            if (skipSelectKeys && "selectKey".equals(child.name())) {
                continue;
            }
            if ("include".equals(child.name())) {
                String refid = resolveProperties(
                        requiredAttribute(child, "refid", mapperRelativePath, "include"),
                        variables,
                        strictProperties,
                        statementId,
                        mapperRelativePath);
                Map<String, String> scopedVariables = includeVariables(
                        child, variables, strictProperties, statementId, mapperRelativePath);
                if (!strictProperties && PROPERTY_PLACEHOLDER.matcher(refid).find()) {
                    continue;
                }
                String fullId = refid.contains(".") ? refid : namespace + "." + refid;
                Fragment fragment = fragments.get(fullId);
                if (fragment == null) {
                    throw new IllegalArgumentException("missing include '" + fullId + "' while expanding '"
                            + statementId + "' in " + mapperRelativePath);
                }
                if (includeStack.contains(fullId)) {
                    List<String> cycle = new ArrayList<>(includeStack);
                    java.util.Collections.reverse(cycle);
                    cycle.add(fullId);
                    throw new IllegalArgumentException("include cycle while expanding '"
                            + statementId + "' in " + mapperRelativePath + ": "
                            + String.join(" -> ", cycle));
                }
                resolvedFragments.add(fullId);
                includeStack.push(fullId);
                expand(
                        fragment.node(),
                        fragment.mapper().namespace(),
                        mapperRelativePath,
                        fullId,
                        fragments,
                        includeStack,
                        resolvedFragments,
                        dynamicNodes,
                        sql,
                        skipSelectKeys,
                        false,
                        scopedVariables,
                        strictProperties,
                        true);
                includeStack.pop();
            } else {
                expand(
                        child,
                        namespace,
                        mapperRelativePath,
                        statementId,
                        fragments,
                        includeStack,
                        resolvedFragments,
                        dynamicNodes,
                        sql,
                        skipSelectKeys,
                        true,
                        variables,
                        strictProperties,
                        fragmentContext);
            }
        }
    }

    private Map<String, String> includeVariables(
            XmlNode include,
            Map<String, String> inherited,
            boolean strictProperties,
            String expansionContext,
            String mapperRelativePath) {
        Map<String, String> declared = new LinkedHashMap<>();
        for (XmlNode child : childElements(include)) {
            if (!"property".equals(child.name())) {
                continue;
            }
            String name = requiredAttribute(child, "name", mapperRelativePath, "include property");
            String rawValue = requiredAttribute(child, "value", mapperRelativePath, "include property '" + name + "'");
            String value = resolveProperties(
                    rawValue, inherited, strictProperties, expansionContext, mapperRelativePath);
            if (declared.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException(
                        "duplicate include property '" + name + "' in " + mapperRelativePath);
            }
        }
        if (declared.isEmpty()) {
            return inherited;
        }
        Map<String, String> scoped = new LinkedHashMap<>(inherited);
        scoped.putAll(declared);
        return Map.copyOf(scoped);
    }

    private String resolveProperties(
            String value,
            Map<String, String> variables,
            boolean strictProperties,
            String expansionContext,
            String mapperRelativePath) {
        Matcher matcher = PROPERTY_PLACEHOLDER.matcher(value);
        StringBuffer resolved = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1).trim();
            String replacement = variables.get(name);
            if (replacement == null) {
                if (strictProperties) {
                    throw new IllegalArgumentException("unresolved include property '" + name + "' while expanding '"
                            + expansionContext + "' in " + mapperRelativePath);
                }
                replacement = matcher.group();
            }
            matcher.appendReplacement(resolved, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private void collectSelectKeys(XmlNode node, List<XmlNode> selectKeys) {
        for (XmlNode child : childElements(node)) {
            if ("selectKey".equals(child.name())) {
                selectKeys.add(child);
            } else if (!"include".equals(child.name())) {
                collectSelectKeys(child, selectKeys);
            }
        }
    }

    private List<XmlNode> childElements(XmlNode node) {
        List<XmlNode> children = new ArrayList<>();
        for (Object part : node.content()) {
            if (part instanceof XmlNode child) {
                children.add(child);
            }
        }
        return children;
    }

    private String requiredAttribute(XmlNode node, String name, String relativePath, String context) {
        String value = node.attributes().getOrDefault(name, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing required '" + name + "' on " + context + " in " + relativePath);
        }
        return value;
    }

    private List<String> placeholders(String sql) {
        List<String> result = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(sql);
        while (matcher.find()) {
            result.add(matcher.group());
        }
        return result;
    }

    private String normalizeSql(String sql) {
        return sql.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private String rawXml(String source, XmlNode node) {
        int start = Math.max(0, Math.min(node.startOffset(), source.length()));
        int end = Math.max(start, Math.min(node.endOffset(), source.length()));
        return source.substring(start, end);
    }

    static String canonicalMapperKey(String relativePath, String namespace) {
        String fileName = Path.of(relativePath).getFileName().toString();
        String withoutExtension = fileName.replaceFirst("(?i)\\.xml$", "");
        return slug(withoutExtension + "-" + namespace, "mapper") + "-"
                + shortSha256(relativePath + "\n" + namespace);
    }

    static String canonicalStatementKey(String relativePath, String namespace, String id, int selectKeyOrdinal) {
        String readable = namespace + "-" + id;
        if (selectKeyOrdinal > 0) {
            readable += "-select-key-" + selectKeyOrdinal;
        }
        return slug(readable, "statement") + "-"
                + shortSha256(relativePath + "\n" + namespace + "\n" + id + "\n" + selectKeyOrdinal);
    }

    private static String slug(String value, String fallback) {
        String slug = value.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
                .replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isBlank() ? fallback : slug;
    }

    private static String shortSha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String logicalPath(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    private String conciseMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private record ParsedMapper(
            String relativePath,
            String namespace,
            String sourceSha256,
            String source,
            XmlNode root) {
    }

    private record RootElement(String prefix, String localName, String namespaceUri) {
    }

    private record Fragment(ParsedMapper mapper, XmlNode node, String fullId) {
    }

    private record StatementOrigin(String relativePath, String namespace, String id, int selectKeyOrdinal) {
    }

    private record XmlNode(
            String name,
            Map<String, String> attributes,
            List<Object> content,
            int startOffset,
            int endOffset,
            int startLine,
            int endLine) {

        private XmlNode {
            attributes = Map.copyOf(attributes);
            content = List.copyOf(content);
        }
    }

    private static final class MutableXmlNode {
        private final String name;
        private final Map<String, String> attributes;
        private final List<Object> content = new ArrayList<>();
        private final int startOffset;
        private final int startLine;
        private int endOffset;
        private int endLine;

        private MutableXmlNode(String name, Map<String, String> attributes, int startOffset, int startLine) {
            this.name = name;
            this.attributes = attributes;
            this.startOffset = startOffset;
            this.startLine = startLine;
        }

        private List<Object> content() {
            return content;
        }

        private void finish(int endOffset, int endLine) {
            this.endOffset = endOffset;
            this.endLine = endLine;
        }

        private XmlNode freeze() {
            List<Object> immutableContent = new ArrayList<>();
            for (Object part : content) {
                immutableContent.add(part instanceof MutableXmlNode child ? child.freeze() : part);
            }
            return new XmlNode(name, attributes, immutableContent, startOffset, endOffset, startLine, endLine);
        }
    }
}
