package com.processdesk.ai;

import com.processdesk.harness.DecisionEditor;
import com.processdesk.harness.DecisionProjection;
import com.processdesk.harness.ProcessProjection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema an {@link AiProvider} constrains its reply to, built fresh for each
 * request from the file that is actually open.
 *
 * <p>The step names in the open file become the enum for {@code targetStepName}. A model
 * served through Ollama's structured output cannot emit a token the schema forbids, so a
 * step that does not exist stops being an error to detect and becomes an answer the model
 * is incapable of giving. {@link StepResolver} still runs afterwards, because a provider
 * that ignores the schema must not be trusted on that basis alone.
 */
public final class IntentSchema {

    private IntentSchema() {}

    /**
     * The reply shape for a decision edit, with the table's own columns and current values
     * as enums so a request cannot name a cell that does not exist.
     */
    public static Map<String, Object> forDecision(DecisionProjection.Vocabulary vocabulary) {
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", List.of("SET_CELL", "RENAME_DECISION", "QUESTION", "NONE"));
        kind.put("description", "SET_CELL changes one cell of one rule — every change to what "
                + "a rule tests or produces is this. RENAME_DECISION renames a decision. "
                + "QUESTION means the user is asking about the rules rather than changing them. "
                + "NONE means a change none of these can make.");

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("type", "string");
        decision.put("description", "Which decision the change is in.");
        if (!vocabulary.decisions().isEmpty()) {
            decision.put("enum", vocabulary.decisions());
        }

        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("type", "integer");
        rule.put("description", "Which rule, counting from 1 in the order they are listed.");

        Map<String, Object> column = new LinkedHashMap<>();
        column.put("type", "string");
        column.put("description", "Which cell of that rule: a column heading to change when the "
                + "rule applies, or \"" + DecisionEditor.OUTCOME + "\" to change what it produces.");
        List<String> cells = new java.util.ArrayList<>(vocabulary.columns());
        cells.add(DecisionEditor.OUTCOME);
        column.put("enum", cells);

        Map<String, Object> expect = new LinkedHashMap<>();
        expect.put("type", "string");
        expect.put("description", "What that cell contains today, copied exactly as it is "
                + "written in the rules above. The change is refused if it does not match, so "
                + "this is how a miscounted rule is caught.");

        Map<String, Object> to = new LinkedHashMap<>();
        to.put("type", "string");
        to.put("description", "The new value for that cell, or for RENAME_DECISION the "
                + "decision's new name. The replacement only, never the reason: in "
                + "\"change X to B so that C\", this is B.");

        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "Only when kind is NONE: at most 20 words telling the user "
                + "why, in their words. Never name the kinds above.");
        reason.put("maxLength", 200);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kind", kind);
        properties.put("decision", decision);
        properties.put("rule", rule);
        properties.put("column", column);
        properties.put("expect", expect);
        properties.put("to", to);
        properties.put("declineReason", reason);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required",
                List.of("kind", "decision", "rule", "column", "expect", "to", "declineReason"));
        return schema;
    }

    public static Map<String, Object> forProcess(ProcessProjection process) {
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", List.of("RENAME", "ADD_AFTER", "QUESTION", "NONE"));
        kind.put("description", "RENAME changes a step's name. ADD_AFTER inserts a new step "
                + "directly after an existing one. QUESTION means the user is asking about the "
                + "process rather than changing it. NONE means a change that is not supported.");

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("type", "string");
        target.put("description", "The existing step the change applies to.");
        if (!process.isEmpty()) {
            target.put("enum", process.steps());
        }

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", "string");
        value.put("description", "For RENAME, the step's new name. For ADD_AFTER, the new step's name.");

        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("type", "string");
        reason.put("description", "Only when kind is NONE: what to tell the user, in plain language.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kind", kind);
        properties.put("targetStepName", target);
        properties.put("value", value);
        properties.put("declineReason", reason);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        // Every field is required, including the ones a given kind ignores. Asking only for
        // "kind" is enough for the model to stop there: ornith 9b reliably returned
        // {"kind":"RENAME","targetStepName":"..."} with no new name, which is a half-formed
        // edit the harness has to reject. Requiring all four costs a few wasted tokens and
        // makes the reply usable. The controller ignores the fields that do not apply.
        schema.put("required", List.of("kind", "targetStepName", "value", "declineReason"));
        return schema;
    }
}
