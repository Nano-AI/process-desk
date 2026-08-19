package com.processdesk.harness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.processdesk.Fixtures;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HarnessTest {

    private String refundProcess;
    private final ProcessEditor editor = new ProcessEditor();
    private final ValidationGates gates = new ValidationGates();

    @BeforeEach
    void loadFixture() throws Exception {
        refundProcess = Fixtures.refundProcess();
    }

    @Test
    void theFixtureItselfPassesEveryGate() {
        List<GateResult> results = gates.run(refundProcess);
        assertTrue(ValidationGates.allPassed(results),
                () -> "fixture should be valid, got: " + results);
    }

    @Test
    void renamingKeepsTheProcessValid() throws Exception {
        ProcessEditor.EditResult result = editor.rename(refundProcess, "Task_Approve", "Supervisor Review");

        assertTrue(result.xml().contains("Supervisor Review"));
        assertTrue(ValidationGates.allPassed(gates.run(result.xml())));
        // Renaming must not touch the identifier — references elsewhere depend on it.
        assertTrue(result.xml().contains("Task_Approve"));
    }

    @Test
    void addingAStepRewiresTheProcessAndKeepsItValid() throws Exception {
        ProcessEditor.EditResult result = editor.addTaskAfter(refundProcess, "Task_Submit", "Quality Check");

        List<GateResult> results = gates.run(result.xml());
        assertTrue(ValidationGates.allPassed(results), () -> "gates rejected a valid edit: " + results);

        BpmnDocument after = BpmnDocument.parse(result.xml());
        assertEquals(List.of("Submit Request", "Quality Check", "Manager Approval"),
                after.stepNamesInFlowOrder(),
                "the new step must sit between the anchor and what followed it");

        // The new step sits between the anchor and what used to follow it.
        String newTaskId = after.byName("Quality Check").orElseThrow().getAttribute("id");
        assertEquals(newTaskId, after.outgoingFlowOf("Task_Submit").orElseThrow().getAttribute("targetRef"));
        assertEquals("Task_Approve", after.outgoingFlowOf(newTaskId).orElseThrow().getAttribute("targetRef"));

        // Every new step gets a diagram shape, or the editor would render nothing.
        assertTrue(after.shapeFor(newTaskId).isPresent());
    }

    @Test
    void addedStepsGetGeneratedIdentifiersNotSuppliedOnes() throws Exception {
        String first = editor.addTaskAfter(refundProcess, "Task_Submit", "Check").xml();
        String second = editor.addTaskAfter(first, "Task_Submit", "Check").xml();

        BpmnDocument doc = BpmnDocument.parse(second);
        long distinctIds = doc.elements(BpmnDocument.STEP_TYPES).stream()
                .map(el -> el.getAttribute("id"))
                .distinct()
                .count();
        assertEquals(4, distinctIds, "identical requests must not collide on identifiers");
    }

    @Test
    void connectionsGateCatchesADanglingReference() {
        String broken = refundProcess.replace("targetRef=\"Task_Approve\"", "targetRef=\"Task_Ghost\"");

        List<GateResult> results = gates.run(broken);
        assertFalse(ValidationGates.allPassed(results));
        GateResult connections = results.stream().filter(g -> g.id().equals("connections")).findFirst().orElseThrow();
        assertFalse(connections.ok());
    }

    @Test
    void rulesGateCatchesAStepWithNoWayOut() {
        String orphaned = refundProcess.replace(
                "<bpmn2:sequenceFlow id=\"Flow_3\" sourceRef=\"Task_Approve\" targetRef=\"EndEvent_1\"/>", "");

        List<GateResult> results = gates.run(orphaned);
        assertFalse(ValidationGates.allPassed(results));
        GateResult rules = results.stream().filter(g -> g.id().equals("rules")).findFirst().orElseThrow();
        assertFalse(rules.ok());
        assertTrue(rules.detail().contains("Manager Approval"), "failure should name the step in plain language");
    }

    @Test
    void structureGateRejectsMalformedXml() {
        GateResult result = gates.structure("<bpmn2:definitions><broken>");
        assertFalse(result.ok());
    }
}
