package com.processdesk.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every editor operation, run against a real decision model rather than a fixture, with the
 * gates checked after each one.
 *
 * <p>The fixture tests prove each method does what it says on a table we wrote. This proves
 * they hold on {@code loan-recommendation.dmn} — eleven decisions, fifteen rules in one
 * table, ranges rather than strings, numeric outcomes, and columns that repeat across
 * decisions. Every defect found by hand was in this file and none were reachable from the
 * fixture.
 *
 * <p>The summaries are asserted too, not only the XML. A summary is what the user reads
 * before approving, so a wrong one is a change made under a false description.
 *
 * <p>The coordinates below come from the projection, which numbers rules from 1:
 * <pre>
 * Income Risk Category:  1. >70 → "Low"   2. (10..70] → "Medium"   3. &lt;=10 → "High"
 * Income Risk Score:     1. Borrower.Age &lt;18 → -100   2. [18..35] → 30   ...
 * </pre>
 */
class HarnessSweepTest {

    private final DecisionEditor editor = new DecisionEditor();
    private final DecisionGates gates = new DecisionGates();

    private String loanModel() throws Exception {
        Path path = Path.of("assets/loan-recommendation.dmn");
        assertTrue(Files.exists(path), () -> "missing asset: " + path.toAbsolutePath());
        return Files.readString(path);
    }

    private void assertStillValid(String before, String after, String what) {
        List<GateResult> results = gates.run(before, after);
        assertTrue(DecisionGates.allPassed(results), () -> what + " was rejected: " + results);
    }

    @Test
    @DisplayName("the real model passes every gate before anything touches it")
    void baseline() throws Exception {
        assertStillValid(loanModel(), loanModel(), "the unedited file");
    }

    @Test
    @DisplayName("change when a rule applies")
    void changeACondition() throws Exception {
        String before = loanModel();
        DecisionEditor.EditResult result =
                editor.setCell(before, "Income Risk Category", 1, "Income Risk Score", ">70", ">80");

        assertEquals(1, result.cellsChanged());
        assertEquals("Changed Income Risk Score in rule 1 of \"Income Risk Category\" "
                + "from >70 to >80.", result.summary());

        // The edit itself is exactly right, and the change is still refused — because on its
        // own it leaves 70 < score <= 80 matching nothing. **A single boundary move on a
        // range table is almost always an incomplete edit**, and that is not a defect in the
        // editor but the reason the multi-edit work exists.
        List<GateResult> results = gates.run(before, result.xml());
        GateResult coverage = results.stream()
                .filter(g -> g.id().equals("coverage")).findFirst().orElseThrow();
        assertFalse(coverage.ok(), "moving one edge of a band leaves the band next to it behind");
        assertTrue(results.stream().filter(g -> !g.id().equals("coverage")).allMatch(GateResult::ok),
                "nothing is structurally wrong with it");
    }

    @Test
    @DisplayName("change what a rule produces")
    void changeAnOutcome() throws Exception {
        String before = loanModel();
        DecisionEditor.EditResult result =
                editor.setCell(before, "Income Risk Category", 1, DecisionEditor.OUTCOME, "\"Low\"", "Very low");

        assertEquals("Changed the result in rule 1 of \"Income Risk Category\" "
                + "from \"Low\" to Very low.", result.summary());
        assertTrue(result.xml().contains("\"Very low\""), "a quoted cell stays quoted");
        assertStillValid(before, result.xml(), "changing an outcome");
    }

    @Test
    @DisplayName("the request that six phrasings could not express is now one coordinate")
    void theRequestThatStartedTheCollapse() throws Exception {
        // "Change Income Risk Category to High for values <= 5" — the rule is found by what
        // it produces, and what changes is when it applies. That crossing used to need its
        // own intent kind; now it is rule 3, column Income Risk Score.
        String before = loanModel();
        DecisionEditor.EditResult result =
                editor.setCell(before, "Income Risk Category", 3, "Income Risk Score", "<=10", "<=5");

        String rules = renderedRulesOf(result.xml(), "Income Risk Category");
        assertTrue(rules.contains("Income Risk Score <=5 → \"High\""), () -> rules);

        // And still refused, for the right reason: (5..10] would now match no rule. The pair
        // of edits this needs — High to <=5 and Medium to (5..70] — is one intent short of
        // what a request can currently express.
        assertFalse(DecisionGates.allPassed(gates.run(before, result.xml())));
    }

    @Test
    @DisplayName("a matched pair of edits leaves the table whole")
    void movingBothSidesOfABoundaryIsAccepted() throws Exception {
        // The complete version of the request above, applied as two cell edits. It passes
        // every gate — which is the proof that the refusals above are about the change being
        // half-finished rather than about the editor being unable to make it.
        String before = loanModel();
        String high = editor.setCell(before, "Income Risk Category", 3,
                "Income Risk Score", "<=10", "<=5").xml();
        String both = editor.setCell(high, "Income Risk Category", 2,
                "Income Risk Score", "(10..70]", "(5..70]").xml();

        assertStillValid(before, both, "moving a boundary and its neighbour together");

        String rules = renderedRulesOf(both, "Income Risk Category");
        assertTrue(rules.contains("Income Risk Score <=5 → \"High\""), () -> rules);
        assertTrue(rules.contains("Income Risk Score (5..70] → \"Medium\""), () -> rules);
    }

    @Test
    @DisplayName("a change that makes two rules overlap is caught and refused")
    void overlapIsCaught() throws Exception {
        // <=10 → <=20 leaves the next rule reading (10..70], so a score of 15 matches both.
        // Under UNIQUE that is a table with no answer. The first three gates all pass it,
        // because nothing is structurally wrong — it simply means something else.
        String before = loanModel();
        DecisionEditor.EditResult result =
                editor.setCell(before, "Income Risk Category", 3, "Income Risk Score", "<=10", "<=20");

        List<GateResult> results = gates.run(before, result.xml());

        assertFalse(DecisionGates.allPassed(results), "an overlapping table must not be offered");
        GateResult overlap = results.stream().filter(g -> g.id().equals("coverage")).findFirst().orElseThrow();
        assertTrue(overlap.detail().contains("Income Risk Category"), overlap::detail);
        assertTrue(overlap.detail().contains("both apply"), overlap::detail);
        assertTrue(results.stream().filter(g -> !g.id().equals("coverage")).allMatch(GateResult::ok));
    }

    @Test
    @DisplayName("overlaps the file already had do not block unrelated edits")
    void preexistingOverlapsAreNotOurProblem() throws Exception {
        // This model ships with twelve overlapping-rule errors in "Loan Recommendation". An
        // absolute check would refuse every edit to it, and a gate nobody can satisfy is a
        // gate that gets turned off.
        String before = loanModel();
        assertTrue(gates.coverage(before, before).ok(), "a file compared with itself introduces nothing");

        DecisionEditor.EditResult unrelated =
                editor.setCell(before, "Income Risk Score", 1, DecisionEditor.OUTCOME, "-100", "-50");
        assertStillValid(before, unrelated.xml(), "an edit elsewhere in the file");
    }

    @Test
    @DisplayName("the wrong rule number changes nothing, because the cell disagrees")
    void aMiscountIsCaught() throws Exception {
        // Rule 2 holds (10..70], not <=10. This is the check that replaced asking whether
        // the user's sentence happened to mention the value.
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> editor.setCell(loanModel(), "Income Risk Category", 2, "Income Risk Score", "<=10", "<=5"));

        assertTrue(thrown.getMessage().contains("(10..70]"), "it says what is actually there");
        assertTrue(thrown.getMessage().contains("Nothing was changed."));
    }

    @Test
    @DisplayName("RENAME_DECISION: everything that refers to the decision by name moves too")
    void renameDecision() throws Exception {
        // "Income Risk Category" is read by name inside the "Credit Risk Category" table.
        // Renaming only the decision leaves that reference dangling and Kogito refuses the
        // model — which is how this was found, on a file with more than one decision in it.
        String before = loanModel();
        DecisionEditor.EditResult result = editor.renameDecision(before, "Income Risk Category", "Income Band");

        assertTrue(result.summary().startsWith("Renamed \"Income Risk Category\" to \"Income Band\""));
        assertTrue(result.summary().contains("reference"), result::summary);
        assertTrue(DecisionProjection.of(result.xml()).decisionNames().contains("Income Band"));
        assertFalse(result.xml().contains(">Income Risk Category<"), "no reference left behind");
        assertStillValid(before, result.xml(), "renaming a decision");
    }

    @Test
    @DisplayName("renaming the final decision moves its own output label with it")
    void renameFinalDecision() throws Exception {
        String before = loanModel();
        DecisionEditor.EditResult result =
                editor.renameDecision(before, "Loan Recommendation", "Lending Decision");

        assertTrue(result.summary().contains("updated 1 reference"), result::summary);
        assertTrue(result.xml().contains("outputLabel=\"Lending Decision\""));
        assertStillValid(before, result.xml(), "renaming the final decision");
    }

    @Test
    @DisplayName("every refusal names what was not found, and nothing else")
    void refusals() throws Exception {
        String xml = loanModel();

        record Case(String what, org.junit.jupiter.api.function.Executable run, String mustSay) {}
        List<Case> cases = List.of(
                new Case("a value the cell does not hold",
                        () -> editor.setCell(xml, "Income Risk Category", 1, "Income Risk Score", ">999", ">1"), ">999"),
                new Case("a column that is not there",
                        () -> editor.setCell(xml, "Income Risk Category", 1, "Shoe Size", "9", "10"), "Shoe Size"),
                new Case("a rule past the end",
                        () -> editor.setCell(xml, "Income Risk Category", 40, "Income Risk Score", ">70", ">80"), "rule 40"),
                new Case("a decision that is not there",
                        () -> editor.setCell(xml, "Vibe Check", 1, "Income Risk Score", ">70", ">80"), "Vibe Check"),
                new Case("renaming a decision that is not there",
                        () -> editor.renameDecision(xml, "Vibe Check", "Something"), "Vibe Check"));

        for (Case c : cases) {
            IllegalArgumentException thrown =
                    assertThrows(IllegalArgumentException.class, c.run(), c.what());
            assertTrue(thrown.getMessage().contains(c.mustSay()),
                    () -> c.what() + ": message should name it — " + thrown.getMessage());
            assertFalse(thrown.getMessage().contains("null"),
                    () -> c.what() + ": leaked a null — " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("one intent writes one cell, and no more")
    void oneCellOnly() throws Exception {
        // A deliberate consequence of the collapse, recorded because it is a capability that
        // was lost: "change every Approve to Approve with conditions" used to rewrite six
        // rules from one intent. It now needs six, which is the multi-edit work.
        String before = loanModel();
        DecisionEditor.EditResult result =
                editor.setCell(before, "Income Risk Category", 1, DecisionEditor.OUTCOME, "\"Low\"", "Very low");

        assertEquals(1, result.cellsChanged());

        List<String> cellsBefore = allCells(before);
        List<String> cellsAfter = allCells(result.xml());
        assertEquals(cellsBefore.size(), cellsAfter.size(), "no cell added or removed");
        long differing = java.util.stream.IntStream.range(0, cellsBefore.size())
                .filter(i -> !cellsBefore.get(i).equals(cellsAfter.get(i)))
                .count();
        assertEquals(1, differing, "exactly one cell moved");
    }

    /**
     * Every cell value in document order.
     *
     * <p>Comparing the serialised text line by line measures the DOM writer's formatting
     * rather than the edit: the serialiser reflows the whole document, so a one-cell change
     * produces a whole-file textual diff. What matters is that exactly one *value* moved.
     */
    private static List<String> allCells(String xml) throws Exception {
        BpmnDocument doc = BpmnDocument.parse(xml);
        List<String> cells = new java.util.ArrayList<>();
        for (org.w3c.dom.Element table : doc.elements("decisionTable")) {
            for (org.w3c.dom.Element rule : BpmnDocument.childElements(table)) {
                if (!"rule".equals(rule.getLocalName())) {
                    continue;
                }
                for (org.w3c.dom.Element entry : BpmnDocument.childElements(rule)) {
                    BpmnDocument.childElements(entry).stream()
                            .filter(e -> "text".equals(e.getLocalName()))
                            .findFirst()
                            .ifPresent(text -> cells.add(text.getTextContent().trim()));
                }
            }
        }
        return cells;
    }

    @Test
    @DisplayName("the vocabulary is scoped per column, not pooled across the file")
    void vocabularyIsScoped() throws Exception {
        DecisionProjection.Vocabulary vocabulary = DecisionProjection.of(loanModel()).vocabulary();

        List<String> scores = vocabulary.conditionsFor("Income Risk Score");
        assertEquals(List.of(">70", "(10..70]", "<=10"), scores,
                "the column holds three values, and quoting fifty at the user is not an answer");

        assertTrue(vocabulary.conditionsFor("Borrower.EmploymentStatus").contains("\"Employed\""));
        assertFalse(scores.contains("\"Employed\""),
                "a value from another decision is not a legal setting for this column");
        assertTrue(vocabulary.conditionsFor("No Such Column").isEmpty());
    }

    private static String renderedRulesOf(String xml, String decisionName) {
        return DecisionProjection.of(xml).decisions().stream()
                .filter(d -> d.name().equals(decisionName))
                .findFirst()
                .map(d -> String.join("\n", d.rules()))
                .orElse("");
    }

    @Test
    @DisplayName("a change that leaves a hole is caught too")
    void gapIsCaught() throws Exception {
        // What the model actually produced when asked to make High cover <=20: it moved the
        // *neighbouring* rule instead, to (20..70]. That is half of the correct pair of
        // edits, and on its own it leaves 10 < score <= 20 matching no rule at all. The
        // analyser reports a gap at WARN, so filtering on ERROR alone checked the overlap
        // and missed the hole beside it.
        String before = loanModel();
        DecisionEditor.EditResult result =
                editor.setCell(before, "Income Risk Category", 2, "Income Risk Score", "(10..70]", "(20..70]");

        List<GateResult> results = gates.run(before, result.xml());

        assertFalse(DecisionGates.allPassed(results), "a table with a hole must not be offered");
        GateResult coverage = results.stream()
                .filter(g -> g.id().equals("coverage")).findFirst().orElseThrow();
        assertTrue(coverage.detail().contains("no answer at all"), coverage::detail);
        assertTrue(coverage.detail().contains("10"), () -> "it names the range: " + coverage.detail());
    }
}
