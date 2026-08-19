package com.processdesk.harness;

import com.processdesk.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Editing a decision table.
 *
 * <p>One operation: write one cell, addressed by decision, rule number and column. What used
 * to be four methods — address by condition, by outcome, and the two crossed pairs — was a
 * 2×2 of how you find the rule and which cell you write, and naming each combination
 * separately meant every new phrasing looked like a missing feature.
 *
 * <p>The gates here are the DMN ones. Reusing the process gates would mean handing a DMN
 * file to the BPMN parser, which can only ever fail — a check that always says no is
 * indistinguishable from no check at all.
 */
class DecisionEditorTest {

    private final DecisionEditor editor = new DecisionEditor();
    private final DecisionGates gates = new DecisionGates();
    private final String dmn = Fixtures.refundDecision();

    /**
     * The fixture, as the projection numbers it:
     *   1. Refund Amount <= 50, any Member Tier → "Automatic"
     *   2. Refund Amount > 50, Member Tier "Executive" → "Manager Approval"
     *   3. Refund Amount > 50, any Member Tier → "Store Review"
     */
    private static final String DECISION = "Approval Route";

    @Test
    void theFixturePassesEveryGate() {
        List<GateResult> results = gates.run(dmn);
        assertTrue(DecisionGates.allPassed(results), () -> "fixture rejected: " + results);
    }

    @Test
    void changesTheConditionThatDecidesManagerApproval() throws Exception {
        // The request that started this: make Gold reach Manager Approval.
        DecisionEditor.EditResult result =
                editor.setCell(dmn, DECISION, 2, "Member Tier", "\"Executive\"", "Gold");

        assertEquals(1, result.cellsChanged());
        assertTrue(DecisionGates.allPassed(gates.run(dmn, result.xml())));

        String rules = String.join("\n", DecisionProjection.of(result.xml()).decisions().get(0).rules());
        assertTrue(rules.contains("Member Tier \"Gold\" → \"Manager Approval\""),
                () -> "expected Gold to route to Manager Approval:\n" + rules);
        assertFalse(rules.contains("\"Executive\""), "the old condition is gone");
    }

    @Test
    void changesWhatARuleProduces() throws Exception {
        DecisionEditor.EditResult result =
                editor.setCell(dmn, DECISION, 2, DecisionEditor.OUTCOME, "\"Manager Approval\"", "Regional Review");

        assertTrue(result.xml().contains("\"Regional Review\""),
                "the cell was quoted, so the replacement is quoted too");
        assertTrue(DecisionGates.allPassed(gates.run(dmn, result.xml())));
    }

    @Test
    void anOmittedColumnMeansTheResult() throws Exception {
        // "outcome" and a blank column are the same address, so a model that leaves the
        // field empty when changing a result is understood rather than refused.
        DecisionEditor.EditResult named =
                editor.setCell(dmn, DECISION, 2, DecisionEditor.OUTCOME, "\"Manager Approval\"", "Regional Review");
        DecisionEditor.EditResult blank =
                editor.setCell(dmn, DECISION, 2, "", "\"Manager Approval\"", "Regional Review");

        assertEquals(named.xml(), blank.xml());
    }

    @Test
    void matchesAValueWhetherOrNotItWasQuoted() throws Exception {
        assertEquals(1, editor.setCell(dmn, DECISION, 2, "Member Tier", "Executive", "Gold").cellsChanged());
        assertEquals(1, editor.setCell(dmn, DECISION, 2, "member tier", "\"executive\"", "Gold").cellsChanged(),
                "column and value matching ignore case, as a person would");
    }

    @Test
    void changingAnOutcomeKeepsItAString() throws Exception {
        DecisionEditor.EditResult result =
                editor.setCell(dmn, DECISION, 2, DecisionEditor.OUTCOME, "\"Manager Approval\"", "Regional Review");

        // The cell was quoted; an unquoted replacement would change the value's type from
        // string to a variable reference and break the model.
        assertTrue(result.xml().contains("\"Regional Review\""));
        assertTrue(DecisionGates.allPassed(gates.run(dmn, result.xml())));
    }

    @Test
    void aCellHoldingSomethingElseIsRefused() throws Exception {
        // The check that replaced the guessing. If the coordinate and the value disagree,
        // one of them is wrong and neither is safe to act on.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> editor.setCell(dmn, DECISION, 2, "Member Tier", "Platinum", "Gold"));

        assertTrue(thrown.getMessage().contains("Platinum"), "it names what was expected");
        assertTrue(thrown.getMessage().contains("\"Executive\""), "and what is actually there");
        assertTrue(thrown.getMessage().contains("Nothing was changed."));
    }

    @Test
    void aRuleNumberOffTheEndIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> editor.setCell(dmn, DECISION, 99, "Member Tier", "\"Executive\"", "Gold"));

        assertTrue(thrown.getMessage().contains("rule 99"));
        assertTrue(thrown.getMessage().contains("3 rules"), "it says how many there are");
    }

    @Test
    void ruleZeroIsRefusedRatherThanTreatedAsTheFirst() {
        // Rules are numbered from 1 in the projection. Accepting 0 would quietly shift every
        // coordinate by one.
        assertThrows(IllegalArgumentException.class,
                () -> editor.setCell(dmn, DECISION, 0, "Member Tier", "\"Executive\"", "Gold"));
    }

    @Test
    void namingAColumnThatIsNotThereIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> editor.setCell(dmn, DECISION, 2, "Store Region", "North", "South"));

        assertTrue(thrown.getMessage().contains("Store Region"));
        assertTrue(thrown.getMessage().contains("Member Tier"), "it lists what the decision does look at");
    }

    @Test
    void namingADecisionThatIsNotThereIsRefused() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> editor.setCell(dmn, "Vibe Check", 2, "Member Tier", "\"Executive\"", "Gold"));

        assertTrue(thrown.getMessage().contains("Vibe Check"));
    }

    @Test
    void renamingADecisionAlsoRenamesItsVariable() throws Exception {
        DecisionEditor.EditResult result = editor.renameDecision(dmn, DECISION, "Routing Decision");

        assertEquals(List.of("Routing Decision"), DecisionProjection.of(result.xml()).decisionNames());
        // A decision's variable carries the same name; leaving it behind breaks references.
        assertTrue(result.xml().contains("name=\"Routing Decision\""));
        assertTrue(DecisionGates.allPassed(gates.run(dmn, result.xml())),
                () -> "rename left the model unusable: " + gates.run(dmn, result.xml()));
    }

    @Test
    void shapeGateCatchesARuleWithTooFewConditions() {
        String broken = dmn.replace(
                "<inputEntry id=\"RuleInput_1_2\"><text>-</text></inputEntry>", "");

        List<GateResult> results = gates.run(broken);
        assertFalse(DecisionGates.allPassed(results));
        GateResult shape = results.stream().filter(g -> g.id().equals("shape")).findFirst().orElseThrow();
        assertFalse(shape.ok(), () -> "a rule short of a column must be caught: " + results);
    }

    @Test
    void structureGateUsesTheDmnEngineNotTheBpmnParser() {
        // A well-formed process is not a decision model, and the DMN gate must say so.
        GateResult result = gates.structure(Fixtures.memberRefund());
        assertFalse(result.ok());
    }

    @Test
    void theVocabularyEnumeratesWhatCanBeNamed() {
        DecisionProjection.Vocabulary vocabulary = DecisionProjection.of(dmn).vocabulary();

        assertTrue(vocabulary.columns().contains("Refund Amount"));
        assertTrue(vocabulary.columns().contains("Member Tier"));
        assertTrue(vocabulary.outcomes().contains("\"Manager Approval\""));
        assertTrue(vocabulary.conditions().contains("\"Executive\""));
        assertTrue(vocabulary.decisions().contains(DECISION));
        assertFalse(vocabulary.conditions().contains("-"), "a don't-care is not a value to name");
    }

    @Test
    void theProjectionNumbersItsRules() {
        // The numbers are the addresses an edit is written in, so they have to be visible.
        String rendered = DecisionProjection.of(dmn).render();

        assertTrue(rendered.contains("\n  1. "), rendered);
        assertTrue(rendered.contains("\n  2. "), rendered);
    }
}
