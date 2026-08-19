package com.processdesk.corpus;

import com.processdesk.harness.BpmnDocument;
import com.processdesk.harness.DecisionProjection;
import com.processdesk.harness.GateResult;
import com.processdesk.harness.ProcessProjection;
import com.processdesk.harness.ValidationGates;
import com.processdesk.kogito.ProcessRuntime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Reports how this project's own reading of BPMN and DMN holds up against files it did not
 * write.
 *
 * <p>The fixtures in this repository are ones we authored, so they only ever prove that the
 * code agrees with itself. The corpora are other people's files — the DMN conformance suite,
 * the engine's own test resources — and they are where the interesting failures live. The
 * first run of this found that 99% of real decisions were being handed to the model with
 * their logic silently removed.
 *
 * <p>This <strong>reads</strong> the corpus and never runs it. A BPMN script task carries
 * executable code, so starting hundreds of downloaded processes would be running strangers'
 * code on this machine. Parsing is safe; executing is not, and the survey does not need it.
 *
 * <p>Run with {@code ./gradlew surveyCorpora} after {@code ./scripts/fetch-corpora.sh}.
 * This prints a report rather than asserting: it is a measurement to work against, and the
 * thresholds worth defending belong in tests once they are met.
 */
public final class CorpusSurvey {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : "../corpora");
        if (!Files.isDirectory(root)) {
            System.out.println("No corpora at " + root.toAbsolutePath()
                    + "\nRun ./scripts/fetch-corpora.sh first.");
            return;
        }

        List<Path> dmn = filesUnder(root, ".dmn");
        List<Path> bpmn = new ArrayList<>(filesUnder(root, ".bpmn"));
        bpmn.addAll(filesUnder(root, ".bpmn2"));

        System.out.println("corpora: " + root.toAbsolutePath().normalize());
        System.out.printf("%d DMN, %d BPMN%n", dmn.size(), bpmn.size());

        surveyDecisions(dmn);
        surveyProcesses(bpmn);
    }

    private static void surveyDecisions(List<Path> files) {
        int parsed = 0, decisions = 0, withoutLogic = 0, filesAffected = 0;
        Map<String, Integer> constructs = new LinkedHashMap<>();

        for (Path file : files) {
            String xml = read(file);
            if (xml == null) {
                continue;
            }
            for (String construct : List.of("literalExpression", "<context", "invocation",
                    "businessKnowledgeModel", "requiredDecision", "functionDefinition",
                    "relation", "decisionTable")) {
                if (xml.contains(construct)) {
                    constructs.merge(construct, 1, Integer::sum);
                }
            }

            DecisionProjection projection = DecisionProjection.of(xml);
            if (projection.isEmpty()) {
                continue;
            }
            parsed++;
            int blankHere = 0;
            for (DecisionProjection.Decision decision : projection.decisions()) {
                decisions++;
                if (decision.rules().isEmpty()) {
                    withoutLogic++;
                    blankHere++;
                }
            }
            if (blankHere > 0) {
                filesAffected++;
            }
        }

        System.out.println("\n── DECISIONS ──");
        System.out.printf("  files with decisions      %d%n", parsed);
        System.out.printf("  decisions found           %d%n", decisions);
        System.out.printf("  shown to the model with no logic   %d  (%s)%n",
                withoutLogic, percent(withoutLogic, decisions));
        System.out.printf("  files affected            %d  (%s)%n", filesAffected, percent(filesAffected, parsed));
        System.out.println("  constructs present across the corpus:");
        constructs.forEach((name, count) -> System.out.printf("    %-24s %4d files%n", name, count));
    }

    private static void surveyProcesses(List<Path> files) {
        ValidationGates gates = new ValidationGates();
        ProcessRuntime runtime = new ProcessRuntime();

        int readable = 0, kogitoReads = 0, gatesPass = 0, hasSteps = 0, projectionEmpty = 0;

        for (Path file : files) {
            String xml = read(file);
            if (xml == null) {
                continue;
            }
            readable++;

            // Throwable, not Exception: a process in the corpus recurses through an event
            // subprocess that signals itself, and StackOverflowError killed the whole survey.
            try {
                runtime.read(xml);
                kogitoReads++;
            } catch (Throwable ignored) {
                // Counted by omission.
            }

            try {
                if (ValidationGates.allPassed(gates.run(xml))) {
                    gatesPass++;
                }
            } catch (Throwable ignored) {
                // Counted by omission.
            }

            // A file with tasks whose projection is empty is the failure that matters: the
            // model would be shown a process with no steps and answer anyway.
            boolean fileHasSteps = false;
            try {
                fileHasSteps = !BpmnDocument.parse(xml).stepNames().isEmpty();
            } catch (Throwable ignored) {
                // Counted by omission.
            }
            if (fileHasSteps) {
                hasSteps++;
                if (ProcessProjection.of(xml).isEmpty()) {
                    projectionEmpty++;
                }
            }

        }

        System.out.println("\n── PROCESSES ──");
        System.out.printf("  files read                %d%n", readable);
        System.out.printf("  Kogito can parse          %d  (%s)%n", kogitoReads, percent(kogitoReads, readable));
        System.out.printf("  passes all three gates    %d  (%s)%n", gatesPass, percent(gatesPass, readable));
        System.out.printf("  files containing steps    %d%n", hasSteps);
        System.out.printf("  ...projected as EMPTY     %d  (%s)%n", projectionEmpty, percent(projectionEmpty, hasSteps));
    }

    private static List<Path> filesUnder(Path root, String suffix) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (Exception e) {
            return null;
        }
    }

    private static String percent(int part, int whole) {
        return whole == 0 ? "n/a" : String.format("%.0f%%", 100.0 * part / whole);
    }

    private CorpusSurvey() {}
}
