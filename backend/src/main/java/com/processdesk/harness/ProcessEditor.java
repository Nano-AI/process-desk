package com.processdesk.harness;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Applies structural edits to a BPMN file.
 *
 * <p>Two rules hold for every method here, and they are what make model-proposed
 * edits safe: identifiers are generated in this class (never taken from the caller),
 * and diagram geometry is computed here (the model never supplies coordinates).
 * The AI layer chooses <em>which</em> edit to make and <em>where</em>; this class
 * decides how it is written.
 */
@Component
public class ProcessEditor {

    public record EditResult(String xml, String summary) {}

    /** Renames a step. The identifier is untouched, so references elsewhere keep working. */
    public EditResult rename(String xml, String targetId, String newName) throws Exception {
        BpmnDocument doc = BpmnDocument.parse(xml);
        Element target = doc.byId(targetId)
                .orElseThrow(() -> new IllegalArgumentException("No element with id " + targetId));
        String oldName = BpmnDocument.displayName(target);
        target.setAttribute("name", newName);
        return new EditResult(doc.serialize(), "Renamed \"" + oldName + "\" to \"" + newName + "\".");
    }

    /**
     * Inserts a new task directly after {@code anchorId}, so the path becomes
     * anchor → new task → whatever followed the anchor.
     */
    public EditResult addTaskAfter(String xml, String anchorId, String taskName) throws Exception {
        BpmnDocument doc = BpmnDocument.parse(xml);
        Element anchor = doc.byId(anchorId)
                .orElseThrow(() -> new IllegalArgumentException("No element with id " + anchorId));
        Element process = (Element) anchor.getParentNode();

        String newTaskId = "Task_" + shortId();
        String newFlowId = "Flow_" + shortId();

        // Rewire: the anchor's existing outgoing flow now starts at the new task,
        // and a new flow carries the anchor into it.
        Optional<Element> existingOutgoing = doc.outgoingFlowOf(anchorId);
        String oldFlowId = existingOutgoing.map(f -> f.getAttribute("id")).orElse(null);
        existingOutgoing.ifPresent(f -> f.setAttribute("sourceRef", newTaskId));

        Element task = doc.dom().createElementNS(BpmnDocument.BPMN_NS, "bpmn2:task");
        task.setAttribute("id", newTaskId);
        task.setAttribute("name", taskName);
        task.appendChild(refChild(doc, "incoming", newFlowId));
        if (oldFlowId != null) {
            task.appendChild(refChild(doc, "outgoing", oldFlowId));
        }

        Element flow = doc.dom().createElementNS(BpmnDocument.BPMN_NS, "bpmn2:sequenceFlow");
        flow.setAttribute("id", newFlowId);
        flow.setAttribute("sourceRef", anchorId);
        flow.setAttribute("targetRef", newTaskId);

        // Flow nodes must precede sequence flows in a BPMN process element.
        Optional<Element> firstFlow = doc.sequenceFlows().stream()
                .filter(f -> f.getParentNode() == process)
                .findFirst();
        if (firstFlow.isPresent()) {
            process.insertBefore(task, firstFlow.get());
        } else {
            process.appendChild(task);
        }
        process.appendChild(flow);

        // The anchor's own <outgoing> child still names the old flow.
        if (oldFlowId != null) {
            for (Element child : BpmnDocument.childElements(anchor)) {
                if ("outgoing".equals(child.getLocalName()) && oldFlowId.equals(child.getTextContent().trim())) {
                    child.setTextContent(newFlowId);
                }
            }
        }

        drawNewTask(doc, anchorId, newTaskId, newFlowId, oldFlowId);

        String summary = "Added \"" + taskName + "\" after \"" + BpmnDocument.displayName(anchor) + "\".";
        return new EditResult(doc.serialize(), summary);
    }

    /** Places the new task below its anchor and reroutes the affected edges. */
    private void drawNewTask(BpmnDocument doc, String anchorId, String newTaskId, String newFlowId, String oldFlowId) {
        List<Element> planes = doc.elements("BPMNPlane");
        if (planes.isEmpty()) {
            return;
        }
        Element plane = planes.get(0);

        double[] anchorBounds = boundsOf(doc, anchorId).orElse(new double[] {100, 100, 154, 64});
        double x = anchorBounds[0];
        double y = anchorBounds[1] + anchorBounds[3] + 60;
        double width = 154;
        double height = 64;

        Element shape = doc.dom().createElementNS(BpmnDocument.BPMNDI_NS, "bpmndi:BPMNShape");
        shape.setAttribute("id", "Shape_" + newTaskId);
        shape.setAttribute("bpmnElement", newTaskId);
        Element bounds = doc.dom().createElementNS(BpmnDocument.DC_NS, "dc:Bounds");
        bounds.setAttribute("x", num(x));
        bounds.setAttribute("y", num(y));
        bounds.setAttribute("width", num(width));
        bounds.setAttribute("height", num(height));
        shape.appendChild(bounds);
        plane.appendChild(shape);

        Element edge = doc.dom().createElementNS(BpmnDocument.BPMNDI_NS, "bpmndi:BPMNEdge");
        edge.setAttribute("id", "Edge_" + newFlowId);
        edge.setAttribute("bpmnElement", newFlowId);
        edge.appendChild(waypoint(doc, anchorBounds[0] + anchorBounds[2] / 2, anchorBounds[1] + anchorBounds[3]));
        edge.appendChild(waypoint(doc, x + width / 2, y));
        plane.appendChild(edge);

        // The rerouted flow now leaves the new task instead of the anchor.
        if (oldFlowId != null) {
            doc.edgeFor(oldFlowId).ifPresent(oldEdge -> {
                for (Element child : BpmnDocument.childElements(oldEdge)) {
                    if ("waypoint".equals(child.getLocalName())) {
                        child.setAttribute("x", num(x + width / 2));
                        child.setAttribute("y", num(y + height));
                        return;
                    }
                }
            });
        }
    }

    private Optional<double[]> boundsOf(BpmnDocument doc, String elementId) {
        return doc.shapeFor(elementId).flatMap(shape -> BpmnDocument.childElements(shape).stream()
                .filter(c -> "Bounds".equals(c.getLocalName()))
                .findFirst()
                .map(b -> new double[] {
                        Double.parseDouble(b.getAttribute("x")),
                        Double.parseDouble(b.getAttribute("y")),
                        Double.parseDouble(b.getAttribute("width")),
                        Double.parseDouble(b.getAttribute("height"))
                }));
    }

    private Element refChild(BpmnDocument doc, String localName, String flowId) {
        Element el = doc.dom().createElementNS(BpmnDocument.BPMN_NS, "bpmn2:" + localName);
        el.setTextContent(flowId);
        return el;
    }

    private Element waypoint(BpmnDocument doc, double x, double y) {
        Element wp = doc.dom().createElementNS(BpmnDocument.DI_NS, "di:waypoint");
        wp.setAttribute("x", num(x));
        wp.setAttribute("y", num(y));
        return wp;
    }

    private static String num(double value) {
        return String.valueOf(Math.round(value));
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
