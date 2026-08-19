package com.processdesk.harness;

import com.processdesk.Fixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A decision model has no steps, so projecting it as a process yields nothing — and the
 * assistant answered "I can't see any steps in this file" to a fair question about a
 * decision table. These cases pin the shape a decision is shown in instead.
 */
class DecisionProjectionTest {

    private final String dmn = Fixtures.refundDecision();

    @Test
    void aDecisionModelIsRecognisedAsOne() {
        assertTrue(DecisionProjection.looksLikeDecisionModel(dmn));
        assertFalse(DecisionProjection.looksLikeDecisionModel(Fixtures.memberRefund()),
                "a process must not be mistaken for a decision model");
    }

    @Test
    void carriesWhatItDecidesAndWhatItNeeds() {
        DecisionProjection projection = DecisionProjection.of(dmn);

        assertEquals("Refund Approval", projection.modelName());
        assertEquals(java.util.List.of("Approval Route"), projection.decisionNames());
        assertTrue(projection.inputs().contains("Refund Amount"));
        assertTrue(projection.inputs().contains("Member Tier"));
    }

    @Test
    void rulesReadAsConditionsAndOutcomes() {
        DecisionProjection.Decision decision = DecisionProjection.of(dmn).decisions().get(0);

        assertEquals(3, decision.rules().size());
        assertTrue(decision.rules().get(0).contains("Refund Amount <= 50"),
                () -> "expected the threshold in the rule: " + decision.rules().get(0));
        assertTrue(decision.rules().get(0).contains("\"Automatic\""));
        // "-" in DMN means the column is irrelevant to that rule, which must not read as a value.
        assertTrue(decision.rules().get(0).contains("any Member Tier"),
                () -> "a don't-care column must say so: " + decision.rules().get(0));
    }

    @Test
    void renderCarriesTheRulesAndNoMarkup() {
        String rendered = DecisionProjection.of(dmn).render();

        assertTrue(rendered.contains("Refund Approval"));
        assertTrue(rendered.contains("the first rule that matches wins"), "hit policy in plain words");
        assertTrue(rendered.contains("Manager Approval"));
        // "<=" is a threshold the model needs, so look for tags rather than any "<".
        assertFalse(rendered.contains("</") || rendered.contains("<decision") || rendered.contains("<text"),
                "no markup reaches the model");
        assertTrue(rendered.contains("<= 50"), "thresholds survive intact");
        assertFalse(rendered.contains("InputData_"), "no identifiers reach the model");
        assertFalse(rendered.contains("typeRef"), "no schema detail reaches the model");
    }

    @Test
    void projectionIsFarSmallerThanTheFile() {
        assertTrue(DecisionProjection.of(dmn).render().length() * 4 < dmn.length(),
                "the point is to leave the file behind");
    }

    @Test
    void aFileWithNoDecisionsSaysSo() {
        DecisionProjection empty = DecisionProjection.of("<definitions xmlns=\"x\"/>");

        assertTrue(empty.isEmpty());
        assertTrue(empty.render().contains("no decisions"));
    }
}
