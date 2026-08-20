package com.processdesk.ai;

import com.processdesk.harness.AssetProjection;
import com.processdesk.harness.DecisionEditor;
import com.processdesk.harness.DecisionGates;
import com.processdesk.harness.DecisionProjection;
import com.processdesk.harness.ProcessProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The loop's control flow, driven by a scripted provider rather than a model.
 *
 * <p>A real model makes these decisions differently every run, which is the right thing to
 * measure in a benchmark and the wrong thing to build a test on. What is tested here is what the
 * loop does with a given sequence of replies: when it stops, what it offers, and what it does
 * when the model misbehaves — a tool that does not exist, a reply with no call at all, and a
 * conversation that never ends.
 */
class DecisionToolLoopTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DecisionEditor editor = new DecisionEditor();
    private final DecisionGates gates = new DecisionGates();

    private String loanModel() throws Exception {
        Path path = Path.of("assets/loan-recommendation.dmn");
        assertTrue(Files.exists(path), () -> "missing asset: " + path.toAbsolutePath());
        return Files.readString(path);
    }

    /** A provider that replies with a scripted sequence and records what it was asked. */
    private static class ScriptedProvider implements AiProvider {
        private final Deque<Tools.Turn> script = new ArrayDeque<>();
        private final List<Tools.Message> seen = new ArrayList<>();
        private final List<List<String>> offered = new ArrayList<>();
        private final Tools.Turn whenExhausted;
        private Reading reading = Reading.UNKNOWN;
        int turns;

        ScriptedProvider(Tools.Turn whenExhausted, Tools.Turn... turns) {
            this.whenExhausted = whenExhausted;
            this.script.addAll(List.of(turns));
        }

        ScriptedProvider reading(Reading reading) {
            this.reading = reading;
            return this;
        }

        @Override public Reading classify(String request) { return reading; }

        @Override public String name() { return "scripted"; }
        @Override public String explain(String q, AssetProjection a) { return "explained"; }
        @Override public EditIntent interpret(String r, ProcessProjection p) { return EditIntent.none("no"); }
        @Override public EditIntent interpretDecision(String r, DecisionProjection d) { return EditIntent.none("no"); }
        @Override public boolean supportsTools() { return true; }

        @Override
        public Tools.Turn nextTurn(String system, List<Tools.Message> conversation,
                                   List<Tools.Spec> tools) {
            turns++;
            seen.clear();
            seen.addAll(conversation);
            offered.add(tools.stream().map(Tools.Spec::name).toList());
            return script.isEmpty() ? whenExhausted : script.poll();
        }
    }

    private static Tools.Turn call(String name, Object... keysAndValues) {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            arguments.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return new Tools.Turn("", List.of(
                new Tools.Call("c", name, MAPPER.valueToTree(arguments))));
    }

    private DecisionToolLoop loopOf(ScriptedProvider provider, int maxTurns) {
        return new DecisionToolLoop(provider, editor, gates, maxTurns);
    }

    @Test
    @DisplayName("two edits in one request, looked up first and checked afterwards")
    void theRequestThatStartedThis() throws Exception {
        // "Change the DTI for affordable to be < 0.15 and Marginal to be from 0.15 to 0.36."
        ScriptedProvider provider = new ScriptedProvider(
                call("done"),
                call("list_decisions"),
                call("show_decision", "decision", "Affordability Category"),
                call("set_cell", "decision", "Affordability Category", "rule", 3,
                        "column", "DTI", "expect", "<0.33", "to", "<0.15"),
                call("set_cell", "decision", "Affordability Category", "rule", 2,
                        "column", "DTI", "expect", "[0.33..0.36]", "to", "[0.15..0.36]"),
                call("done"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 8).run("...", loanModel());

        assertTrue(outcome.ok(), () -> "gates refused: " + outcome.gates());
        assertNotNull(outcome.proposedXml());
        assertEquals(2, outcome.edits().size(), "both halves of the request were made");
        // The summary is the edit log, not the model's account of it. done takes no argument,
        // so there is no second, unchecked source for what happened.
        assertEquals(String.join(" ", outcome.edits()), outcome.message());
        assertTrue(outcome.message().contains("rule 3") && outcome.message().contains("rule 2"),
                outcome.message());
    }

    @Test
    @DisplayName("a half-finished pair of edits is withheld, in the gate's own words")
    void anIncompleteEditIsNotOffered() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                call("done"),
                call("show_decision", "decision", "Affordability Category"),
                call("set_cell", "decision", "Affordability Category", "rule", 3,
                        "column", "DTI", "expect", "<0.33", "to", "<0.15"),
                call("done"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 8).run("...", loanModel());

        assertFalse(outcome.ok());
        assertNull(outcome.proposedXml(), "a failing proposal must not carry a file");
        assertTrue(outcome.message().contains("no answer at all")
                || outcome.message().contains("both apply"), outcome.message());
    }

    @Test
    @DisplayName("a question is answered rather than proposed")
    void answerEndsTheLoop() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                call("done"),
                call("show_decision", "decision", "Affordability Category"),
                call("answer", "text", "A loan is affordable when debt is under a third of income."));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 8).run("what makes a loan affordable?", loanModel());

        assertTrue(outcome.isAnswer());
        assertEquals("A loan is affordable when debt is under a third of income.", outcome.answer());
        assertNull(outcome.proposedXml());
    }

    @Test
    @DisplayName("prose with no tool call is taken as the answer, not pressed for a call")
    void proseEndsTheLoop() throws Exception {
        // Small models do this constantly. Looping to demand a tool call spends turns to
        // arrive at the sentence the model already wrote.
        ScriptedProvider provider = new ScriptedProvider(
                new Tools.Turn("Nothing in this file sets a limit on loan size.", List.of()));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 8).run("is there a maximum loan?", loanModel());

        assertTrue(outcome.isAnswer());
        assertEquals("Nothing in this file sets a limit on loan size.", outcome.answer());
        assertEquals(1, outcome.turns());
    }

    @Test
    @DisplayName("a tool that does not exist is a recoverable turn")
    void anInventedToolIsSurvivable() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                call("done"),
                call("delete_rule", "decision", "Affordability Category", "rule", 2),
                call("show_decision", "decision", "Affordability Category"),
                call("answer", "text", "I can't remove a rule from that table."));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 8).run("delete a rule", loanModel());

        assertTrue(outcome.isAnswer());
        assertEquals("I can't remove a rule from that table.", outcome.answer());
        // The model was told what does exist, which is what made the next turn useful.
        assertTrue(provider.seen.stream().anyMatch(message ->
                        "tool".equals(message.role())
                                && message.content().contains("no tool called delete_rule")
                                && message.content().contains("add_rule")),
                "an unknown tool has to name the ones that exist");
    }

    @Test
    @DisplayName("a doubly-encoded argument is unwrapped before the user sees it")
    void doubleEncodedArgumentsAreUnwrapped() throws Exception {
        // Measured on gpt-oss:20b, which filled the summary with the arguments object again.
        // Without unwrapping, the panel shows raw JSON as the description of the change the
        // user is being asked to approve.
        ScriptedProvider provider = new ScriptedProvider(
                call("answer", "text", "{\"text\": \"Nothing here caps the loan size.\"}"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 4).run("is there a maximum loan?", loanModel());

        assertEquals("Nothing here caps the loan size.", outcome.answer());
    }

    @Test
    @DisplayName("an argument that merely looks like a brace is left alone")
    void onlyRealDoubleEncodingIsUnwrapped() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                call("answer", "text", "{this is not json} and it is the answer"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 4).run("...", loanModel());

        assertEquals("{this is not json} and it is the answer", outcome.answer());
    }

    @Test
    @DisplayName("a call repeated verbatim is answered with the reason it failed, not rerun")
    void repeatedCallsAreCutOff() throws Exception {
        // The measured pathology: gpt-oss:20b sent one rejected set_cell four times across 292
        // seconds. None of these tools depend on anything the failed call changed, so running
        // it again cannot answer differently — the model is stuck, and has to be told so.
        ScriptedProvider provider = new ScriptedProvider(
                call("set_cell", "decision", "Affordability Category", "rule", 1,
                        "column", "DTI", "expect", "nonsense", "to", "<0.15"),
                call("show_decision", "decision", "Affordability Category"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 5).run("...", loanModel());

        List<Tools.Message> results = provider.seen.stream()
                .filter(message -> "tool".equals(message.role())).toList();
        assertTrue(results.size() >= 2, "the loop has to have run more than one turn");
        assertTrue(results.get(results.size() - 1).content().startsWith("You already tried"),
                "a repeat is answered by naming the repeat: " + results.get(results.size() - 1).content());
        assertTrue(results.get(results.size() - 1).content().contains("Nothing was changed"),
                "and it has to carry the original reason forward");
        assertFalse(outcome.ok());
    }

    @Test
    @DisplayName("a conversation that never ends is stopped, and says so")
    void theCapHolds() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(call("list_decisions"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 4).run("...", loanModel());

        assertEquals(4, outcome.turns());
        assertEquals(4, provider.turns);
        assertFalse(outcome.ok());
        assertEquals("I couldn't work out how to make that change.", outcome.message());
    }

    @Test
    @DisplayName("work done before the cap is still offered if it passes")
    void thecapKeepsWorkThatPasses() throws Exception {
        // Ran out of turns after a complete, valid pair of edits. Throwing that away because
        // the model never said "done" would be a silent loss of correct work.
        ScriptedProvider provider = new ScriptedProvider(
                call("check"),
                call("show_decision", "decision", "Affordability Category"),
                call("set_cell", "decision", "Affordability Category", "rule", 3,
                        "column", "DTI", "expect", "<0.33", "to", "<0.15"),
                call("set_cell", "decision", "Affordability Category", "rule", 2,
                        "column", "DTI", "expect", "[0.33..0.36]", "to", "[0.15..0.36]"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 5).run("...", loanModel());

        assertTrue(outcome.ok(), () -> "gates refused: " + outcome.gates());
        assertNotNull(outcome.proposedXml());
        assertEquals(2, outcome.edits().size());
    }

    @Test
    @DisplayName("a change reports what it broke and re-renders the table, without being asked")
    void everyChangeChecksAndRerendersItself() throws Exception {
        // The three turns this removes are set_cell, check, show_decision, set_cell. Both
        // halves used to be the model's job, and a small model skipping either one is how a
        // half-finished boundary edit reached the gates.
        ScriptedProvider provider = new ScriptedProvider(
                call("panic", "what", "finish that", "why", "UNSURE"),
                call("show_decision", "decision", "Affordability Category"),
                call("set_cell", "decision", "Affordability Category", "rule", 3,
                        "column", "DTI", "expect", "<0.33", "to", "<0.15"));

        loopOf(provider, 4).run("...", loanModel());

        String result = provider.seen.stream()
                .filter(message -> "tool".equals(message.role()))
                .map(Tools.Message::content)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertTrue(result.contains("no answer at all") || result.contains("both apply"),
                "the gap has to come back with the change that made it: " + result);
        assertTrue(result.contains("The table now reads"), result);
        assertTrue(result.contains("<0.15"), "re-rendered from the working copy: " + result);
    }

    @Test
    @DisplayName("panic ends the loop with a sentence this code wrote, not the model")
    void panicIsWrittenHere() throws Exception {
        // qwen3:14b refused a valid request by reciting the prompt's own example refusal. A
        // model that can only emit a reason code has no sentence available to recite.
        ScriptedProvider provider = new ScriptedProvider(
                call("done"),
                call("panic", "what", "add a column for postcode.", "why", "NEEDS_NEW_COLUMN"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 8).run("add a column for postcode", loanModel());

        assertFalse(outcome.ok());
        assertNull(outcome.proposedXml());
        assertFalse(outcome.hitCap(), "a panic is an ending, not a cap hit");
        assertEquals("NEEDS_NEW_COLUMN", outcome.panicReason());
        assertEquals("Sorry, I can't add a column for postcode. The table would have to weigh "
                + "something it doesn't look at today, and I can only change the values in the "
                + "columns it already has.", outcome.message());
    }

    @Test
    @DisplayName("a panic reason outside the list still produces a usable sentence")
    void anUnknownPanicReasonFallsBack() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                call("panic", "what", "do that", "why", "BECAUSE_I_SAID_SO"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 8).run("...", loanModel());

        assertEquals("UNSURE", outcome.panicReason());
        assertTrue(outcome.message().startsWith("Sorry, I can't do that."), outcome.message());
    }

    @Test
    @DisplayName("done is refused once while a gap remains, then honoured")
    void doneIsRefusedOnceWhileTheChangeIsUnfinished() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                call("done"),
                call("show_decision", "decision", "Affordability Category"),
                call("set_cell", "decision", "Affordability Category", "rule", 3,
                        "column", "DTI", "expect", "<0.33", "to", "<0.15"),
                call("done"));

        DecisionToolLoop.Outcome outcome = loopOf(provider, 8).run("...", loanModel());

        assertTrue(provider.seen.stream().anyMatch(message ->
                        "tool".equals(message.role())
                                && message.content().contains("The change isn't finished")),
                "the first done has to say what is still wrong");
        // Refused once, not forever: the gates were always going to withhold this, and
        // spending the remaining turns arriving there would only cost time.
        assertEquals(4, outcome.turns());
        assertFalse(outcome.ok());
    }

    @Test
    @DisplayName("with two turns left the model is asked for an ending")
    void theLastTurnsAskForAnEnding() throws Exception {
        // Four silent cap hits in twenty requests on ornith:9b. Nothing had asked it to stop.
        ScriptedProvider provider = new ScriptedProvider(call("list_decisions"));

        loopOf(provider, 6).run("...", loanModel());

        assertEquals(1, provider.seen.stream()
                        .filter(message -> "user".equals(message.role())
                                && message.content().startsWith("Two turns left")).count(),
                "asked exactly once, on the turn where it is still actionable");
    }

    @Test
    @DisplayName("a question is not offered the tools that write")
    void aQuestionCarriesNoWriteTools() throws Exception {
        // Every model scored 12/12 on QUESTION and lost everything it lost on EDIT. A question
        // has no reason to be paying for set_cell, in prefill or in the chance of reaching it.
        ScriptedProvider provider = new ScriptedProvider(
                call("answer", "text", "Under a third of income."))
                .reading(AiProvider.Reading.QUESTION);

        loopOf(provider, 4).run("what makes a loan affordable?", loanModel());

        List<String> tools = provider.offered.get(0);
        assertEquals(List.of("list_decisions", "show_decision", "answer", "panic"), tools);
    }

    @Test
    @DisplayName("an edit gets the write tools, and only the rare ones it asks for")
    void anEditCarriesTheWriteTools() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(call("panic", "what", "x", "why", "UNSURE"))
                .reading(AiProvider.Reading.EDIT);

        loopOf(provider, 4).run("change the DTI for affordable to under 0.15", loanModel());

        List<String> tools = provider.offered.get(0);
        assertTrue(tools.containsAll(List.of("set_cell", "add_rule", "done", "panic")), "" + tools);
        // Real capabilities, rarely the answer. A rarely-correct tool in the default set is a
        // standing invitation to a wrong call, and each one is schema prefilled every turn.
        assertFalse(tools.contains("rename_decision"), "" + tools);
        assertFalse(tools.contains("calculate"), "" + tools);
        assertFalse(tools.contains("check"), "every write already runs it: " + tools);
    }

    @Test
    @DisplayName("a request that names a rename, or arithmetic, gets that tool")
    void rareToolsLoadWhenTheRequestImpliesThem() throws Exception {
        ScriptedProvider renaming = new ScriptedProvider(call("panic", "what", "x", "why", "UNSURE"))
                .reading(AiProvider.Reading.EDIT);
        loopOf(renaming, 2).run("rename the Reserves Months decision to Cash Reserves", loanModel());
        assertTrue(renaming.offered.get(0).contains("rename_decision"), "" + renaming.offered.get(0));

        ScriptedProvider converting = new ScriptedProvider(call("panic", "what", "x", "why", "UNSURE"))
                .reading(AiProvider.Reading.EDIT);
        loopOf(converting, 2).run("convert those thresholds to percent", loanModel());
        assertTrue(converting.offered.get(0).contains("calculate"), "" + converting.offered.get(0));
    }

    @Test
    @DisplayName("a wrong reading costs nothing: the call runs, and the rest is offered after")
    void aWrongReadingWidensRatherThanRefuses() throws Exception {
        // Routed as a question, but it was an edit. The tool exists and nothing about it was
        // unsafe, only unadvertised, so it runs — and the model stops being kept in the dark.
        ScriptedProvider provider = new ScriptedProvider(
                call("done"),
                call("show_decision", "decision", "Affordability Category"),
                call("set_cell", "decision", "Affordability Category", "rule", 3,
                        "column", "DTI", "expect", "<0.33", "to", "<0.15"),
                call("set_cell", "decision", "Affordability Category", "rule", 2,
                        "column", "DTI", "expect", "[0.33..0.36]", "to", "[0.15..0.36]"),
                call("done"))
                .reading(AiProvider.Reading.QUESTION);

        DecisionToolLoop.Outcome outcome = loopOf(provider, 8).run("...", loanModel());

        assertFalse(provider.offered.get(0).contains("set_cell"), "started narrow");
        assertTrue(provider.offered.get(provider.offered.size() - 1).contains("set_cell"),
                "and widened once it was clear the reading was wrong");
        assertEquals(2, outcome.edits().size(), "the unadvertised call still ran");
        assertTrue(outcome.ok(), () -> "gates refused: " + outcome.gates());
        assertEquals("QUESTION", outcome.reading());
    }

    @Test
    @DisplayName("the conversation carries tool results forward")
    void resultsAreReplayed() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(
                call("done"),
                call("list_decisions"),
                call("answer", "text", "eleven"));

        loopOf(provider, 8).run("how many decisions?", loanModel());

        // A tool result the model can no longer see is a tool call it will make again.
        assertTrue(provider.seen.stream().anyMatch(message ->
                        "tool".equals(message.role()) && message.content().contains("Affordability Category")),
                "the listing has to still be visible on the next turn");
    }
}
