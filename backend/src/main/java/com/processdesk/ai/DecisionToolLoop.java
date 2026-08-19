package com.processdesk.ai;

import com.processdesk.harness.DecisionEditor;
import com.processdesk.harness.DecisionGates;
import com.processdesk.harness.DecisionWorkspace;
import com.processdesk.harness.GateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the model look at a decision model, change it, check its work, and fix what it broke.
 *
 * <p>This replaces the one-shot protocol, not the harness. The deterministic editor and the four
 * Kogito gates are unchanged and still have the last word; what changes is that the model now
 * gets more than one guess, and gets to see the file rather than a projection somebody chose for
 * it in advance. Three failures that had three different-looking causes turned out to be the same
 * cause — one turn, one intent — and all three dissolve here:
 *
 * <ul>
 *   <li>"the DTI" is both a decision and a column; {@code list_decisions} shows both, and shows
 *       which of them is a formula with no rules to change.
 *   <li>"Affordable under 0.15 and Marginal from 0.15 to 0.36" is two edits; the working copy
 *       accumulates, so it is two {@code set_cell} calls.
 *   <li>moving one boundary of a range table leaves a gap beside it; {@code check} reports the
 *       gap while the model can still move the neighbour.
 * </ul>
 *
 * <p>The loop is bounded. A model that never calls {@code done} is a real failure mode of small
 * local models, and an unbounded conversation would spend a quota or a laptop rather than
 * admitting it is stuck.
 */
@Component
public class DecisionToolLoop {

    private static final Logger log = LoggerFactory.getLogger(DecisionToolLoop.class);

    private final AiProvider ai;
    private final DecisionEditor editor;
    private final DecisionGates gates;
    private final int maxTurns;

    public DecisionToolLoop(AiProvider ai, DecisionEditor editor, DecisionGates gates,
                            @Value("${processdesk.tool-loop.max-turns:8}") int maxTurns) {
        this.ai = ai;
        this.editor = editor;
        this.gates = gates;
        this.maxTurns = maxTurns;
    }

    /**
     * What the loop concluded.
     *
     * <p>Either an answer to a question, or a proposed change with the gates that judged it —
     * the same two outcomes the one-shot path had, so the controller and the UI are unaffected.
     */
    public record Outcome(String answer, String proposedXml, List<String> edits,
                          List<GateResult> gates, boolean ok, String message, int turns,
                          List<String> toolCalls, boolean hitCap, boolean unreachable) {

        static Outcome answered(String text, int turns, List<String> toolCalls) {
            return new Outcome(text, null, List.of(), List.of(), true, null, turns, toolCalls,
                    false, false);
        }

        static Outcome declined(String message, int turns, List<String> toolCalls, boolean hitCap) {
            return new Outcome(null, null, List.of(), List.of(), false, message, turns, toolCalls,
                    hitCap, false);
        }

        /**
         * The model could not be reached at all.
         *
         * <p>Distinct from a refusal, and the benchmark is why. Scoring a request the model was
         * never asked is not scoring the model: a timeout produces no proposal, which is exactly
         * what a correct refusal produces, so two crashed requests were counted as the assistant
         * rightly declining to do the impossible.
         */
        static Outcome unreachable(String message, int turns, List<String> toolCalls) {
            return new Outcome(null, null, List.of(), List.of(), false, message, turns, toolCalls,
                    false, true);
        }

        public boolean isAnswer() {
            return answer != null;
        }

        /** Tool names the model asked for that do not exist — the clearest sign it is lost. */
        public long unknownToolCalls() {
            return toolCalls.stream().filter(name -> !KNOWN.contains(name)).count();
        }
    }

    private static final java.util.Set<String> KNOWN = java.util.Set.of(
            "list_decisions", "show_decision", "set_cell", "add_rule", "rename_decision",
            "calculate", "check", "done", "answer");

    public boolean available() {
        return ai.supportsTools();
    }

    /**
     * One thing the assistant did, as it happens, for the panel to show.
     *
     * <p>Reported when the call starts rather than when it finishes, because the point is to
     * say what is being waited on. Nothing is announced in advance: the loop does not know its
     * own next move, and a list of steps that might not run is what made the old fixed trail
     * show rows marked "not reached".
     */
    public record Step(String label, String detail) {}

    public Outcome run(String request, String xml) {
        return run(request, xml, step -> { });
    }

    /** Says what each tool call is, in the words of someone who has not seen the tools. */
    private static Step describe(Tools.Call call) {
        String decision = call.text("decision");
        return switch (call.name()) {
            case "list_decisions" -> new Step("Looking at what this file decides", null);
            case "show_decision" -> new Step("Reading the rules for \"" + decision + "\"", null);
            case "set_cell" -> new Step("Changing rule " + call.number("rule")
                    + " of \"" + decision + "\"", call.text("to"));
            case "add_rule" -> new Step("Adding a rule to \"" + decision + "\"", null);
            case "rename_decision" -> new Step("Renaming \"" + call.text("from") + "\"",
                    call.text("to"));
            case "calculate" -> new Step("Working out " + call.text("formula"), null);
            case "check" -> new Step("Checking nothing else broke", null);
            default -> new Step("Working on it", null);
        };
    }

    public Outcome run(String request, String xml, java.util.function.Consumer<Step> onStep) {
        DecisionWorkspace workspace = new DecisionWorkspace(xml, editor, gates);
        List<Tools.Message> conversation = new ArrayList<>();
        conversation.add(Tools.Message.user(request));

        List<String> called = new ArrayList<>();
        // Every distinct call and what it returned. A model that repeats a call verbatim is
        // stuck, not thinking — gpt-oss:20b sent the same rejected set_cell four times over
        // 292 seconds — and running it again cannot produce a different answer, because none
        // of these tools depend on anything but the working copy the last call did not change.
        java.util.Map<String, String> repeated = new java.util.LinkedHashMap<>();
        String closing = null;
        int turn = 0;

        while (turn < maxTurns) {
            turn++;
            Tools.Turn reply;
            try {
                reply = ai.nextTurn(Prompts.DECISION_TOOLS, conversation, SPECS);
            } catch (Exception e) {
                log.error("tool loop turn {} failed", turn, e);
                return Outcome.unreachable("I couldn't reach the model. Try again in a moment.",
                        turn, called);
            }

            if (!reply.hasCalls()) {
                // No call and some prose is the model answering in the only way it has left.
                // Treating that as an answer is better than pressing it for a tool call it has
                // already decided not to make — small models do this constantly, and looping
                // on it burns turns to reach the same place.
                String text = reply.text() == null ? "" : reply.text().trim();
                return finish(workspace, text.isEmpty() ? null : text, turn, called, false);
            }

            conversation.add(Tools.Message.assistant(reply.text(), reply.calls()));

            for (Tools.Call call : reply.calls()) {
                called.add(call.name());
                if ("done".equals(call.name()) || "answer".equals(call.name())) {
                    closing = call.text("done".equals(call.name()) ? "summary" : "text");
                    break;
                }
                onStep.accept(describe(call));
                String result = repeated.containsKey(signature(call))
                        ? alreadyTried(repeated.get(signature(call)))
                        : dispatch(workspace, call);
                repeated.putIfAbsent(signature(call), result);
                // At debug because it is the whole conversation and it is long; on when you
                // are asking why a loop took the turns it did, which is most of the time.
                log.debug("turn {} · {}({}) → {}", turn, call.name(), call.arguments(),
                        result.replace('\n', ' '));
                conversation.add(Tools.Message.tool(call.name(), result));
            }

            if (closing != null) {
                return finish(workspace, closing.isBlank() ? null : closing, turn, called, false);
            }
        }

        // Out of turns. If the model got as far as a change that passes, offering it is better
        // than throwing the work away; if it did not, say so plainly rather than inventing a
        // reason. A silent cap is the worst outcome of a loop and the one worth naming.
        log.info("tool loop hit its {}-turn cap", maxTurns);
        return workspace.changed()
                ? finish(workspace, null, turn, called, true)
                : Outcome.declined("I couldn't work out how to make that change.", turn, called, true);
    }

    /** Runs the gates one last time and turns the workspace into something the UI can show. */
    private Outcome finish(DecisionWorkspace workspace, String closing, int turns,
                           List<String> called, boolean hitCap) {
        if (!workspace.changed()) {
            return Outcome.answered(closing == null
                    ? "I couldn't work out which rule you meant." : closing, turns, called);
        }

        List<GateResult> checked = workspace.gates();
        boolean ok = DecisionGates.allPassed(checked);
        String summary = closing != null && !closing.isBlank()
                ? closing : String.join(" ", workspace.edits());

        return new Outcome(null, ok ? workspace.workingXml() : null, workspace.edits(), checked, ok,
                ok ? summary : whyNot(checked), turns, called, hitCap, false);
    }

    private static String whyNot(List<GateResult> gates) {
        return gates.stream().filter(gate -> !gate.ok()).map(GateResult::detail)
                .filter(detail -> detail != null && !detail.isBlank())
                .findFirst()
                .orElse("That change wouldn't be safe to make, so I haven't offered it.");
    }

    /** Identity of a call: same tool, same arguments, therefore same answer. */
    private static String signature(Tools.Call call) {
        return call.name() + "|" + call.arguments();
    }

    /**
     * Says the call has already been made, and pushes towards a different move.
     *
     * <p>Repeating the original result matters: without it the model has lost the reason its
     * approach failed and has nothing to reason from except the fact that it failed.
     */
    private static String alreadyTried(String previous) {
        return "You already tried exactly this and it did not work: " + previous
                + "\nDo not repeat it. Look at the decision again, change the values you are "
                + "sending, use a different tool, or finish and explain what you cannot do.";
    }

    private String dispatch(DecisionWorkspace workspace, Tools.Call call) {
        try {
            return switch (call.name()) {
                case "list_decisions" -> workspace.listDecisions();
                case "show_decision" -> workspace.showDecision(call.text("decision"));
                case "set_cell" -> workspace.setCell(call.text("decision"), call.number("rule"),
                        call.text("column"), call.text("expect"), call.text("to"));
                case "add_rule" -> workspace.addRule(call.text("decision"),
                        call.strings("conditions"), call.strings("outcomes"));
                case "calculate" -> workspace.calculate(call.text("formula"),
                        call.text("variable"), call.strings("values"),
                        call.strings("variables"));
                case "rename_decision" -> workspace.renameDecision(
                        call.text("from"), call.text("to"));
                case "check" -> workspace.check();
                // A name outside the list is a hallucinated tool. Naming what exists is a
                // recoverable turn; a stack trace is not.
                default -> "There is no tool called " + call.name()
                        + ". The tools are: list_decisions, show_decision, set_cell, add_rule, "
                        + "rename_decision, calculate, check, done, answer.";
            };
        } catch (Exception e) {
            log.debug("tool {} failed", call.name(), e);
            return "That didn't work. Nothing was changed.";
        }
    }

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    /**
     * The tools, and the descriptions the model reads to choose between them.
     *
     * <p>Kept few and kept short on purpose. Every tool description is prefilled on every turn of
     * every conversation, and prefill — not generation — is what the loop actually costs: at the
     * measured 126 tokens/second, a needlessly generous set of schemas is paid for again each
     * time the model stops to think.
     */
    public static final List<Tools.Spec> SPECS = List.of(
            new Tools.Spec("list_decisions",
                    "Every decision in the file, with whether it is a table of rules or a formula. "
                            + "Start here when you are not certain which decision the request means.",
                    object(Map.of(), List.of())),

            new Tools.Spec("show_decision",
                    "The columns and numbered rules of one decision. Always look before changing "
                            + "a rule: the rule number and current value come from here.",
                    object(Map.of("decision", string("The decision's name, exactly as listed.")),
                            List.of("decision"))),

            new Tools.Spec("set_cell",
                    "Change one cell of one rule. Call it more than once when a request needs "
                            + "more than one change.",
                    object(new LinkedHashMap<>(Map.of(
                            "decision", string("The decision's name."),
                            "rule", Map.of("type", "integer",
                                    "description", "Which rule, numbered from 1 as shown."),
                            "column", string("A column heading, or \"outcome\" for the result."),
                            "expect", string("What that cell contains now — the value only, "
                                    + "not the column heading in front of it. In a rule shown "
                                    + "as \"DTI: >0.36\" the cell is \">0.36\". Copy it exactly, "
                                    + "including any quotes. Checked before anything is written."),
                            "to", string("What the cell should say instead. Write conditions in "
                                    + "the table's notation: \"20 or less\" is <=20, "
                                    + "\"between 18 and 35\" is [18..35]."))),
                            List.of("decision", "rule", "column", "expect", "to"))),

            new Tools.Spec("add_rule",
                    "Add a new rule to the end of a table. Give one condition per column, in the "
                            + "order show_decision listed them, and \"-\" for a column that does "
                            + "not matter for this rule.",
                    object(new LinkedHashMap<>(Map.of(
                            "decision", string("The decision's name."),
                            "conditions", Map.of("type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "One value per column, in column order."),
                            "outcomes", Map.of("type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "What the rule produces, usually one value."))),
                            List.of("decision", "conditions", "outcomes"))),

            new Tools.Spec("rename_decision",
                    "Rename a decision. Other decisions that read it by name are updated too.",
                    object(new LinkedHashMap<>(Map.of(
                            "from", string("The decision's current name, exactly as listed."),
                            "to", string("The new name."))),
                            List.of("from", "to"))),

            new Tools.Spec("calculate",
                    "Work out arithmetic. Give the formula, and a value for every name in "
                            + "it. Use \"variables\" for names with one value each "
                            + "(rate=0.3). To put many values through the same formula, name "
                            + "that one in \"variable\" and list them in \"values\" — "
                            + "converting a whole column is then one call. Use this whenever a "
                            + "new cell value needs arithmetic, rather than doing the sums "
                            + "yourself.",
                    object(new LinkedHashMap<>(Map.of(
                            "formula", string("The formula, e.g. (c * 9 / 5) + 32. Numbers, "
                                    + "+ - * / ( ), and round, abs, min, max."),
                            "variable", string("The name standing for the value, e.g. c. "
                                    + "Empty when the formula has no variable."),
                            "values", Map.of("type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "The values to put through the formula."),
                            "variables", Map.of("type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "Any other names the formula uses, each "
                                            + "written name=number, like rate=0.3."))),
                            List.of("formula"))),

            new Tools.Spec("check",
                    "Check the changes so far. Reports any case that now matches two rules or no "
                            + "rule. Call it after changing a boundary — moving one usually means "
                            + "moving the rule next to it.",
                    object(Map.of(), List.of())),

            new Tools.Spec("done",
                    "Finish, when the changes are made and check passes.",
                    object(Map.of("summary", string("What you changed, in one or two sentences, "
                                    + "for someone who does not read DMN.")),
                            List.of("summary"))),

            new Tools.Spec("answer",
                    "Finish by answering a question, when the request was about the rules rather "
                            + "than a change to them.",
                    object(Map.of("text", string("The answer, in plain language.")),
                            List.of("text"))));
}
