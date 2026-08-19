package com.processdesk.harness;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Thin DOM wrapper over a BPMN file. Everything that reads or rewrites process
 * XML goes through here so parsing is configured (and hardened) in one place.
 */
public final class BpmnDocument {

    public static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    public static final String BPMNDI_NS = "http://www.omg.org/spec/BPMN/20100524/DI";
    public static final String DC_NS = "http://www.omg.org/spec/DD/20100524/DC";
    public static final String DI_NS = "http://www.omg.org/spec/DD/20100524/DI";

    /** Element local names that a user would call "a step". */
    public static final List<String> STEP_TYPES = List.of(
            "task", "userTask", "serviceTask", "scriptTask", "businessRuleTask", "callActivity");

    /** Every node that can sit on a sequence flow. */
    public static final List<String> FLOW_NODES = List.of(
            "task", "userTask", "serviceTask", "scriptTask", "businessRuleTask", "callActivity",
            "startEvent", "endEvent", "exclusiveGateway", "parallelGateway", "inclusiveGateway");

    private final Document doc;

    private BpmnDocument(Document doc) {
        this.doc = doc;
    }

    public static BpmnDocument parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        // Assets are user-supplied files; disable external entity resolution.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return new BpmnDocument(builder.parse(new InputSource(new StringReader(xml))));
    }

    public Document dom() {
        return doc;
    }

    public String serialize() throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = tf.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    public List<Element> elements(String localName) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        List<Element> out = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    public List<Element> elements(List<String> localNames) {
        List<Element> out = new ArrayList<>();
        for (String name : localNames) {
            out.addAll(elements(name));
        }
        return out;
    }

    public Optional<Element> byId(String id) {
        NodeList all = doc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            Element el = (Element) all.item(i);
            if (id.equals(el.getAttribute("id"))) {
                return Optional.of(el);
            }
        }
        return Optional.empty();
    }

    /** Case-insensitive lookup of a flow node by its display name. */
    public Optional<Element> byName(String name) {
        String wanted = name.trim().toLowerCase();
        return elements(FLOW_NODES).stream()
                .filter(el -> el.getAttribute("name").trim().toLowerCase().equals(wanted))
                .findFirst();
    }

    /** Step names in document order, which is not necessarily the order work happens in. */
    public List<String> stepNames() {
        return elements(STEP_TYPES).stream()
                .map(el -> el.getAttribute("name"))
                .filter(n -> !n.isBlank())
                .toList();
    }

    /**
     * Step names grouped by how far work has travelled to reach them, nearest first.
     *
     * <p>Each inner list holds steps the same number of hops from the start — that is,
     * alternatives on different branches of a gateway. Following only the first outgoing
     * flow would walk one branch and silently omit the others, which left "Manager
     * Approval" invisible to the model and therefore impossible for it to name.
     *
     * <p>Falls back to a single group in document order when there is no start event.
     */
    public List<List<String>> stepNamesByDepth() {
        List<Element> starts = elements("startEvent");
        if (starts.isEmpty()) {
            List<String> names = stepNames();
            return names.isEmpty() ? List.of() : List.of(names);
        }

        Map<String, String> stepNamesById = new LinkedHashMap<>();
        for (Element step : elements(STEP_TYPES)) {
            String id = step.getAttribute("id");
            if (!id.isBlank()) {
                stepNamesById.put(id, displayName(step));
            }
        }

        // Every flow leaving a node, not just the first.
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (Element flow : sequenceFlows()) {
            outgoing.computeIfAbsent(flow.getAttribute("sourceRef"), k -> new ArrayList<>())
                    .add(flow.getAttribute("targetRef"));
        }

        Map<Integer, List<String>> byDepth = new TreeMap<>();
        Set<String> visited = new HashSet<>();
        Deque<Map.Entry<String, Integer>> queue = new ArrayDeque<>();
        String startId = starts.get(0).getAttribute("id");
        queue.add(Map.entry(startId, 0));
        visited.add(startId);

        while (!queue.isEmpty()) {
            Map.Entry<String, Integer> current = queue.poll();
            String name = stepNamesById.get(current.getKey());
            if (name != null && !name.isBlank()) {
                byDepth.computeIfAbsent(current.getValue(), k -> new ArrayList<>()).add(name);
            }
            for (String next : outgoing.getOrDefault(current.getKey(), List.of())) {
                if (visited.add(next)) {
                    queue.add(Map.entry(next, current.getValue() + 1));
                }
            }
        }

        return byDepth.values().stream().map(List::copyOf).toList();
    }

    /** Every step, nearest to the start first. Branch alternatives are adjacent. */
    public List<String> stepNamesInFlowOrder() {
        return stepNamesByDepth().stream().flatMap(List::stream).toList();
    }

    public List<Element> sequenceFlows() {
        return elements("sequenceFlow");
    }

    public Optional<Element> outgoingFlowOf(String nodeId) {
        return sequenceFlows().stream()
                .filter(f -> nodeId.equals(f.getAttribute("sourceRef")))
                .findFirst();
    }

    public Optional<Element> shapeFor(String elementId) {
        return elements("BPMNShape").stream()
                .filter(s -> elementId.equals(s.getAttribute("bpmnElement")))
                .findFirst();
    }

    public Optional<Element> edgeFor(String flowId) {
        return elements("BPMNEdge").stream()
                .filter(e -> flowId.equals(e.getAttribute("bpmnElement")))
                .findFirst();
    }

    public static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) n);
            }
        }
        return out;
    }

    public static String displayName(Element el) {
        String name = el.getAttribute("name");
        return name.isBlank() ? el.getAttribute("id") : name;
    }
}
