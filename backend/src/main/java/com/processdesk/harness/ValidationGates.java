package com.processdesk.harness;

import org.jbpm.bpmn2.xml.BPMNDISemanticModule;
import org.jbpm.bpmn2.xml.BPMNExtensionsSemanticModule;
import org.jbpm.bpmn2.xml.BPMNSemanticModule;
import org.jbpm.compiler.xml.XmlProcessReader;
import org.jbpm.compiler.xml.core.SemanticModules;
import org.kie.api.definition.process.Process;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The three gates every proposed change passes before a human is asked to approve it.
 * Nothing reaches the editor preview, and nothing is written to disk, until all three pass.
 *
 * <p>Gate wording is deliberately plain: these strings are shown to business users.
 */
@Component
public class ValidationGates {

    private static final Logger log = LoggerFactory.getLogger(ValidationGates.class);

    /** The BPMN dialect Kogito understands: process semantics, diagram, and Kogito extensions. */
    private static SemanticModules bpmnModules() {
        SemanticModules modules = new SemanticModules();
        modules.addSemanticModule(new BPMNSemanticModule());
        modules.addSemanticModule(new BPMNDISemanticModule());
        modules.addSemanticModule(new BPMNExtensionsSemanticModule());
        return modules;
    }

    public List<GateResult> run(String xml) {
        return List.of(structure(xml), connections(xml), rules(xml));
    }

    public static boolean allPassed(List<GateResult> gates) {
        return gates.stream().allMatch(GateResult::ok);
    }

    /**
     * Gate 1 — Structure. Loads the file with Kogito's own BPMN parser. Passing here
     * means the Kogito runtime can read the file, not merely that the XML is well-formed.
     */
    GateResult structure(String xml) {
        try {
            XmlProcessReader reader = new XmlProcessReader(
                    bpmnModules(), Thread.currentThread().getContextClassLoader());
            List<Process> processes = reader.read(new StringReader(xml));
            if (processes == null || processes.isEmpty()) {
                return GateResult.fail("structure", "Structure", "This file doesn't contain a process.");
            }
            return GateResult.pass("structure", "Structure",
                    "Kogito can read the file (" + processes.size() + " process definition"
                            + (processes.size() == 1 ? "" : "s") + ").");
        } catch (Exception e) {
            log.debug("structure gate rejected the document", e);
            return GateResult.fail("structure", "Structure",
                    "Kogito couldn't read the file after this change.");
        }
    }

    /** Gate 2 — Connections. Every connection must point at a step that exists. */
    GateResult connections(String xml) {
        try {
            BpmnDocument doc = BpmnDocument.parse(xml);
            Set<String> ids = new HashSet<>();
            for (Element el : doc.elements("*")) {
                String id = el.getAttribute("id");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
            for (Element flow : doc.sequenceFlows()) {
                for (String ref : List.of("sourceRef", "targetRef")) {
                    String target = flow.getAttribute(ref);
                    if (target.isBlank() || !ids.contains(target)) {
                        return GateResult.fail("connections", "Connections",
                                "A connection points at a step that doesn't exist.");
                    }
                }
            }
            return GateResult.pass("connections", "Connections", "Every connection points at a real step.");
        } catch (Exception e) {
            return GateResult.fail("connections", "Connections", "Couldn't read the file.");
        }
    }

    /** Gate 3 — Rules. Reachability: every step needs a way in and a way out. */
    GateResult rules(String xml) {
        try {
            BpmnDocument doc = BpmnDocument.parse(xml);
            List<Element> flows = doc.sequenceFlows();
            Set<String> hasIncoming = new HashSet<>();
            Set<String> hasOutgoing = new HashSet<>();
            for (Element flow : flows) {
                hasOutgoing.add(flow.getAttribute("sourceRef"));
                hasIncoming.add(flow.getAttribute("targetRef"));
            }

            for (Element step : doc.elements(BpmnDocument.STEP_TYPES)) {
                String id = step.getAttribute("id");
                String name = BpmnDocument.displayName(step);
                if (!hasIncoming.contains(id)) {
                    return GateResult.fail("rules", "Rules", "Nothing leads into the \"" + name + "\" step.");
                }
                if (!hasOutgoing.contains(id)) {
                    return GateResult.fail("rules", "Rules",
                            "The \"" + name + "\" step isn't connected to anything after it.");
                }
            }
            for (Element start : doc.elements("startEvent")) {
                if (!hasOutgoing.contains(start.getAttribute("id"))) {
                    return GateResult.fail("rules", "Rules", "The start of the process isn't connected to anything.");
                }
            }
            for (Element end : doc.elements("endEvent")) {
                if (!hasIncoming.contains(end.getAttribute("id"))) {
                    return GateResult.fail("rules", "Rules", "Nothing leads to the end of the process.");
                }
            }
            return GateResult.pass("rules", "Rules", "Every step has a way in and a way out.");
        } catch (Exception e) {
            return GateResult.fail("rules", "Rules", "Couldn't read the file.");
        }
    }
}
