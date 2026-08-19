package com.processdesk.kogito;

import com.processdesk.harness.ProcessEditor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.processdesk.Fixtures;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the Kogito engines actually execute the assets, rather than the application
 * merely parsing them. If these pass, a file that survives the edit harness is a file
 * Kogito can run.
 */
class KogitoRuntimeTest {

    private final ProcessRuntime processes = new ProcessRuntime();
    private final DecisionRuntime decisions = new DecisionRuntime();
    private final ProcessEditor editor = new ProcessEditor();

    private String bpmn() throws Exception {
        return Fixtures.refundProcess();
    }

    private String dmn() throws Exception {
        return Fixtures.refundDecision();
    }

    @Test
    @DisplayName("Kogito runs the process to completion")
    void kogitoRunsTheProcess() throws Exception {
        ProcessRuntime.ProcessOutcome outcome = processes.start(bpmn(), Map.of());

        assertEquals("refund-request", outcome.processId());
        assertEquals("Refund Request", outcome.processName());
        assertEquals("completed", outcome.status(),
                () -> "the fixture has no wait states, so Kogito should run it start to finish; error: "
                        + outcome.error());
    }

    @Test
    @DisplayName("Kogito still runs a process after the harness edits it")
    void kogitoRunsAnEditedProcess() throws Exception {
        String edited = editor.addTaskAfter(bpmn(), "Task_Submit", "Quality Check").xml();

        ProcessRuntime.ProcessOutcome outcome = processes.start(edited, Map.of());

        assertEquals("completed", outcome.status(),
                () -> "an edit that passes the gates must still be executable by Kogito; error: "
                        + outcome.error());
        assertTrue(processes.read(edited).get(0).getId().equals("refund-request"));
    }

    @Test
    @DisplayName("Kogito evaluates the decision table")
    void kogitoEvaluatesDecisions() throws Exception {
        String model = dmn();

        var small = decisions.evaluateAll(model, Map.of("Refund Amount", 25, "Member Tier", "Standard"));
        assertEquals("Automatic", routeOf(small));

        var executive = decisions.evaluateAll(model, Map.of("Refund Amount", 500, "Member Tier", "Executive"));
        assertEquals("Manager Approval", routeOf(executive));

        var standard = decisions.evaluateAll(model, Map.of("Refund Amount", 500, "Member Tier", "Standard"));
        assertEquals("Store Review", routeOf(standard));
    }

    @Test
    @DisplayName("A decision model reports its decisions before being run")
    void decisionModelLoads() throws Exception {
        var model = decisions.load(dmn());

        assertEquals("Refund Approval", model.getName());
        assertEquals(1, model.getDecisions().size());
    }

    @Test
    @DisplayName("The run reports which branch work actually took")
    void runRecordsThePathTaken() {
        ProcessRuntime.ProcessOutcome outcome = processes.start(Fixtures.memberRefund(), Map.of());

        assertEquals("completed", outcome.status(), () -> "error: " + outcome.error());
        List<String> path = outcome.path();

        // The gateway defaults to the automatic branch, so the manager step is not entered.
        assertEquals(List.of("Submit Request", "Verify Membership", "Decide Approval Route",
                "Approve Automatically", "Issue Refund", "Notify Member"), path,
                "the path is the order work entered each step, and only steps");
        assertFalse(path.contains("Manager Approval"),
                () -> "the other branch was not taken, so it must not be reported as run: " + path);
        assertFalse(path.contains("Which approval?"), "a gateway is wiring, not a place work happened");
        assertFalse(path.contains("Refund requested"), "an event is not a step");
    }

    @Test
    @DisplayName("Kogito refuses a process it cannot read")
    void kogitoRejectsBrokenProcess() {
        assertThrows(IllegalArgumentException.class, () -> processes.read("<definitions><nope>"));
    }

    private String routeOf(DecisionRuntime.EvaluationResult result) {
        List<DecisionRuntime.DecisionOutcome> outcomes = result.decisions();
        assertFalse(outcomes.isEmpty(), "expected at least one decision result");
        DecisionRuntime.DecisionOutcome route = outcomes.get(0);
        assertTrue(route.succeeded(), () -> "decision failed: " + route.messages());
        return String.valueOf(route.result());
    }
}
