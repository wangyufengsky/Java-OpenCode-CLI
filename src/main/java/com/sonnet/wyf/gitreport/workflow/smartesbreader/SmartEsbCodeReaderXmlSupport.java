package com.sonnet.wyf.gitreport.workflow.smartesbreader;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

final class SmartEsbCodeReaderXmlSupport {
    private static final List<String> JAVA_HINT_ATTRS = List.of("class", "clazz", "className", "impl", "implementation", "ref", "service", "bean", "target", "serviceId");
    private static final Set<String> FLOW_TAGS = Set.of("from", "process", "to", "choice", "when", "otherwise", "pipeline");

    List<SmartEsbFlowNode> flowNodes(Path transactionXml) throws Exception {
        List<SmartEsbFlowNode> nodes = new ArrayList<>();
        Document document = parseXml(transactionXml);
        walkFlow(document.getDocumentElement(), List.of(localName(document.getDocumentElement())), nodes);
        return nodes;
    }

    List<String> javaHints(Path baseXml) throws Exception {
        List<String> hints = new ArrayList<>();
        Document document = parseXml(baseXml);
        collectHints(document.getDocumentElement(), hints);
        return hints;
    }

    Document parseXml(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        Document document = factory.newDocumentBuilder().parse(path.toFile());
        document.getDocumentElement().normalize();
        return document;
    }

    List<Path> collectFiles(Path root, String suffix) throws IOException {
        if (root == null || !Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix))
                    .sorted()
                    .toList();
        }
    }

    String localName(Element element) {
        String name = element.getLocalName();
        return name == null ? element.getTagName() : name;
    }

    private void walkFlow(Element element, List<String> stack, List<SmartEsbFlowNode> nodes) {
        String tag = localName(element);
        String serviceId = firstAttr(element, JAVA_HINT_ATTRS);
        if ((FLOW_TAGS.contains(tag) || !serviceId.isBlank()) && !serviceId.isBlank()) {
            nodes.add(new SmartEsbFlowNode(tag, "/" + String.join("/", stack), serviceId, attributes(element)));
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                List<String> childStack = new ArrayList<>(stack);
                childStack.add(localName(childElement));
                walkFlow(childElement, childStack, nodes);
            }
        }
    }

    private void collectHints(Element element, List<String> hints) {
        for (String attr : JAVA_HINT_ATTRS) {
            String value = element.getAttribute(attr);
            if (!value.isBlank()) {
                hints.add(value);
            }
        }
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                collectHints(childElement, hints);
            }
        }
    }

    private String firstAttr(Element element, List<String> attrs) {
        for (String attr : attrs) {
            String value = element.getAttribute(attr).trim();
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Map<String, String> attributes(Element element) {
        Map<String, String> attrs = new LinkedHashMap<>();
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node node = element.getAttributes().item(i);
            attrs.put(node.getNodeName(), node.getNodeValue());
        }
        return attrs;
    }
}
