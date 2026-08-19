package com.processdesk.harness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The tools, tested against the model that produced the failures they exist to fix.
 *
 * <p>Every case here is a request that failed under the one-shot protocol. The point is not
 * that the tools work in general — it is that these specific requests can now succeed, and
 * that the safety properties of the one-shot path survived the change.
 */
class DecisionWorkspaceTest {

    private final DecisionEditor editor = new DecisionEditor();
    private final DecisionGates gates = new DecisionGates();

    private String loanModel() throws Exception {
        Path path = Path.of("assets/loan-recommendation.dmn");
        assertTrue(Files.exists(path), () -> "missing asset: " + path.toAbsolutePath());
        return Files.readString(path);
    }

    private DecisionWorkspace workspace() throws Exception {
        return new DecisionWorkspace(loanModel(), editor, gates);
    }

    @Test
    @DisplayName("the listing says which decisions are formulas rather than tables")
    void formulasAreVisibleAsFormulas() throws Exception {
        String listing = workspace().listDecisions();

        // The DTI failure in one line. Both exist, and the listing says which is which — the
        // projection rendered the formula as a name with nothing under it, indistinguishable
        // from a table with no rules, and the model picked it and correctly reported that it
        // had no rules to change.
        assertTrue(listing.contains("\"DTI\" — a formula"), listing);
        assertTrue(listing.contains("\"Affordability Category\""), listing);
        assertTrue(listing.contains("looks at: DTI"),
                "the listing has to show that a different decision tests DTI as a column");
    }

    @Test
    @DisplayName("asking to see a formula says what it is instead of showing nothing")
    void aFormulaExplainsItself() throws Exception {
        String shown = workspace().showDecision("DTI");

        assertTrue(shown.contains("is a formula"), shown);
        assertTrue(shown.contains("Borrower.TotalAnnualIncome"),
                "the FEEL body is business English and is the whole explanation");
        assertTrue(shown.contains("appear as a column"),
                "and it has to point at where the editable version lives");
    }

    @Test
    @DisplayName("a decision is shown with its rules numbered as the coordinates an edit uses")
    void rulesAreNumbered() throws Exception {
        String shown = workspace().showDecision("Affordability Category");

        assertTrue(shown.contains("Columns: DTI"), shown);
        // "DTI: >0.36" — the colon is load-bearing. The cell holds ">0.36" alone, and without
        // a visible break gpt-oss:20b copied "DTI >0.36" into expect and could not recover.
        assertTrue(shown.contains("1. DTI: >0.36 → \"Not affordable\""), shown);
        assertTrue(shown.contains("3. DTI: <0.33 → \"Affordable\""), shown);
    }

    @Test
    @DisplayName("a cell value that arrives with its column name still finds the cell")
    void theColumnPrefixIsTolerated() throws Exception {
        DecisionWorkspace workspace = workspace();

        // Exactly what the model sent: the whole rendered rule fragment, not the cell.
        String result = workspace.setCell("Affordability Category", 3, "DTI", "DTI <0.33", "<0.15");

        assertTrue(result.startsWith("Changed DTI in rule 3"), result);
        assertTrue(workspace.workingXml().contains("text><0.15</")
                || workspace.workingXml().contains("text>&lt;0.15</"), "the value is written bare");
    }

    @Test
    @DisplayName("a prefix that is not the column name is left alone")
    void onlyTheRealPrefixIsStripped() throws Exception {
        DecisionWorkspace workspace = workspace();

        // "DTIX" is not the column, so nothing may be stripped and the cell must not match.
        String result = workspace.setCell("Affordability Category", 3, "DTI", "DTIX <0.33", "<0.15");

        assertTrue(result.contains("Nothing was changed"), result);
        assertFalse(workspace.changed());
    }

    @Test
    @DisplayName("an unknown decision names the ones that exist")
    void anUnknownDecisionIsRecoverable() throws Exception {
        String shown = workspace().showDecision("Debt To Income");

        assertTrue(shown.startsWith("There is no decision called \"Debt To Income\"."), shown);
        assertTrue(shown.contains("Affordability Category"),
                "a miss has to leave the model able to pick again");
    }

    @Test
    @DisplayName("two edits in one request, which one intent could never express")
    void editsAccumulate() throws Exception {
        DecisionWorkspace workspace = workspace();

        // "Please change the DTI for affordable to be < 0.15 and Marginal to be from 0.15 to
        // 0.36" — the request that started this. Two cells, so two calls.
        workspace.setCell("Affordability Category", 3, "DTI", "<0.33", "<0.15");
        workspace.setCell("Affordability Category", 2, "DTI", "[0.33..0.36]", "[0.15..0.36]");

        assertEquals(2, workspace.edits().size());
        assertTrue(workspace.check().startsWith("All checks pass."), workspace.check());
    }

    @Test
    @DisplayName("check reports the gap a half-finished pair of edits leaves behind")
    void checkCatchesAnIncompleteEdit() throws Exception {
        DecisionWorkspace workspace = workspace();

        // Only the first half: Affordable moves down and nothing takes the range it vacated.
        workspace.setCell("Affordability Category", 3, "DTI", "<0.33", "<0.15");
        String checked = workspace.check();

        assertFalse(checked.startsWith("All checks pass."), checked);
        assertTrue(checked.contains("no answer at all") || checked.contains("both apply"), checked);
        // The point of saying it while the model can still act: it is told to keep going.
        assertTrue(checked.contains("Fix this with further changes"), checked);
    }

    @Test
    @DisplayName("check reports nothing when nothing has been changed")
    void nothingChangedIsNotAFailure() throws Exception {
        // The lending model has twelve overlap errors before anyone touches it. A gate that
        // judged rather than compared would refuse every edit to it.
        assertEquals("Nothing has been changed yet.", workspace().check());
    }

    @Test
    @DisplayName("a wrong coordinate is refused in words the model can correct from")
    void expectStillGuards() throws Exception {
        DecisionWorkspace workspace = workspace();

        String result = workspace.setCell("Affordability Category", 1, "DTI", "<0.33", "<0.15");

        assertTrue(result.contains("not <0.33"), result);
        assertTrue(result.contains("Nothing was changed"), result);
        assertFalse(workspace.changed(), "a refused edit must not reach the working copy");
    }

    @Test
    @DisplayName("editing a formula is refused rather than half-done")
    void aFormulaCannotBeEdited() throws Exception {
        DecisionWorkspace workspace = workspace();

        String result = workspace.setCell("DTI", 1, "outcome", "anything", "0.15");

        assertTrue(result.contains("isn't a table of rules"), result);
        assertFalse(workspace.changed());
    }

    @Test
    @DisplayName("a new rule can be added, which no phrasing could achieve before")
    void aRuleCanBeAdded() throws Exception {
        DecisionWorkspace workspace = workspace();

        // "Add a new criteria for DTI between 0.36 and 0.5 called Poor" — the request that had
        // no operation behind it, so every wording of it failed.
        workspace.setCell("Affordability Category", 1, "DTI", ">0.36", ">0.5");
        // "(0.36..0.5]", not "[0.36..0.5]": the rule beside it ends at 0.36 inclusive, so a
        // closed lower bound overlaps it at exactly that value. Written wrong here first, and
        // the coverage gate caught it — the third time on this project that a boundary I
        // expected to pass did not.
        String added = workspace.addRule("Affordability Category",
                java.util.List.of("(0.36..0.5]"), java.util.List.of("Poor"));

        assertTrue(added.startsWith("Added a rule to \"Affordability Category\""), added);
        assertTrue(added.contains("DTI (0.36..0.5] gives Poor"), added);
        assertTrue(workspace.check().startsWith("All checks pass."), workspace.check());
        assertTrue(workspace.showDecision("Affordability Category").contains("4. DTI: (0.36..0.5]"),
                "the new rule has to appear, numbered, for a later edit to address it");
    }

    @Test
    @DisplayName("a new rule takes the quoting the column already uses")
    void quotingIsInferredFromTheColumn() throws Exception {
        DecisionWorkspace workspace = workspace();

        workspace.setCell("Affordability Category", 1, "DTI", ">0.36", ">0.5");
        workspace.addRule("Affordability Category",
                java.util.List.of("(0.36..0.5]"), java.util.List.of("Poor"));

        // FEEL cares: "Poor" is a string, Poor is a variable that does not exist. The outcome
        // column is quoted, the condition column is not, and the new rule has to match both.
        // Matched without the element's prefix, because this file writes "semantic:text" and
        // another exporter will write something else.
        assertTrue(workspace.workingXml().contains("text>\"Poor\"</"),
                "an outcome beside quoted outcomes has to be quoted");
        assertTrue(workspace.workingXml().contains("text>(0.36..0.5]</"),
                "a range beside unquoted ranges must not be");
        // The proof that it matters: an unquoted string would not compile.
        assertTrue(DecisionGates.allPassed(workspace.gates()), () -> workspace.check());
    }

    @Test
    @DisplayName("a rule with the wrong number of conditions is refused, not padded")
    void theShapeIsEnforced() throws Exception {
        DecisionWorkspace workspace = workspace();

        String result = workspace.addRule("Loan Recommendation",
                java.util.List.of("\"Low\""), java.util.List.of("\"Approve\""));

        assertTrue(result.contains("looks at 3 things"), result);
        assertTrue(result.contains("I was given 1"), result);
        assertFalse(workspace.changed(), "a misshapen rule must not reach the working copy");
    }

    @Test
    @DisplayName("a rule that overlaps an existing one is added, then caught by check")
    void anOverlappingRuleIsCaught() throws Exception {
        DecisionWorkspace workspace = workspace();

        // Deliberately careless: [0.2..0.3] sits inside the existing <0.33 "Affordable" rule.
        workspace.addRule("Affordability Category",
                java.util.List.of("[0.2..0.3]"), java.util.List.of("Poor"));

        String checked = workspace.check();
        assertTrue(checked.contains("both apply"), checked);
        assertTrue(checked.contains("Fix this with further changes"), checked);
    }

    @Test
    @DisplayName("a rule cannot be added to a formula")
    void aFormulaTakesNoRules() throws Exception {
        DecisionWorkspace workspace = workspace();

        String result = workspace.addRule("DTI", java.util.List.of("x"), java.util.List.of("y"));

        assertTrue(result.contains("isn't a table of rules"), result);
        assertFalse(workspace.changed());
    }

    @Test
    @DisplayName("a decision can be renamed, references and all")
    void aDecisionCanBeRenamed() throws Exception {
        // Missing from the loop until the benchmark asked for it: the editor could do this and
        // the one-shot path could reach it, but no tool exposed it, so the assistant had lost a
        // capability it used to have and said so politely.
        DecisionWorkspace workspace = workspace();

        String result = workspace.renameDecision("Reserves Months", "Cash Reserves");

        assertTrue(result.startsWith("Renamed \"Reserves Months\" to \"Cash Reserves\""), result);
        // A decision name is a FEEL variable other decisions read; gate 1 fails if any are left.
        assertTrue(DecisionGates.allPassed(workspace.gates()), () -> workspace.check());
        assertTrue(workspace.listDecisions().contains("Cash Reserves"), workspace.listDecisions());
    }

    @Test
    @DisplayName("renaming something that isn't there says so")
    void renamingAnUnknownDecision() throws Exception {
        DecisionWorkspace workspace = workspace();

        String result = workspace.renameDecision("Reserve Months", "Cash Reserves");

        assertTrue(result.contains("no decision called"), result);
        assertFalse(workspace.changed());
    }

    private static final java.util.List<String> NONE = java.util.List.of();

    @Test
    @DisplayName("one formula converts a whole column in a single call")
    void calculateSubstitutes() throws Exception {
        String result = workspace().calculate("(c * 9 / 5) + 32", "c",
                java.util.List.of("20", "18", "8"), NONE);

        assertEquals("Using (c * 9 / 5) + 32: 20 → 68, 18 → 64.4, 8 → 46.4", result);
    }

    @Test
    @DisplayName("several names with one value each")
    void calculateWithNamedValues() throws Exception {
        // The debt-to-income formula from the lending model, which is about three quantities.
        String result = workspace().calculate("(payment + debts) / (income / 12)", "", NONE,
                java.util.List.of("payment=1200", "debts=400", "income=96000"));

        assertEquals("(payment + debts) / (income / 12) = 0.2", result);
    }

    @Test
    @DisplayName("named values hold while another name runs over a column")
    void calculateCombinesBoth() throws Exception {
        // The constant applies to every value, so a rescale keeps its factor in one place.
        String result = workspace().calculate("x * rate", "x", java.util.List.of("100", "250"),
                java.util.List.of("rate=1.075"));

        assertEquals("Using x * rate: 100 → 107.5, 250 → 268.75", result);
    }

    @Test
    @DisplayName("with no variable it just works the expression out")
    void calculateEvaluates() throws Exception {
        assertEquals("0.36 * 1.1 = 0.396",
                workspace().calculate("0.36 * 1.1", "", NONE, NONE));
    }

    @Test
    @DisplayName("a value that is not a number is named, not silently dropped")
    void calculateNamesBadInputs() throws Exception {
        String result = workspace().calculate("c * 2", "c", java.util.List.of("20", "warm"), NONE);

        assertTrue(result.contains("20 → 40"), result);
        assertTrue(result.contains("\"warm\" isn't a number"), result);
    }

    @Test
    @DisplayName("a name the formula uses but was given no value says which names it did get")
    void calculateNamesWhatIsMissing() throws Exception {
        String result = workspace().calculate("(payment + debts) / income", "", NONE,
                java.util.List.of("payment=1200", "income=96000"));

        assertTrue(result.contains("\"debts\""), result);
        assertTrue(result.contains("payment, income") || result.contains("income, payment"), result);
    }

    @Test
    @DisplayName("a binding that is not name=number is refused with the shape it wanted")
    void calculateRejectsMalformedBindings() throws Exception {
        String result = workspace().calculate("rate * 2", "", NONE, java.util.List.of("rate 0.3"));

        assertTrue(result.contains("name=number"), result);
    }

    @Test
    @DisplayName("a formula it cannot read comes back as something it can act on")
    void calculateExplainsItself() throws Exception {
        String result = workspace().calculate("sqrt(c)", "c", java.util.List.of("9"), NONE);

        assertTrue(result.contains("round, abs, min and max"), result);
    }

    @Test
    @DisplayName("the original is never mutated, so a refusal costs nothing")
    void theOriginalSurvives() throws Exception {
        String before = loanModel();
        DecisionWorkspace workspace = new DecisionWorkspace(before, editor, gates);

        workspace.setCell("Affordability Category", 3, "DTI", "<0.33", "<0.15");

        assertNotEquals(before, workspace.workingXml());
        assertEquals(before, loanModel(), "the file on disk is not the working copy");
    }
}
