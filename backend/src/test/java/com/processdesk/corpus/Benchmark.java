package com.processdesk.corpus;

import com.processdesk.ai.DecisionToolLoop;
import com.processdesk.ai.OllamaAiProvider;
import com.processdesk.harness.DecisionEditor;
import com.processdesk.harness.DecisionGates;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How well a model drives the tool loop, measured rather than guessed.
 *
 * <p>Parameter count is a proxy for the thing that actually decides which local model is usable,
 * which is loop pathology: calling a tool that does not exist, forgetting what a previous tool
 * returned, repeating a call, or never finishing. None of that tracks parameter count cleanly —
 * so it is measured here instead of argued about in {@code docs/08-choosing-a-model.md}.
 *
 * <p>Run it against whatever is installed:
 * <pre>
 *   ./gradlew bench -Pllm=gpt-oss:20b
 *   ./gradlew bench -Pllm=ornith:9b -Pruns=2
 * </pre>
 *
 * <p>{@code -Pllm}, not {@code -Pmodel}: {@code model} is a reserved Gradle Project property,
 * so {@code -Pmodel=} is silently shadowed and the model name becomes the Project's toString.
 *
 * <p>No assertions and no pass mark. A benchmark that fails the build would get deleted the
 * first time a model had a bad night; what it produces is a table to put in a report.
 */
public final class Benchmark {

    private Benchmark() {
    }

    /**
     * What a request is for, which decides what counts as success.
     *
     * <p>A refusal is the right answer to {@link #IMPOSSIBLE}, and the wrong answer to
     * {@link #EDIT}. Scoring both as "did it produce a proposal" would reward a model that
     * edits everything and punish one that knows its limits.
     */
    private enum Expect { EDIT, QUESTION, IMPOSSIBLE }

    private record Case(Expect expect, String request) {}

    /**
     * The requests, chosen to cover what has actually broken rather than to be exhaustive.
     *
     * <p>Several are here because they failed at some point in this project's history: the DTI
     * pair could not be expressed by one intent, the boundary changes silently left gaps, and
     * "create a new criteria" had no operation behind it at all.
     */
    private static final List<Case> CASES = List.of(
            // Single cell, named by its condition — the shape that needed four intent kinds.
            new Case(Expect.EDIT, "change the risk score for under 18s to -150"),
            new Case(Expect.EDIT, "I want to change Income Risk score for under 18's to -150"),
            new Case(Expect.EDIT, "for borrowers aged 18 to 35 set the risk score to 40"),
            new Case(Expect.EDIT, "can we make under 18s -75 instead"),

            // Two cells in one sentence. Impossible before the loop.
            new Case(Expect.EDIT, "Please change the DTI for affordable to be < 0.15 "
                    + "and Marginal to be from 0.15 to 0.36"),

            // One boundary named, which always implies a second edit beside it.
            new Case(Expect.EDIT, "make the income risk category High for scores of 20 or less"),
            new Case(Expect.EDIT, "Affordability should be Not affordable above 0.4"),

            // A name that means two things: DTI is a formula and a column.
            new Case(Expect.EDIT, "set the DTI cutoff for Marginal to start at 0.30"),

            // A new rule.
            new Case(Expect.EDIT, "add a criteria to Affordability Category: DTI over 0.36 up "
                    + "to 0.5 is Poor, and Not affordable starts above 0.5"),

            // Rename, including the references other decisions hold.
            new Case(Expect.EDIT, "rename the Reserves Months decision to Cash Reserves"),

            // Questions. The second and third are traps: they open like instructions.
            new Case(Expect.QUESTION, "why would a loan be declined?"),
            new Case(Expect.QUESTION, "what score do under 18s get?"),
            new Case(Expect.QUESTION, "tell me how affordability is decided"),
            new Case(Expect.QUESTION, "what does the credit risk category depend on?"),

            // Nothing in the file supports these, and inventing an answer is the failure.
            new Case(Expect.IMPOSSIBLE, "set the risk score for over 100 to 5"),
            new Case(Expect.IMPOSSIBLE, "delete the rule for borrowers over 60"),
            new Case(Expect.IMPOSSIBLE, "add a column for postcode to the affordability table"),
            new Case(Expect.IMPOSSIBLE, "change the maximum loan amount to 500000"),
            new Case(Expect.IMPOSSIBLE, "make the DTI formula divide by 13 instead of 12"),
            new Case(Expect.IMPOSSIBLE, "approve every application"),

            // ── The same work, typed by someone who was not thinking about this benchmark ──
            //
            // Everything above is a clean sentence written while looking at the code. These are
            // not. They are terse, rambling, misspelt, indirect, and in five other languages,
            // because that is what a request looks like when it comes from the person who knows
            // the rule rather than the person who wrote the tool. The offline half of this lives
            // in RequestConformanceTest, which checks what the harness does with each of them;
            // only a model can answer whether it still gets the edit right.

            new Case(Expect.EDIT, "dti affordable <0.15"),
            new Case(Expect.EDIT, "pls change income risk score for under 18 to -150 thx"),
            new Case(Expect.EDIT, "so the thing where it says over 70 is low, that needs to be "
                    + "over 80 instead i think"),
            new Case(Expect.EDIT, "can we make it so a score of 20 or less counts as high risk"),
            new Case(Expect.EDIT, "hola, necesito que la categoría de asequibilidad use 0,15 "
                    + "en vez de 0,33"),
            new Case(Expect.EDIT, "die Grenze für Affordable muss unter 0,15 liegen"),
            new Case(Expect.EDIT, "il faudrait que le seuil passe à 0,15 pour abordable"),
            new Case(Expect.EDIT, "muda o limite de acessível para menos de 0,15 por favor"),
            // A rename that nobody phrased as a rename. The word list will miss it, and the
            // panic retry is what is being measured here.
            new Case(Expect.EDIT, "can we call the Reserves Months decision something clearer, "
                    + "like Cash Reserves"),

            new Case(Expect.QUESTION, "what does affordability actually look at"),
            new Case(Expect.QUESTION, "i don't get how the loan recommendation is decided, walk "
                    + "me through it"),
            new Case(Expect.QUESTION, "¿qué reglas se aplican para rechazar un préstamo?"),
            new Case(Expect.QUESTION, "welche Regeln entscheiden über eine Ablehnung?"),
            new Case(Expect.QUESTION, "लोन किस आधार पर मंज़ूर होता है?"),
            // Opens like a rename and is a question. Which it is depends on what the person
            // wants, not on the words, which is exactly the judgement no gate can make.
            new Case(Expect.QUESTION, "why is it called Reserves Months and not something clearer"),

            new Case(Expect.IMPOSSIBLE, "borra la regla de los mayores de 60"),
            new Case(Expect.IMPOSSIBLE, "füge eine Spalte für die Postleitzahl hinzu"),
            new Case(Expect.IMPOSSIBLE, "just make it approve everyone, i don't care how"));

    private record Result(Case source, boolean scored, int turns, long millis,
                          long unknownTools, boolean hitCap, String detail, boolean unreachable,
                          String panicReason, String reading, long promptTokens,
                          long promptNanos) {}

    public static void main(String[] args) throws Exception {
        String model = System.getProperty("bench.model", "gpt-oss:20b");
        int runs = Integer.parseInt(System.getProperty("bench.runs", "1"));
        int maxTurns = Integer.parseInt(System.getProperty("bench.maxTurns", "12"));

        String xml = Files.readString(Path.of("assets/loan-recommendation.dmn"));

        OllamaAiProvider provider = new OllamaAiProvider(
                System.getProperty("bench.url", "http://localhost:11434"),
                model, 16384, 0, "30m", false, 512, 200, 300);
        DecisionToolLoop loop = new DecisionToolLoop(
                provider, new DecisionEditor(), new DecisionGates(), maxTurns);

        System.out.println("model=" + model + "  runs=" + runs + "  maxTurns=" + maxTurns);
        System.out.println("=".repeat(100));

        // One real call before spending twenty. An unreachable model scores every request the
        // same way, and the report that comes out — 30%, every miss reading "I couldn't reach
        // the model" — looks like a finding about the model rather than a broken invocation.
        // That happened: -Pmodel is shadowed by Gradle, so the name arrived as the Project.
        try {
            provider.nextTurn("Reply with the answer tool.",
                    List.of(com.processdesk.ai.Tools.Message.user("say ready")),
                    DecisionToolLoop.SPECS);
        } catch (Exception e) {
            System.err.println("Can't reach " + model + " — " + e.getMessage());
            System.err.println("Check `ollama list`, and that the model name is spelled exactly.");
            System.exit(1);
        }

        String only = System.getProperty("bench.only", "").trim();
        List<Case> cases = only.isEmpty() ? CASES : CASES.stream()
                .filter(one -> one.expect().name().equalsIgnoreCase(only)
                        || one.request().toLowerCase(java.util.Locale.ROOT)
                                .contains(only.toLowerCase(java.util.Locale.ROOT)))
                .toList();
        if (cases.isEmpty()) {
            System.out.println("no case matches -Ponly=" + only);
            System.exit(1);
        }
        if (cases.size() != CASES.size()) {
            // Said out loud. A score over a subset that reads like a score over the suite is
            // the same mistake as counting a timeout as a correct refusal.
            System.out.printf("running %d of %d cases (-Ponly=%s)%n%n",
                    cases.size(), CASES.size(), only);
        }

        List<Result> results = new ArrayList<>();
        for (int run = 1; run <= runs; run++) {
            for (Case one : cases) {
                results.add(measure(loop, provider, xml, one));
            }
        }
        report(model, results, runs);
    }

    private static Result measure(DecisionToolLoop loop, OllamaAiProvider provider, String xml,
                                  Case one) {
        long[] before = provider.spent();
        long started = System.nanoTime();
        DecisionToolLoop.Outcome outcome;
        try {
            outcome = loop.run(one.request(), xml);
        } catch (Exception e) {
            return new Result(one, false, 0, 0, 0, false, "threw: " + e.getMessage(), true,
                    null, null, 0, 0);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        long[] after = provider.spent();

        boolean proposed = outcome.proposedXml() != null;
        // A request the model never received is not a request it answered correctly. Without
        // this, a timeout scores as a right refusal, because both produce no proposal.
        boolean scored = !outcome.unreachable() && switch (one.expect()) {
            // An edit has to survive every gate; a proposal that fails them is withheld, which
            // is safe and is still not the thing the user asked for.
            case EDIT -> proposed && outcome.ok();
            case QUESTION -> outcome.isAnswer() && !proposed;
            // Refusing correctly means changing nothing and saying why.
            case IMPOSSIBLE -> !proposed;
        };

        String detail = outcome.unreachable() ? "UNREACHABLE: " + trim(outcome.message())
                : outcome.isAnswer()
                ? "answer: " + trim(outcome.answer())
                : (outcome.ok() ? "edit: " : "withheld: ") + trim(outcome.message());

        // Expected against actual, on every request rather than only the misses. A run that
        // prints only what went wrong cannot be read as evidence that the rest went right.
        String actual = outcome.unreachable() ? "UNREACHABLE"
                : outcome.isAnswer() ? "answered"
                : outcome.panicReason() != null ? "refused (" + outcome.panicReason() + ")"
                : proposed && outcome.ok() ? "edited"
                : "withheld";
        System.out.printf("%-4s %-11s %-11s %2d turns %6dms %5d prefill  %s%n",
                scored ? "ok" : "MISS", one.expect(), actual, outcome.turns(), millis,
                after[0] - before[0], trim(one.request()));
        System.out.printf("       expected %s, got %s · read as %s%n",
                switch (one.expect()) {
                    case EDIT -> "an edit that passes every gate";
                    case QUESTION -> "an answer and no proposal";
                    case IMPOSSIBLE -> "no proposal, and a reason";
                },
                actual, outcome.reading() == null ? "unrouted" : outcome.reading());
        System.out.printf("       %s%n", detail);

        return new Result(one, scored, outcome.turns(), millis,
                outcome.unknownToolCalls(), outcome.hitCap(), detail, outcome.unreachable(),
                outcome.panicReason(), outcome.reading(),
                after[0] - before[0], after[1] - before[1]);
    }

    private static void report(String model, List<Result> results, int runs) {
        System.out.println("=".repeat(100));
        System.out.println("model: " + model + "   " + results.size() + " requests over " + runs
                + (runs == 1 ? " run" : " runs"));

        for (Expect expect : Expect.values()) {
            List<Result> group = results.stream().filter(r -> r.source().expect() == expect).toList();
            if (group.isEmpty()) {
                continue;
            }
            long ok = group.stream().filter(Result::scored).count();
            System.out.printf("  %-11s %2d/%2d%n", expect, ok, group.size());
        }

        long scored = results.stream().filter(Result::scored).count();
        System.out.printf("%nscore              %d/%d (%.0f%%)%n",
                scored, results.size(), 100.0 * scored / results.size());
        System.out.printf("turns              median %d, max %d%n",
                median(results.stream().mapToLong(Result::turns).sorted().toArray()),
                results.stream().mapToLong(Result::turns).max().orElse(0));
        System.out.printf("latency            median %dms, max %dms%n",
                median(results.stream().mapToLong(Result::millis).sorted().toArray()),
                results.stream().mapToLong(Result::millis).max().orElse(0));
        System.out.printf("unknown tools      %d calls across %d requests%n",
                results.stream().mapToLong(Result::unknownTools).sum(),
                results.stream().filter(r -> r.unknownTools() > 0).count());
        // The worst failure a loop has, because it looks like a refusal rather than a bug.
        // Everything in this project since the panic tool exists to drive this to zero.
        System.out.printf("hit the turn cap   %d%n",
                results.stream().filter(Result::hitCap).count());

        // What prefill actually cost. On a CPU-only machine this is the number that decides
        // whether the tool is usable, and it is measured rather than estimated because Ollama
        // reports it per call.
        long promptTokens = results.stream().mapToLong(Result::promptTokens).sum();
        long promptNanos = results.stream().mapToLong(Result::promptNanos).sum();
        if (promptTokens > 0) {
            System.out.printf("prefill            %d tokens over %d requests, median %d each%n",
                    promptTokens, results.size(),
                    median(results.stream().mapToLong(Result::promptTokens).sorted().toArray()));
            System.out.printf("                   %.0fs total, %.1f tok/s, %.0f%% of wall clock%n",
                    promptNanos / 1e9, promptTokens * 1e9 / Math.max(promptNanos, 1),
                    100.0 * promptNanos / 1e6 / Math.max(
                            results.stream().mapToLong(Result::millis).sum(), 1));
        }

        // A refusal that names its reason is a roadmap: "refused 6" is a number, and "refused
        // 6, four of them needing a column this table does not have" is the next piece of work.
        Map<String, Long> panics = results.stream()
                .map(Result::panicReason).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.groupingBy(r -> r,
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        if (!panics.isEmpty()) {
            System.out.printf("panicked           %d%n",
                    panics.values().stream().mapToLong(Long::longValue).sum());
            panics.forEach((reason, count) -> System.out.printf("  %-18s %d%n", reason, count));
        }

        // Whether the router read each request the way the case says it is. A reading that is
        // wrong costs a widened tool set rather than a failure, but a router that is wrong
        // often is one that should be deleted for the tokens it spends.
        Map<String, Long> readings = results.stream()
                .map(r -> r.reading() == null ? "UNROUTED" : r.reading() + " for " + r.source().expect())
                .collect(java.util.stream.Collectors.groupingBy(r -> r,
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()));
        System.out.println("routing");
        readings.forEach((reading, count) -> System.out.printf("  %-24s %d%n", reading, count));
        long lost = results.stream().filter(Result::unreachable).count();
        if (lost > 0) {
            // Named loudly: these say nothing about the model, and a score computed over them
            // is a score over fewer requests than it claims.
            System.out.printf("UNREACHABLE        %d — not scored, and not the model's fault%n", lost);
        }

        List<Result> missed = results.stream().filter(r -> !r.scored()).toList();
        if (!missed.isEmpty()) {
            System.out.println("\nmissed:");
            missed.forEach(r -> System.out.printf("  [%s] %s%n     %s%n",
                    r.source().expect(), trim(r.source().request()), r.detail()));
        }
    }

    private static long median(long[] sorted) {
        return sorted.length == 0 ? 0 : sorted[sorted.length / 2];
    }

    private static String trim(String text) {
        if (text == null) {
            return "(none)";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 88 ? flat : flat.substring(0, 85) + "...";
    }
}
