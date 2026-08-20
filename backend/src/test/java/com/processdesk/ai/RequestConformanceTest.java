package com.processdesk.ai;

import com.processdesk.harness.AssetProjection;
import com.processdesk.harness.DecisionEditor;
import com.processdesk.harness.DecisionGates;
import com.processdesk.harness.DecisionProjection;
import com.processdesk.harness.ProcessProjection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * How the loop reads requests that were not written for it.
 *
 * <p>Every request in {@link DecisionToolLoopTest} is a clean English sentence that a developer
 * wrote while thinking about the code. Real ones are typed by someone in a hurry, in whatever
 * language they think in, about a table they half remember. This suite is that: terse, rambling,
 * misspelt, indirect, and in six languages, run against the parts of the loop that are decided
 * by the words themselves rather than by the model.
 *
 * <p>Two things are actually under test, and neither needs a model.
 *
 * <p>**Which tools a request loads.** {@code rename_decision} and {@code calculate} are held back
 * until the request looks like it needs them, and "looks like" is a word list. Word lists do not
 * generalise, so the rows below include the ones that miss, marked as misses rather than quietly
 * passing. What makes a miss survivable is the retry: a narrowed conversation that panics gets
 * every tool and one more turn, which is the last test in this file.
 *
 * <p>**What a refusal reads like.** The model picks a reason code and this side writes the
 * sentence, so the sentence has to survive whatever the model puts in {@code what} — trailing
 * punctuation, an initial capital, an empty string, or a script with no notion of letter case.
 *
 * <p>Every row prints expected against actual, and the whole table is written to
 * {@code build/reports/conformance.txt}. Run with {@code -Pverbose} to watch it instead.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RequestConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> REPORT = new ArrayList<>();

    private final DecisionEditor editor = new DecisionEditor();
    private final DecisionGates gates = new DecisionGates();

    /** What a request should pull in beyond the tools every edit gets. */
    private record Row(AiProvider.Reading reading, String language, String request,
                       boolean rename, boolean calculate, String note) {

        Row(AiProvider.Reading reading, String language, String request,
            boolean rename, boolean calculate) {
            this(reading, language, request, rename, calculate, null);
        }
    }

    private static final AiProvider.Reading EDIT = AiProvider.Reading.EDIT;
    private static final AiProvider.Reading QUESTION = AiProvider.Reading.QUESTION;

    /**
     * The corpus.
     *
     * <p>Written to be typed rather than composed: no request here is a well-formed instruction,
     * because well-formed instructions are not what arrives. The language column is what the
     * person was thinking in, not a claim about what the model supports.
     */
    private static final List<Row> CORPUS = List.of(
            // --- ordinary edits, phrased the way people actually phrase them ---
            new Row(EDIT, "en", "change the DTI for affordable to be under 0.15", false, false),
            new Row(EDIT, "en", "affordable should be anything under 0.15 now, and marginal from "
                    + "there up to 0.36", false, false),
            new Row(EDIT, "en terse", "dti affordable <0.15", false, false),
            new Row(EDIT, "en indirect", "can we make it so a score of 20 or less counts as high "
                    + "risk", false, false),
            new Row(EDIT, "en rambling", "so the thing where it says over 70 is low, that needs "
                    + "to be over 80 instead i think, unless that breaks something", false, false),
            new Row(EDIT, "en sloppy", "pls change income risk score for under 18 to -150 thx",
                    false, false),
            new Row(EDIT, "en structured", "credit risk category, rule 2, should say Medium not "
                    + "High", false, false),
            new Row(EDIT, "es", "hola, necesito que la categoría de asequibilidad use 0,15 en vez "
                    + "de 0,33", false, false),
            new Row(EDIT, "de", "die Grenze für Marginal soll bleiben, aber Affordable muss unter "
                    + "0,15 liegen", false, false),
            new Row(EDIT, "fr", "il faudrait que le seuil passe à 0,15 pour abordable", false, false),
            new Row(EDIT, "pt", "muda o limite de acessível para menos de 0,15 por favor", false, false),

            // --- renames ---
            new Row(EDIT, "en", "rename the Reserves Months decision to Cash Reserves", true, false),
            new Row(EDIT, "en", "give that decision a new name, Debt Ratio", true, false),
            new Row(EDIT, "es", "¿puedes renombrar la decisión Income Risk Category a Banda de "
                    + "Ingresos?", true, false),
            new Row(EDIT, "de", "bitte die Entscheidung umbenennen in Bonitätsstufe", true, false),
            new Row(EDIT, "fr", "on pourrait renommer cette décision en Ratio d'endettement",
                    true, false),
            new Row(EDIT, "zh", "把 Income Risk Category 改名为 收入等级", true, false),
            new Row(EDIT, "en oblique", "can we call the DTI thing something clearer", false, false,
                    "a rename nobody phrased as one; the tool is withheld and the panic retry is "
                            + "what recovers it"),

            // --- arithmetic ---
            new Row(EDIT, "en", "convert those DTI thresholds to percentages", false, true),
            new Row(EDIT, "en", "bump every threshold in the affordability table up by 10%",
                    false, true),
            new Row(EDIT, "en", "the numbers are in celsius, make them fahrenheit", false, true),
            new Row(EDIT, "es", "multiplica los umbrales por 1,1", false, true),
            new Row(EDIT, "de", "runde die Schwellenwerte auf zwei Nachkommastellen", false, true),
            new Row(EDIT, "en implicit", "shift everything down 0.05", false, false,
                    "arithmetic nobody named as arithmetic. \"shift\" is not in the list and "
                            + "adding it would drag in every request that shifts a step; the "
                            + "panic retry is the cheaper repair"),

            // --- both at once ---
            new Row(EDIT, "en", "rename it to Debt Ratio and scale the thresholds by 1.1",
                    true, true),

            // --- questions, which should never see a tool that writes ---
            new Row(QUESTION, "en", "why would a $30 refund be automatic?", false, false),
            new Row(QUESTION, "en vague", "what does affordability actually look at", false, false),
            new Row(QUESTION, "en rambling", "i don't get how the loan recommendation is decided, "
                    + "walk me through it", false, false),
            new Row(QUESTION, "es", "¿qué reglas se aplican para rechazar un préstamo?", false, false),
            new Row(QUESTION, "de", "welche Regeln entscheiden über eine Ablehnung?", false, false),
            new Row(QUESTION, "hi", "लोन किस आधार पर मंज़ूर होता है?", false, false),
            // A question whose words look like a rename. The read is what decides, not the words:
            // a question never gets a tool that writes, however it is phrased.
            new Row(QUESTION, "en trap", "why is it called Reserves Months and not something "
                    + "clearer", false, false),

            // --- requests nothing here can do; they still route as edits ---
            new Row(EDIT, "en", "add a column for postcode to the affordability table", false, false),
            new Row(EDIT, "en", "delete the rule for borrowers over 60", false, false),
            new Row(EDIT, "en", "approve every application, no exceptions", false, false),
            new Row(EDIT, "en", "make the DTI formula divide by 13 instead of 12", false, true,
                    "reads as arithmetic because it is; the tool loads and the formula still "
                            + "cannot be edited, which is a panic rather than a wrong edit"));

    @Test
    @Order(1)
    @DisplayName("which tools a request loads, across languages and phrasings")
    void theCorpusLoadsTheRightTools() throws Exception {
        String xml = loanModel();
        List<String> failures = new ArrayList<>();
        List<String> knownMisses = new ArrayList<>();

        REPORT.add("");
        REPORT.add("TOOL SELECTION");
        REPORT.add("-".repeat(118));
        REPORT.add(String.format("%-4s %-12s %-46s  %-22s %-22s", "", "lang", "request",
                "expected extras", "actual extras"));
        REPORT.add("-".repeat(118));

        for (Row row : CORPUS) {
            ScriptedProvider provider = new ScriptedProvider(row.reading());
            new DecisionToolLoop(provider, editor, gates, 2).run(row.request(), xml);

            List<String> offered = provider.firstOffer();
            boolean rename = offered.contains("rename_decision");
            boolean calculate = offered.contains("calculate");
            boolean writes = offered.contains("set_cell");

            boolean ok = rename == row.rename() && calculate == row.calculate()
                    && writes == (row.reading() == EDIT);
            String line = String.format("%-4s %-12s %-46s  %-22s %-22s",
                    ok ? "ok" : "DIFF", row.language(), trim(row.request(), 46),
                    extras(row.rename(), row.calculate(), row.reading() == EDIT),
                    extras(rename, calculate, writes));
            REPORT.add(line);
            if (row.note() != null) {
                REPORT.add("     note: " + row.note());
            }
            if (!ok) {
                (row.note() == null ? failures : knownMisses).add(line);
            }

            // The one guarantee that is not a heuristic: a question is never handed a tool that
            // writes. Everything else here can be wrong by one turn; this cannot be wrong at all.
            if (row.reading() == QUESTION) {
                assertFalse(offered.contains("set_cell"), row.request());
                assertFalse(offered.contains("add_rule"), row.request());
                assertFalse(offered.contains("rename_decision"), row.request());
                assertFalse(offered.contains("done"), row.request());
            }
            // panic is in every set, in every reading. It is the only way out of a narrow one.
            assertTrue(offered.contains("panic"), row.request());
        }

        REPORT.add("-".repeat(118));
        REPORT.add(String.format("%d rows, %d matched, %d known misses, %d unexpected",
                CORPUS.size(), CORPUS.size() - failures.size() - knownMisses.size(),
                knownMisses.size(), failures.size()));

        assertTrue(failures.isEmpty(), () -> "tool selection differed with no note explaining it:\n"
                + String.join("\n", failures));
    }

    @Test
    @Order(2)
    @DisplayName("what a refusal reads like, whatever the model puts in it")
    void panicSentencesSurviveTheirInput() throws Exception {
        String xml = loanModel();
        record Case(String what, String why, String expected) {}

        List<Case> cases = List.of(
                new Case("add a column for postcode.", "NEEDS_NEW_COLUMN",
                        "Sorry, I can't add a column for postcode. The table would have to weigh "
                                + "something it doesn't look at today, and I can only change the "
                                + "values in the columns it already has."),
                // An initial capital, because a model that has been told to use the person's
                // words will often hand back the start of their sentence.
                new Case("Delete the rule for borrowers over 60", "NEEDS_RULE_REMOVAL",
                        "Sorry, I can't delete the rule for borrowers over 60. I can change a "
                                + "rule or add one, but I can't take one away."),
                new Case("   work out which one you meant...  ", "AMBIGUOUS",
                        "Sorry, I can't work out which one you meant. Two things in this file "
                                + "could be what you meant, so I've left it alone rather than "
                                + "guess."),
                // Nothing to name. The sentence still has to be a sentence.
                new Case("", "UNSURE",
                        "Sorry, I can't do that. I couldn't work out how to do it safely, so "
                                + "nothing has been changed."),
                // Scripts with no letter case. Lower-casing the first character is a no-op and
                // must stay one, rather than mangling the character.
                new Case("añadir una columna para el código postal", "NEEDS_NEW_COLUMN",
                        "Sorry, I can't añadir una columna para el código postal. The table "
                                + "would have to weigh something it doesn't look at today, and I "
                                + "can only change the values in the columns it already has."),
                new Case("把这条规则删掉", "NEEDS_RULE_REMOVAL",
                        "Sorry, I can't 把这条规则删掉. I can change a rule or add one, but I "
                                + "can't take one away."),
                // A reason the model invented. It gets the honest fallback, not nothing.
                new Case("do the thing", "BECAUSE_I_FELT_LIKE_IT",
                        "Sorry, I can't do the thing. I couldn't work out how to do it safely, "
                                + "so nothing has been changed."));

        REPORT.add("");
        REPORT.add("REFUSAL SENTENCES");
        REPORT.add("-".repeat(118));

        for (Case one : cases) {
            ScriptedProvider provider = new ScriptedProvider(AiProvider.Reading.UNKNOWN,
                    call("panic", "what", one.what(), "why", one.why()));
            DecisionToolLoop.Outcome outcome =
                    new DecisionToolLoop(provider, editor, gates, 4).run("...", xml);

            boolean ok = one.expected().equals(outcome.message());
            REPORT.add(String.format("%-4s %-22s what=%s", ok ? "ok" : "DIFF", one.why(),
                    one.what().isBlank() ? "(empty)" : trim(one.what(), 40)));
            REPORT.add("     expected: " + one.expected());
            REPORT.add("     actual:   " + outcome.message());

            assertEquals(one.expected(), outcome.message(), one.why());
            assertFalse(outcome.ok());
            assertNull(outcome.proposedXml());
        }
    }

    @Test
    @Order(3)
    @DisplayName("a panic with tools withheld buys one retry with all of them")
    void aNarrowedSetGetsASecondChance() throws Exception {
        // The hole this closes: Ollama enforces the tool schema at the decoder, so a model given
        // seven tools cannot ask for the eighth. It has no way to tell us the narrowing was
        // wrong, and a panic is the only signal that reaches us. "can we call the DTI thing
        // something clearer" is a rename that no word list catches, and this is what saves it.
        ScriptedProvider provider = new ScriptedProvider(AiProvider.Reading.EDIT,
                call("panic", "what", "give it a clearer name", "why", "NEEDS_NEW_DECISION"),
                call("rename_decision", "from", "Reserves Months", "to", "Cash Reserves"),
                call("done"));

        DecisionToolLoop.Outcome outcome =
                new DecisionToolLoop(provider, editor, gates, 8).run(
                        "can we call the DTI thing something clearer", loanModel());

        assertFalse(provider.firstOffer().contains("rename_decision"), "started without it");
        assertTrue(provider.offered.get(1).contains("rename_decision"),
                "and the panic bought it: " + provider.offered.get(1));
        assertTrue(outcome.ok(), () -> "gates refused: " + outcome.gates());
        assertEquals(1, outcome.edits().size(), "the rename it could not have asked for");

        REPORT.add("");
        REPORT.add("PANIC RETRY");
        REPORT.add("     first offer:  " + provider.firstOffer());
        REPORT.add("     after panic:  " + provider.offered.get(1));
        REPORT.add("     outcome:      " + outcome.message());
    }

    @Test
    @Order(4)
    @DisplayName("a second panic is taken as final, so the retry cannot become a loop")
    void theRetryHappensOnlyOnce() throws Exception {
        ScriptedProvider provider = new ScriptedProvider(AiProvider.Reading.EDIT,
                call("panic", "what", "add a column for postcode", "why", "NEEDS_NEW_COLUMN"),
                call("panic", "what", "add a column for postcode", "why", "NEEDS_NEW_COLUMN"));

        DecisionToolLoop.Outcome outcome =
                new DecisionToolLoop(provider, editor, gates, 8).run(
                        "add a column for postcode to the affordability table", loanModel());

        assertEquals("NEEDS_NEW_COLUMN", outcome.panicReason());
        assertEquals(2, outcome.turns(), "one retry, then the answer is taken at face value");
        assertFalse(outcome.hitCap());
    }

    // ── plumbing ───────────────────────────────────────────────────────────────────────────

    private String loanModel() throws Exception {
        Path path = Path.of("assets/loan-recommendation.dmn");
        assertTrue(Files.exists(path), () -> "missing asset: " + path.toAbsolutePath());
        return Files.readString(path);
    }

    private static String extras(boolean rename, boolean calculate, boolean writes) {
        List<String> names = new ArrayList<>();
        if (writes) {
            names.add("writes");
        }
        if (rename) {
            names.add("rename");
        }
        if (calculate) {
            names.add("calc");
        }
        return names.isEmpty() ? "read only" : String.join("+", names);
    }

    private static String trim(String text, int width) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= width ? flat : flat.substring(0, width - 1) + "…";
    }

    private static Tools.Turn call(String name, Object... keysAndValues) {
        Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            arguments.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return new Tools.Turn("", List.of(new Tools.Call("c", name, MAPPER.valueToTree(arguments))));
    }

    /** Answers a fixed reading and a scripted sequence, and records what it was offered. */
    private static class ScriptedProvider implements AiProvider {
        private final Deque<Tools.Turn> script = new ArrayDeque<>();
        private final List<List<String>> offered = new ArrayList<>();
        private final AiProvider.Reading reading;

        ScriptedProvider(AiProvider.Reading reading, Tools.Turn... turns) {
            this.reading = reading;
            this.script.addAll(List.of(turns));
        }

        List<String> firstOffer() {
            return offered.get(0);
        }

        @Override public String name() { return "scripted"; }
        @Override public String explain(String q, AssetProjection a) { return ""; }
        @Override public EditIntent interpret(String r, ProcessProjection p) { return EditIntent.none("no"); }
        @Override public EditIntent interpretDecision(String r, DecisionProjection d) { return EditIntent.none("no"); }
        @Override public boolean supportsTools() { return true; }
        @Override public Reading classify(String request) { return reading; }

        @Override
        public Tools.Turn nextTurn(String system, List<Tools.Message> conversation,
                                   List<Tools.Spec> tools) {
            offered.add(tools.stream().map(Tools.Spec::name).toList());
            return script.isEmpty()
                    ? new Tools.Turn("nothing more to say", List.of())
                    : script.poll();
        }
    }

    @AfterAll
    static void writeTheReport() throws Exception {
        Path out = Path.of("build/reports/conformance.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join("\n", REPORT) + "\n");
        System.out.println(String.join("\n", REPORT));
        System.out.println("\nwritten to " + out.toAbsolutePath());
    }
}
