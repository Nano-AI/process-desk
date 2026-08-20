package com.processdesk.ai;

import com.processdesk.harness.DecisionEditor;
import com.processdesk.harness.DecisionGates;
import com.processdesk.harness.DecisionWorkspace;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a conversation costs to read, held to a budget.
 *
 * <p>Prefill is what this loop spends. On a machine without a GPU a 9B dense reads at roughly
 * 15 to 25 tokens a second, so a couple of thousand tokens of context is a minute before the
 * model has emitted anything. Every number here was cut deliberately at least once, and nothing
 * stops the next person restoring a helpful sentence to a tool description that is prefilled on
 * every turn of every conversation. This is what stops it.
 *
 * <p>The budgets are the measured size plus roughly a quarter. They are not targets to grow into.
 * A failure here is a question — what did this buy, and could a gate have done it instead? — not
 * an instruction to raise the number.
 *
 * <p>Tokens are estimated at four characters each, which is wrong in detail and stable in
 * aggregate. The alternative is a tokeniser dependency to measure something that only has to be
 * accurate enough to catch a regression.
 */
class ContextBudgetTest {

    private static final List<String> REPORT = new ArrayList<>();

    private static int tokens(String text) {
        return text == null ? 0 : text.length() / 4;
    }

    /** The serialised size of a set of tool schemas, as the provider will send them. */
    private static int schemaTokens(Set<String> names) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            int total = 0;
            for (Tools.Spec spec : DecisionToolLoop.SPECS) {
                if (names.contains(spec.name())) {
                    total += tokens(mapper.writeValueAsString(spec.name())
                            + mapper.writeValueAsString(spec.description())
                            + mapper.writeValueAsString(spec.parameters()));
                }
            }
            return total;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void record(String what, int actual, int budget) {
        REPORT.add(String.format("%-4s %-38s %5d  budget %5d", actual <= budget ? "ok" : "OVER",
                what, actual, budget));
    }

    private static void within(String what, int actual, int budget) {
        record(what, actual, budget);
        assertTrue(actual <= budget, () -> what + " is " + actual + " tokens, over its budget of "
                + budget + ". Before raising it: what does the extra buy, and could a gate or a "
                + "tool result have done the same job without being prefilled every turn?");
    }

    private DecisionWorkspace workspace() throws Exception {
        return new DecisionWorkspace(Files.readString(Path.of("assets/loan-recommendation.dmn")),
                new DecisionEditor(), new DecisionGates());
    }

    @Test
    @DisplayName("the preamble, paid on every turn of every conversation")
    void thePreambleStaysSmall() {
        int prompt = tokens(Prompts.DECISION_TOOLS);
        int route = tokens(Prompts.ROUTE);
        int question = schemaTokens(Set.of("list_decisions", "show_decision", "answer", "panic"));
        int edit = schemaTokens(Set.of("list_decisions", "show_decision", "set_cell", "add_rule",
                "done", "answer", "panic"));

        REPORT.add("PREAMBLE");
        // Was 1,105. Everything that could be a gate became one; what is left is the paragraph
        // with no mechanism behind it.
        within("DECISION_TOOLS", prompt, 160);
        within("ROUTE", route, 80);
        within("QUESTION preamble", prompt + question, 500);
        within("EDIT preamble", prompt + edit, 800);
    }

    @Test
    @DisplayName("what each tool hands back")
    void toolResultsStaySmall() throws Exception {
        DecisionWorkspace workspace = workspace();
        REPORT.add("");
        REPORT.add("TOOL RESULTS");

        within("list_decisions (11 decisions)", tokens(workspace.listDecisions()), 200);
        within("show_decision (3 rules)",
                tokens(workspace.showDecision("Affordability Category")), 130);
        within("show_decision (15 rules)",
                tokens(workspace.showDecision("Income Risk Score")), 620);

        // A write that leaves a gap has to come back with the whole table: the fix is almost
        // always the rule beside the one that moved, and the model cannot move what it cannot
        // see. This one is allowed to be large.
        String failed = workspace.setCell("Affordability Category", 3, "DTI", "<0.33", "<0.15");
        assertTrue(failed.contains("The table now reads"), failed);
        within("set_cell, gap left behind", tokens(failed), 200);

        // A write that passes must not. It changes one value, renumbers nothing, and the
        // summary already says which cell now holds what — so re-rendering the table repeats
        // what the model was just told. On the fifteen-rule table that repetition was 522
        // tokens.
        String passed = workspace.setCell("Affordability Category", 2, "DTI",
                "[0.33..0.36]", "[0.15..0.36]");
        assertFalse(passed.contains("The table now reads"),
                "a passing write must not re-render the table: " + passed);
        within("set_cell, checks pass", tokens(passed), 45);
    }

    @Test
    @DisplayName("a whole edit conversation, at its widest")
    void aWholeConversationStaysSmall() throws Exception {
        DecisionWorkspace workspace = workspace();
        int total = tokens(Prompts.DECISION_TOOLS)
                + schemaTokens(Set.of("list_decisions", "show_decision", "set_cell", "add_rule",
                        "done", "answer", "panic"))
                + tokens(workspace.listDecisions())
                + tokens(workspace.showDecision("Affordability Category"))
                + tokens(workspace.setCell("Affordability Category", 3, "DTI", "<0.33", "<0.15"))
                + tokens(workspace.setCell("Affordability Category", 2, "DTI",
                        "[0.33..0.36]", "[0.15..0.36]"));

        REPORT.add("");
        REPORT.add("CONVERSATION");
        // The two-part DTI request, end to end, as the model sees it on its final turn. For
        // scale: measured the same way, the preamble alone used to be about 2,100 tokens, so a
        // whole conversation now costs about half of what the instructions once did.
        within("five-turn edit, last-turn context", total, 1400);
    }

    @AfterAll
    static void printTheReport() {
        System.out.println("\n" + String.join("\n", REPORT) + "\n");
    }
}
