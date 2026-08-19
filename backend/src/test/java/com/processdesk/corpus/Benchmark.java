package com.processdesk.corpus;

import com.processdesk.ai.DecisionToolLoop;
import com.processdesk.ai.OllamaAiProvider;
import com.processdesk.harness.DecisionEditor;
import com.processdesk.harness.DecisionGates;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
            new Case(Expect.IMPOSSIBLE, "approve every application"));

    private record Result(Case source, boolean scored, int turns, long millis,
                          long unknownTools, boolean hitCap, String detail, boolean unreachable) {}

    public static void main(String[] args) throws Exception {
        String model = System.getProperty("bench.model", "gpt-oss:20b");
        int runs = Integer.parseInt(System.getProperty("bench.runs", "1"));
        int maxTurns = Integer.parseInt(System.getProperty("bench.maxTurns", "12"));

        String xml = Files.readString(Path.of("assets/loan-recommendation.dmn"));

        OllamaAiProvider provider = new OllamaAiProvider(
                System.getProperty("bench.url", "http://localhost:11434"),
                model, 16384, 0, "30m", false, 300);
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

        List<Result> results = new ArrayList<>();
        for (int run = 1; run <= runs; run++) {
            for (Case one : CASES) {
                results.add(measure(loop, xml, one));
            }
        }
        report(model, results, runs);
    }

    private static Result measure(DecisionToolLoop loop, String xml, Case one) {
        long started = System.nanoTime();
        DecisionToolLoop.Outcome outcome;
        try {
            outcome = loop.run(one.request(), xml);
        } catch (Exception e) {
            return new Result(one, false, 0, 0, 0, false, "threw: " + e.getMessage(), true);
        }
        long millis = (System.nanoTime() - started) / 1_000_000;

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

        System.out.printf("%-4s %-11s %2d turns %6dms  %s%n", scored ? "ok" : "MISS",
                one.expect(), outcome.turns(), millis, trim(one.request()));
        System.out.printf("       %s%n", detail);

        return new Result(one, scored, outcome.turns(), millis,
                outcome.unknownToolCalls(), outcome.hitCap(), detail, outcome.unreachable());
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
        System.out.printf("hit the turn cap   %d%n",
                results.stream().filter(Result::hitCap).count());
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
