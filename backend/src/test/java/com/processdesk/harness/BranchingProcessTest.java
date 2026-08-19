package com.processdesk.harness;

import com.processdesk.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A process with a gateway is the case a single-branch walk gets wrong.
 *
 * <p>Following only the first outgoing flow left "Manager Approval" out of the projection
 * entirely, so it never reached the schema enum and the model could not name the step even
 * when the user asked for it by name.
 */
class BranchingProcessTest {

    private final String xml = Fixtures.memberRefund();

    @Test
    void everyStepReachesTheProjection() {
        ProcessProjection projection = ProcessProjection.of(xml);

        assertTrue(projection.steps().contains("Manager Approval"),
                "a step on a gateway branch must still be visible to the model");
        assertTrue(projection.steps().contains("Approve Automatically"));
        assertEquals(7, projection.steps().size());
    }

    @Test
    void branchesAreShownAsAlternativesNotASequence() {
        String rendered = ProcessProjection.of(xml).render();

        assertTrue(rendered.contains("Approve Automatically or Manager Approval"),
                () -> "branches must not read as one running after the other: " + rendered);
        assertTrue(rendered.indexOf("Submit Request") < rendered.indexOf("Issue Refund"),
                "work still reaches Submit Request before Issue Refund");
    }

    @Test
    void stepsAreOrderedByDistanceFromTheStart() {
        List<List<String>> stages = assertDoesNotThrow(() -> BpmnDocument.parse(xml).stepNamesByDepth());

        assertEquals(List.of("Submit Request"), stages.get(0));
        assertEquals(List.of("Verify Membership"), stages.get(1));
        assertTrue(stages.stream().anyMatch(stage -> stage.size() == 2),
                "the two approval branches sit at the same distance from the start");
    }

    @Test
    void theFixturePassesEveryGate() {
        List<GateResult> results = new ValidationGates().run(xml);
        assertTrue(ValidationGates.allPassed(results), () -> "branching fixture rejected: " + results);
    }

    @Test
    void aStepCanBeAddedOnABranch() throws Exception {
        BpmnDocument doc = BpmnDocument.parse(xml);
        String managerId = doc.byName("Manager Approval").orElseThrow().getAttribute("id");

        ProcessEditor.EditResult result = new ProcessEditor().addTaskAfter(xml, managerId, "Record Decision");

        assertTrue(ValidationGates.allPassed(new ValidationGates().run(result.xml())));
        assertTrue(ProcessProjection.of(result.xml()).steps().contains("Record Decision"));
    }

    @Test
    void aCycleDoesNotHangTheWalk() throws Exception {
        // Send the last step back to the first: the walk must terminate.
        String looped = xml.replace(
                "<bpmn2:sequenceFlow id=\"F_notify\" sourceRef=\"Task_Notify\" targetRef=\"End\"/>",
                "<bpmn2:sequenceFlow id=\"F_notify\" sourceRef=\"Task_Notify\" targetRef=\"Task_Submit\"/>");

        List<String> steps = assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> BpmnDocument.parse(looped).stepNamesInFlowOrder());

        assertTrue(steps.contains("Submit Request"));
        assertEquals(steps.size(), steps.stream().distinct().count(), "a step must be listed once");
    }
}
