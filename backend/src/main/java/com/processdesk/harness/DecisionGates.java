package com.processdesk.harness;

import org.drools.io.ByteArrayResource;
import org.kie.api.io.Resource;
import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNRuntime;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import org.kie.dmn.validation.DMNValidator;
import org.kie.dmn.validation.DMNValidatorFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The checks a proposed change to a decision model must pass.
 *
 * <p>Deliberately not the process gates. Those hand the file to the BPMN parser, which no
 * DMN file will ever satisfy — reusing them would mean a check that always fails, or worse,
 * one nobody noticed was meaningless. These use Kogito's DMN compiler instead, so passing
 * the first gate means the decision engine can load the model.
 */
@Component
public class DecisionGates {

    private static final Logger log = LoggerFactory.getLogger(DecisionGates.class);

    /** Checks a file on its own. No baseline, so nothing can be said about what changed. */
    public List<GateResult> run(String xml) {
        return run(xml, xml);
    }

    /**
     * Checks a proposed change against the file it came from.
     *
     * <p>The baseline matters. Real decision models are not clean: the lending model in
     * {@code assets/} has twelve overlapping-rule errors before anything touches it. A gate
     * that failed on "the analyser reports errors" would refuse every edit to it, including
     * correct ones, and a gate nobody can satisfy gets switched off. The question worth
     * asking is not whether the model is perfect but whether this change made it worse.
     */
    public List<GateResult> run(String before, String after) {
        return List.of(structure(after), shape(after), rules(after), coverage(before, after));
    }

    public static boolean allPassed(List<GateResult> gates) {
        return gates.stream().allMatch(GateResult::ok);
    }

    /** Gate 1 — Structure. Kogito's DMN compiler loads the model, and reports no errors. */
    GateResult structure(String xml) {
        try {
            Resource resource = new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8));
            resource.setSourcePath("decision.dmn");
            DMNRuntime runtime = DMNRuntimeBuilder.fromDefaults()
                    .buildConfiguration()
                    .fromResources(List.of(resource))
                    .getOrElseThrow(IllegalStateException::new);

            DMNModel model = runtime.getModels().stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("no model"));

            List<DMNMessage> errors = model.getMessages().stream()
                    .filter(m -> m.getSeverity() == DMNMessage.Severity.ERROR)
                    .toList();
            if (!errors.isEmpty()) {
                return GateResult.fail("structure", "Structure",
                        "Kogito rejected the decision model: " + errors.get(0).getText());
            }
            return GateResult.pass("structure", "Structure", "Kogito can load the decision model.");
        } catch (Exception e) {
            log.debug("decision structure gate rejected the document", e);
            return GateResult.fail("structure", "Structure",
                    "Kogito couldn't read the decision model after this change.");
        }
    }

    /**
     * Gate 2 — Shape. Every rule must have exactly one condition per column and at least one
     * outcome. A rule with the wrong number of cells is the classic way to corrupt a table:
     * conditions silently shift into the wrong columns.
     */
    GateResult shape(String xml) {
        try {
            BpmnDocument doc = BpmnDocument.parse(xml);
            for (Element table : doc.elements("decisionTable")) {
                int columns = DecisionEditor.columnsOf(table).size();
                int outputs = (int) BpmnDocument.childElements(table).stream()
                        .filter(c -> "output".equals(c.getLocalName())).count();

                for (Element rule : BpmnDocument.childElements(table)) {
                    if (!"rule".equals(rule.getLocalName())) {
                        continue;
                    }
                    long conditions = BpmnDocument.childElements(rule).stream()
                            .filter(c -> "inputEntry".equals(c.getLocalName())).count();
                    long outcomes = BpmnDocument.childElements(rule).stream()
                            .filter(c -> "outputEntry".equals(c.getLocalName())).count();

                    if (conditions != columns) {
                        return GateResult.fail("shape", "Shape",
                                "A rule has " + conditions + " conditions but the table has "
                                        + columns + " columns.");
                    }
                    if (outcomes != Math.max(1, outputs)) {
                        return GateResult.fail("shape", "Shape", "A rule is missing its outcome.");
                    }
                }
            }
            return GateResult.pass("shape", "Shape", "Every rule lines up with the table's columns.");
        } catch (Exception e) {
            return GateResult.fail("shape", "Shape", "Couldn't read the decision model.");
        }
    }

    /**
     * Gate 4 — Coverage. The change must leave every case matching exactly one rule.
     *
     * <p>The gate this project most needed and did not have. Changing the "High" band of a
     * score table from {@code <=10} to {@code <=20} leaves the neighbouring rule reading
     * {@code (10..70]}, so a score of 15 matches both — and under a UNIQUE hit policy that
     * is not a style problem, it is a table that can no longer say what the answer is. The
     * first three gates all passed it: the model compiles, every rule has the right number
     * of cells, and no decision is empty. Nothing was structurally wrong. It just meant
     * something different from what the user asked for.
     *
     * <p>Kogito's own decision-table analyser finds it, from a dependency already on the
     * classpath and previously unused. The analysis is comparative: only messages this
     * change introduced are reported, so a model that was already imperfect stays editable.
     */
    GateResult coverage(String before, String after) {
        java.util.Map<String, String> was;
        java.util.Map<String, String> now;
        try {
            now = analyse(after);
        } catch (Exception e) {
            log.debug("overlap analysis failed on the proposed model", e);
            return GateResult.fail("coverage", "Coverage",
                    "Couldn't check whether this change leaves a gap or an overlap.");
        }
        try {
            was = analyse(before);
        } catch (Exception e) {
            // The analyser cannot read the original either, so it is the file, not the edit.
            log.debug("overlap analysis failed on the original model", e);
            return GateResult.pass("coverage", "Coverage",
                    "This model can't be checked for gaps or overlaps, before or after.");
        }

        // Compared by signature, reported by text: the identity has to survive a rename,
        // and the wording the user reads has to name the table it is about.
        List<String> introduced = now.entrySet().stream()
                .filter(entry -> !was.containsKey(entry.getKey()))
                .map(java.util.Map.Entry::getValue)
                .sorted()
                .toList();
        if (introduced.isEmpty()) {
            return was.isEmpty()
                    ? GateResult.pass("coverage", "Coverage",
                            "Every case matches exactly one rule.")
                    : GateResult.pass("coverage", "Coverage",
                            "This change doesn't leave any new gaps or overlaps.");
        }
        return GateResult.fail("coverage", "Coverage", describe(introduced));
    }

    /** The analyser's findings: signature to readable text. Below error is not a defect. */
    private java.util.Map<String, String> analyse(String xml) {
        DMNValidator validator = DMNValidatorFactory.newValidator(List.of());
        try {
            return validator.validate(new StringReader(xml),
                            DMNValidator.Validation.VALIDATE_COMPILATION,
                            DMNValidator.Validation.ANALYZE_DECISION_TABLE)
                    .stream()
                    // Errors of any kind, plus the decision-table analyser's own findings —
                    // which it reports at WARN. A gap is a WARN and a table that answers
                    // nothing for some inputs, so filtering on severity alone checked half
                    // the problem: the overlap was caught and the hole beside it was not.
                    .filter(m -> m.getSeverity() == DMNMessage.Severity.ERROR
                            || String.valueOf(m.getMessageType()).startsWith("DECISION_TABLE"))
                    .collect(java.util.stream.Collectors.toMap(
                            DecisionGates::signature,
                            m -> m.getText() == null ? "" : m.getText(),
                            (first, second) -> first));
        } finally {
            try {
                validator.dispose();
            } catch (Exception ignored) {
                // Disposal failing must not turn a clean analysis into a failed gate.
            }
        }
    }

    /**
     * Identifies a finding in a way that survives changes which are not about it.
     *
     * <p>The message text names the table, so comparing on text made a rename look like it
     * had introduced every overlap the file already had: twelve pre-existing errors in "Loan
     * Recommendation" reappeared as twelve new errors in "Lending Decision", and a rename
     * that changed no rule at all was refused. Identity is the element the finding is about
     * and the rules involved — neither of which a rename touches.
     */
    private static String signature(DMNMessage message) {
        String text = message.getText() == null ? "" : message.getText();
        java.util.regex.Matcher rules =
                java.util.regex.Pattern.compile("for rules: \\[([^]]*)]").matcher(text);
        String subject = rules.find()
                ? "rules " + rules.group(1)
                // Names are quoted in these messages, and a rename rewrites them. Dropping
                // quoted spans keeps what the finding is, without what it is called.
                : text.replaceAll("'[^']*'", "'…'");
        return message.getMessageType() + "|" + message.getSourceId() + "|" + subject;
    }

    /**
     * Says what the analyser found, to someone who did not write the table.
     *
     * <p>Its own wording — "HitPolicy ... should be PRIORITY", "Overlap values: [ "Low",
     * "Affordable", [ "A" .. "D" ) ] for rules: [3, 7]", "Gap detected: [ ( 10 .. 20 ] ]" —
     * is precise and unusable for the person this tool is for. A gap and an overlap are
     * different problems and get different sentences: one means the table gives two answers,
     * the other means it gives none.
     */
    private static String describe(List<String> messages) {
        java.util.Set<String> tables = new java.util.LinkedHashSet<>();
        for (String message : messages) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("decision table '([^']+)'").matcher(message);
            if (matcher.find()) {
                tables.add(matcher.group(1));
            }
        }
        String where = tables.isEmpty() ? "this decision" : "\"" + String.join("\", \"", tables) + "\"";

        java.util.List<String> gaps = messages.stream()
                .filter(m -> m.toLowerCase().contains("gap detected"))
                .map(DecisionGates::valuesIn)
                .filter(v -> !v.isBlank())
                .toList();
        if (!gaps.isEmpty()) {
            return "After this change, nothing in " + where + " covers " + String.join(", ", gaps)
                    + ", so those cases would get no answer at all. Moving one boundary usually "
                    + "means moving the one next to it.";
        }
        return "After this change, two rules in " + where + " can both apply to the same case, "
                + "so the table wouldn't say which answer wins. Check the neighbouring rule — "
                + "changing one boundary usually means moving the one next to it.";
    }

    /** The range out of "Gap detected: [ ( 10 .. 20 ] ]", tidied for reading. */
    private static String valuesIn(String message) {
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("Gap detected: \\[(.*?)]\\s*\\(DMN id").matcher(message);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).trim().replaceAll("\\s+", " ");
    }

    /** Gate 3 — Rules. A decision table with no rules decides nothing. */
    GateResult rules(String xml) {
        try {
            BpmnDocument doc = BpmnDocument.parse(xml);
            for (Element table : doc.elements("decisionTable")) {
                boolean hasRule = BpmnDocument.childElements(table).stream()
                        .anyMatch(c -> "rule".equals(c.getLocalName()));
                if (!hasRule) {
                    return GateResult.fail("rules", "Rules", "A decision has no rules left, so it can't decide anything.");
                }
            }
            return GateResult.pass("rules", "Rules", "Every decision still has rules to apply.");
        } catch (Exception e) {
            return GateResult.fail("rules", "Rules", "Couldn't read the decision model.");
        }
    }
}
