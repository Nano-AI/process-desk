package com.processdesk.ai;

import com.processdesk.harness.DecisionEditor;
import com.processdesk.harness.DecisionExamples;
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
    private final DecisionExamples examples;
    private final int maxTurns;

    public DecisionToolLoop(AiProvider ai, DecisionEditor editor, DecisionGates gates,
                            @Value("${processdesk.tool-loop.max-turns:8}") int maxTurns) {
        this(ai, editor, gates, null, maxTurns);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DecisionToolLoop(AiProvider ai, DecisionEditor editor, DecisionGates gates,
                            DecisionExamples examples,
                            @Value("${processdesk.tool-loop.max-turns:8}") int maxTurns) {
        this.ai = ai;
        this.editor = editor;
        this.gates = gates;
        this.examples = examples;
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
                          List<String> toolCalls, boolean hitCap, boolean unreachable,
                          String panicReason, String reading) {

        static Outcome answered(String text, int turns, List<String> toolCalls) {
            return new Outcome(text, null, List.of(), List.of(), true, null, turns, toolCalls,
                    false, false, null, null);
        }

        static Outcome declined(String message, int turns, List<String> toolCalls, boolean hitCap) {
            return new Outcome(null, null, List.of(), List.of(), false, message, turns, toolCalls,
                    hitCap, false, null, null);
        }

        /**
         * The model stopped and said what it could not do, naming which kind of thing it was.
         *
         * <p>Distinct from a cap hit, and that distinction is the point. Four of twenty requests
         * on {@code ornith:9b} ended at the turn cap having said nothing, which from outside is
         * indistinguishable from a correct refusal — the first benchmark run scored two of them
         * as the assistant rightly declining. A refusal that names its own reason can be counted,
         * and the counts are a roadmap: "refused 6" is a number, "refused 6, four of them
         * needing a column this table does not have" is the next piece of work.
         */
        static Outcome panicked(String message, String reason, int turns, List<String> toolCalls) {
            return new Outcome(null, null, List.of(), List.of(), false, message, turns, toolCalls,
                    false, false, reason, null);
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
                    false, true, null, null);
        }

        /** The same outcome, labelled with the reading it ran under, for the benchmark. */
        Outcome readAs(AiProvider.Reading reading) {
            return new Outcome(answer, proposedXml, edits, gates, ok, message, turns, toolCalls,
                    hitCap, unreachable, panicReason,
                    reading == null ? null : reading.name());
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
            "calculate", "check", "done", "answer", "panic");

    /**
     * What each panic reason means, in the words of someone who has never seen a decision table.
     *
     * <p>The model picks the code and this writes the sentence, which is the whole design.
     * {@code qwen3:14b} refused a request that was exactly what it is for, in wording lifted
     * verbatim from the prompt's own example of how to decline — it had learned the sentence
     * rather than the capability. A model that can only emit {@code NEEDS_NEW_COLUMN} has
     * nothing to recite, and the phrasing the user reads stops varying by model.
     */
    private static final Map<String, String> PANIC_REASONS = Map.ofEntries(
            Map.entry("NO_SUCH_DECISION",
                    "Nothing in this file is called that."),
            Map.entry("NOT_A_TABLE",
                    "That one is worked out by a formula, so it has no rules for me to change."),
            Map.entry("NEEDS_NEW_COLUMN",
                    "The table would have to weigh something it doesn't look at today, and I can "
                            + "only change the values in the columns it already has."),
            Map.entry("NEEDS_NEW_DECISION",
                    "That would need a decision this file doesn't have, and I can only work with "
                            + "the ones already in it."),
            Map.entry("NEEDS_RULE_REMOVAL",
                    "I can change a rule or add one, but I can't take one away."),
            Map.entry("AMBIGUOUS",
                    "Two things in this file could be what you meant, so I've left it alone "
                            + "rather than guess."),
            Map.entry("CHECK_KEEPS_FAILING",
                    "Every version I tried left cases the rules no longer cover, so I haven't "
                            + "offered it."),
            Map.entry("UNSURE",
                    "I couldn't work out how to do it safely, so nothing has been changed."));

    /** "Sorry, I can't X. Y." — the model supplies X in the user's words, this supplies Y. */
    private static String panicMessage(String what, String why) {
        String reason = PANIC_REASONS.getOrDefault(why, PANIC_REASONS.get("UNSURE"));
        String subject = what == null ? "" : what.trim().replaceAll("[.\\s]+$", "");
        return subject.isEmpty()
                ? "Sorry, I can't do that. " + reason
                : "Sorry, I can't " + Character.toLowerCase(subject.charAt(0))
                        + subject.substring(1) + ". " + reason;
    }

    public boolean available() {
        return ai.supportsTools();
    }

    /**
     * Requests that name a rename, in the languages this has been checked against.
     *
     * <p>A heuristic, and deliberately not exhaustive. Word lists do not generalise, so the
     * correctness of the narrow tool set does not rest on this one being right. It rests on the
     * retry in {@code run}: when a conversation that had tools withheld panics, it gets them all
     * and one more turn. A miss costs a turn, never a refusal.
     */
    private static final java.util.regex.Pattern RENAMES = java.util.regex.Pattern.compile(
            "\\b(renam\\w*|call it|calling it|change the name|new name"
                    + "|renombra\\w*|renomme\\w*|umbenenn\\w*|rinomina\\w*"
                    + "|переимен\\w*|renomea\\w*)\\b|重命名|改名|名前を",
            java.util.regex.Pattern.UNICODE_CASE);

    /** Requests with arithmetic in them, which are the only ones calculate can help. */
    private static final java.util.regex.Pattern ARITHMETIC = java.util.regex.Pattern.compile(
            "\\b(convert\\w*|conversion|times|multipl\\w*|divid\\w*|percent\\w*"
                    + "|double\\w*|halve\\w*|scal\\w*|rescal\\w*|round\\w*|rund\\w*"
                    + "|increase\\w*|decreas\\w*|celsius|fahrenheit|plus|minus|average"
                    + "|convertir|convertire|umrechn\\w*|prozent|porciento|pourcent\\w*"
                    + "|multiplica\\w*|dividir|redonde\\w*)\\b"
                    // An operator between two numbers. Anchored on both sides because the
                    // conformance suite caught "change the score for under 18s to -150" loading
                    // the arithmetic tool: a negative number is not a subtraction.
                    + "|\\d\\s*[-+*/]\\s*\\d|%",
            java.util.regex.Pattern.UNICODE_CASE);

    /**
     * The tools advertised for one request, chosen before the first turn.
     *
     * <p>Every schema is prefilled on every turn, and on a CPU-only machine that is seconds
     * rather than tokens — but the stronger argument is accuracy. Sixty benchmark requests
     * produced zero unknown tool calls, because Ollama enforces the schema at the decoder and
     * the model can only choose wrongly among the options it was given. Shrinking the option
     * set is therefore the whole lever, and the benchmark says where to shrink it: QUESTION
     * scored 12/12 across three models and every loss was in EDIT, so a question has no reason
     * to be carrying the write tools.
     *
     * <p>{@code rename_decision} and {@code calculate} load only when the request implies them.
     * Both are real capabilities that are rarely the answer, and a rarely-correct tool sitting
     * in the default set is a standing invitation to a wrong call.
     *
     * <p>{@code check} is in neither set. Every write runs it and returns the result, so
     * offering it buys a turn spent re-reading something the model was already told.
     */
    private static List<Tools.Spec> toolsFor(AiProvider.Reading reading, String request) {
        if (reading == null || reading == AiProvider.Reading.UNKNOWN) {
            return SPECS;
        }
        java.util.Set<String> names = new java.util.LinkedHashSet<>(
                List.of("list_decisions", "show_decision", "answer", "panic"));
        if (reading == AiProvider.Reading.EDIT) {
            names.addAll(List.of("set_cell", "add_rule", "done"));
            String text = request == null ? "" : request.toLowerCase(java.util.Locale.ROOT);
            if (RENAMES.matcher(text).find()) {
                names.add("rename_decision");
            }
            if (ARITHMETIC.matcher(text).find()) {
                names.add("calculate");
            }
        }
        return SPECS.stream().filter(spec -> names.contains(spec.name())).toList();
    }

    private static boolean offers(List<Tools.Spec> tools, String name) {
        return tools.stream().anyMatch(spec -> spec.name().equals(name));
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
            case "panic" -> new Step("Working out what I can't do", null);
            default -> new Step("Working on it", null);
        };
    }

    public Outcome run(String request, String xml, java.util.function.Consumer<Step> onStep) {
        DecisionWorkspace workspace = new DecisionWorkspace(xml, editor, gates, examples);
        List<Tools.Message> conversation = new ArrayList<>();
        conversation.add(Tools.Message.user(request));

        AiProvider.Reading reading = ai.classify(request);
        List<Tools.Spec> offered = toolsFor(reading, request);
        boolean widened = false;
        log.debug("read as {}; offering {} of {} tools", reading, offered.size(), SPECS.size());

        List<String> called = new ArrayList<>();
        // Every distinct call and what it returned. A model that repeats a call verbatim is
        // stuck, not thinking — gpt-oss:20b sent the same rejected set_cell four times over
        // 292 seconds — and running it again cannot produce a different answer, because none
        // of these tools depend on anything but the working copy the last call did not change.
        java.util.Map<String, String> repeated = new java.util.LinkedHashMap<>();
        String closing = null;
        boolean finishing = false;
        // done is refused once while the gates are unhappy, and honoured the second time. Once,
        // because the point is to tell the model what it left behind while it can still fix it;
        // only once, because a refusal it cannot satisfy would spend the rest of the turns
        // arriving at the same place the gates were going to put it anyway.
        boolean doneRefused = false;
        boolean answerRetried = false;
        int turn = 0;

        while (turn < maxTurns) {
            turn++;
            Tools.Turn reply;
            try {
                reply = ai.nextTurn(Prompts.DECISION_TOOLS, conversation, offered);
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
                return finish(workspace, text.isEmpty() ? null : text, turn, called, false,
                        reading).readAs(reading);
            }

            conversation.add(Tools.Message.assistant(reply.text(), reply.calls()));

            for (Tools.Call call : reply.calls()) {
                called.add(call.name());

                // It asked for a real tool that was not on the list it was given. The routing
                // was wrong, and the cheapest possible repair is to stop hiding the rest and
                // run the call anyway — the tool exists, and nothing about it was unsafe, only
                // unadvertised. A wrong reading therefore costs nothing but this line.
                if (KNOWN.contains(call.name()) && !offers(offered, call.name())) {
                    log.debug("read as {} but asked for {}; offering everything from here",
                            reading, call.name());
                    offered = SPECS;
                    widened = true;
                }

                if ("panic".equals(call.name())) {
                    // Constrained decoding is why this is here. Ollama enforces the schema at
                    // the decoder, so a model given seven tools cannot ask for the eighth — it
                    // has no way to tell us the narrowing was wrong. A panic is the only signal
                    // that reaches us, so when tools were withheld, the first one buys a retry
                    // with all of them rather than an answer. This is what keeps the word lists
                    // in RENAMES and ARITHMETIC from having to be right.
                    if (offered.size() < SPECS.size() && !widened) {
                        widened = true;
                        offered = SPECS;
                        log.debug("panic with a narrowed tool set; offering everything and asking again");
                        conversation.add(Tools.Message.tool("panic", "Before you give up: you now "
                                + "have every tool, including rename_decision and calculate. If "
                                + "one of those does what was asked, use it. If not, panic again "
                                + "and it will be taken as final."));
                        break;
                    }
                    String why = call.text("why").trim().toUpperCase(java.util.Locale.ROOT);
                    log.info("tool loop panicked after {} turns: {}", turn, why);
                    return Outcome.panicked(panicMessage(call.text("what"), why),
                            PANIC_REASONS.containsKey(why) ? why : "UNSURE", turn, called)
                            .readAs(reading);
                }

                if ("done".equals(call.name())) {
                    // No argument to read. The summary is the edit log, which is what actually
                    // happened; the model's account of it was a second, unchecked source for
                    // the same fact, and gpt-oss:20b filled it with its own arguments object.
                    String left = workspace.check();
                    if (!doneRefused && workspace.changed() && !left.startsWith("All checks pass")) {
                        doneRefused = true;
                        conversation.add(Tools.Message.tool("done", left
                                + "\nThe change isn't finished. Fix it, or call panic."));
                        break;
                    }
                    closing = "";
                    finishing = true;
                    break;
                }

                if ("answer".equals(call.name())) {
                    String text = call.text("text");
                    // A required argument is not enforced. Ollama constrains the *choice* of
                    // tool at the decoder but not the completeness of its arguments, so a small
                    // model can call answer with {} — measured on ornith:9b, which read the
                    // table correctly over two turns and then produced
                    // {"name":"answer","arguments":{}}. Ending there discards a correct
                    // conversation and shows the person a fallback sentence instead of the
                    // answer it was about to give. Asked once, then taken at face value.
                    if (text.isBlank() && !answerRetried) {
                        answerRetried = true;
                        log.debug("answer arrived with no text; asking once");
                        conversation.add(Tools.Message.tool("answer",
                                "That call had no text in it, so nothing was said. Call answer "
                                        + "again with the answer itself in \"text\"."));
                        break;
                    }
                    closing = text;
                    finishing = true;
                    break;
                }

                onStep.accept(describe(call));
                int before = workspace.edits().size();
                String result = repeated.containsKey(signature(call))
                        ? alreadyTried(repeated.get(signature(call)))
                        : dispatch(workspace, call);
                repeated.putIfAbsent(signature(call), result);
                // The repeat cutoff rests on "none of these tools depend on anything a failed
                // call changed". show_decision broke that premise: a set_cell refused for not
                // having looked is *meant* to be retried verbatim once the model has looked.
                // A successful write breaks it too — the table it refers to is a different
                // table now. Both clear the cache rather than weaken the cutoff.
                if ("show_decision".equals(call.name()) || workspace.edits().size() != before) {
                    repeated.clear();
                }
                // At debug because it is the whole conversation and it is long; on when you
                // are asking why a loop took the turns it did, which is most of the time.
                log.debug("turn {} · {}({}) → {}", turn, call.name(), call.arguments(),
                        result.replace('\n', ' '));
                conversation.add(Tools.Message.tool(call.name(), result));
            }

            if (finishing) {
                return finish(workspace, closing.isBlank() ? null : closing, turn, called, false,
                        reading).readAs(reading);
            }

            // Two turns left. A cap hit that says nothing is the worst outcome this loop has —
            // ornith:9b produced four in twenty requests — and it is not that the model had
            // nothing to say, only that nothing asked it before the turns ran out.
            if (turn == maxTurns - 2) {
                conversation.add(Tools.Message.user("Two turns left. Call done if the change is "
                        + "made, or panic to say what you cannot do."));
            }
        }

        // Out of turns. If the model got as far as a change that passes, offering it is better
        // than throwing the work away; if it did not, say so plainly rather than inventing a
        // reason. A silent cap is the worst outcome of a loop and the one worth naming.
        log.info("tool loop hit its {}-turn cap", maxTurns);
        return (workspace.changed()
                ? finish(workspace, null, turn, called, true, reading)
                : Outcome.declined(reading == AiProvider.Reading.QUESTION
                        ? "I couldn't work that out from this file."
                        : "I couldn't work out how to make that change.", turn, called, true))
                .readAs(reading);
    }

    /** Runs the gates one last time and turns the workspace into something the UI can show. */
    private Outcome finish(DecisionWorkspace workspace, String closing, int turns,
                           List<String> called, boolean hitCap, AiProvider.Reading reading) {
        if (!workspace.changed()) {
            // A model that stops without saying anything still owes the person a sentence, and
            // which sentence depends on what they asked for. "I couldn't work out which rule
            // you meant" is edit-shaped, and ornith:9b produced it in reply to "why would a $30
            // refund be automatic?" after four turns spent reading the rules correctly.
            String fallback = reading == AiProvider.Reading.QUESTION
                    ? "I couldn't find an answer to that in this file."
                    : "I couldn't work out which rule you meant.";
            return Outcome.answered(closing == null ? fallback : closing, turns, called);
        }

        List<GateResult> checked = workspace.gates();
        boolean ok = DecisionGates.allPassed(checked);
        String summary = closing != null && !closing.isBlank()
                ? closing : String.join(" ", workspace.edits());

        return new Outcome(null, ok ? workspace.workingXml() : null, workspace.edits(), checked, ok,
                ok ? summary : whyNot(checked), turns, called, hitCap, false, null, null);
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
                        + "rename_decision, calculate, check, done, answer, panic.";
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
                    "Every decision in the file, and whether each is a table of rules or a "
                            + "formula. Start here when unsure which one is meant.",
                    object(Map.of(), List.of())),

            new Tools.Spec("show_decision",
                    "The columns and numbered rules of one decision. Look before you change: "
                            + "a write to a decision you have not opened is refused.",
                    object(Map.of("decision", string("Its name, exactly as listed.")),
                            List.of("decision"))),

            new Tools.Spec("set_cell",
                    "Change one cell of one rule. Returns what the change broke, if anything, "
                            + "and the table as it now stands.",
                    object(new LinkedHashMap<>(Map.of(
                            "decision", string("Its name."),
                            "rule", Map.of("type", "integer",
                                    "description", "Which rule, numbered from 1 as shown."),
                            "column", string("A column heading, or \"outcome\"."),
                            "expect", string("What the cell holds now, copied exactly, quotes "
                                    + "included. Refused if it holds anything else."),
                            "to", string("What it should say instead."))),
                            List.of("decision", "rule", "column", "expect", "to"))),

            new Tools.Spec("add_rule",
                    "Append a rule to a table. One condition per column, in the order "
                            + "show_decision listed them.",
                    object(new LinkedHashMap<>(Map.of(
                            "decision", string("Its name."),
                            "conditions", Map.of("type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "One per column, in column order. \"-\" for "
                                            + "a column that does not matter here."),
                            "outcomes", Map.of("type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "What the rule produces."))),
                            List.of("decision", "conditions", "outcomes"))),

            new Tools.Spec("rename_decision",
                    "Rename a decision. References to it are updated too.",
                    object(new LinkedHashMap<>(Map.of(
                            "from", string("Its current name, exactly as listed."),
                            "to", string("The new name."))),
                            List.of("from", "to"))),

            new Tools.Spec("calculate",
                    "Arithmetic, done exactly. Use it rather than working a number out yourself. "
                            + "One value per name in \"variables\"; to run one formula over many "
                            + "numbers, name that one in \"variable\" and list them in \"values\".",
                    object(new LinkedHashMap<>(Map.of(
                            "formula", string("e.g. (c * 9 / 5) + 32. Numbers, + - * / ( ), and "
                                    + "round, abs, min, max."),
                            "variable", string("The name the values replace, e.g. c. Empty if "
                                    + "there is none."),
                            "values", Map.of("type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "The values to put through it."),
                            "variables", Map.of("type", "array",
                                    "items", Map.of("type", "string"),
                                    "description", "Other names it uses, each name=number."))),
                            List.of("formula"))),

            new Tools.Spec("check",
                    "Re-check without changing anything. Every write already does this.",
                    object(Map.of(), List.of())),

            new Tools.Spec("done",
                    "Finish. Takes nothing; what you changed is already recorded.",
                    object(Map.of(), List.of())),

            new Tools.Spec("answer",
                    "Finish by answering a question about the rules.",
                    object(Map.of("text", string("The answer, in the person's own words. Never "
                                    + "name tools, rule numbers, columns or DMN.")),
                            List.of("text"))),

            new Tools.Spec("panic",
                    "Stop and say what you cannot do. Correct, not failure. First ask whether it "
                            + "can be done by changing values: rescaling, rounding or converting "
                            + "a column is set_cell on each threshold.",
                    object(new LinkedHashMap<>(Map.of(
                            "what", string("The part you cannot do, in the person's own words: "
                                    + "\"add a column for postcode\", never \"create an "
                                    + "inputExpression\"."),
                            "why", Map.of("type", "string",
                                    "enum", List.of("NO_SUCH_DECISION", "NOT_A_TABLE",
                                            "NEEDS_NEW_COLUMN", "NEEDS_NEW_DECISION",
                                            "NEEDS_RULE_REMOVAL", "AMBIGUOUS",
                                            "CHECK_KEEPS_FAILING", "UNSURE"),
                                    "description", "Which of these. The sentence the person "
                                            + "reads is written from it."))),
                            List.of("what", "why"))));
}
