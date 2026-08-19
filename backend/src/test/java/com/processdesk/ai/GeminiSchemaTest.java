package com.processdesk.ai;

import com.processdesk.harness.DecisionProjection;
import com.processdesk.harness.ProcessProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The translation from JSON Schema to Gemini's OpenAPI subset.
 *
 * <p>Worth testing on its own because the differences are silent until they are a 400: a
 * lower-case type and an unrecognised keyword both look fine in the request body.
 */
class GeminiSchemaTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Map<String, Object> schema, String name) {
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    @Test
    void typesAreUpperCased() {
        Map<String, Object> schema = GeminiSchema.from(
                IntentSchema.forProcess(new ProcessProjection("P", List.of("Submit Request"))));

        assertEquals("OBJECT", schema.get("type"));
        assertEquals("STRING", property(schema, "kind").get("type"));
    }

    @Test
    void theStepNamesSurviveAsAnEnum() {
        Map<String, Object> schema = GeminiSchema.from(IntentSchema.forProcess(
                new ProcessProjection("P", List.of("Submit Request", "Manager Approval"))));

        assertEquals(List.of("Submit Request", "Manager Approval"),
                property(schema, "targetStepName").get("enum"));
        assertEquals(List.of("RENAME", "ADD_AFTER", "QUESTION", "NONE"),
                property(schema, "kind").get("enum"));
    }

    @Test
    void everyFieldStaysRequired() {
        Map<String, Object> schema = GeminiSchema.from(
                IntentSchema.forProcess(new ProcessProjection("P", List.of("Submit Request"))));

        assertEquals(List.of("kind", "targetStepName", "value", "declineReason"), schema.get("required"));
    }

    @Test
    void kindIsGeneratedFirst() {
        // Order matters: the choice of edit should be made before the values that depend on it.
        Map<String, Object> schema = GeminiSchema.from(
                IntentSchema.forProcess(new ProcessProjection("P", List.of("Submit Request"))));

        assertEquals("kind", ((List<?>) schema.get("propertyOrdering")).get(0));
    }

    @Test
    void anUnsupportedKeywordIsDroppedRatherThanSent() {
        // declineReason carries maxLength for Ollama. Gemini rejects the whole request over
        // a keyword it does not know, so it is dropped and the word cap in the description
        // does the work instead.
        Map<String, Object> schema = GeminiSchema.from(IntentSchema.forDecision(
                new DecisionProjection.Vocabulary(List.of("Member Tier"), List.of("\"Executive\""),
                        List.of("\"Manager Approval\""), List.of("Approval Route"))));

        Map<String, Object> reason = property(schema, "declineReason");
        assertFalse(reason.containsKey("maxLength"), "an unknown keyword is a 400, not a warning");
        assertTrue(String.valueOf(reason.get("description")).contains("20 words"),
                "the cap still has to be stated somewhere");
    }

    @Test
    void theDecisionVocabularyBecomesEnums() {
        Map<String, Object> schema = GeminiSchema.from(IntentSchema.forDecision(
                new DecisionProjection.Vocabulary(List.of("Member Tier"), List.of("\"Executive\""),
                        List.of("\"Manager Approval\""), List.of("Approval Route"))));

        // The addressable cells are the columns plus the result.
        assertEquals(List.of("Member Tier", "outcome"), property(schema, "column").get("enum"));
        assertEquals(List.of("Approval Route"), property(schema, "decision").get("enum"));
    }

    @Test
    void anEmptyVocabularyLeavesTheFieldUnconstrained() {
        // An empty enum is not a loose constraint, it is an impossible one — nothing
        // satisfies it, so the model has no legal token to emit.
        Map<String, Object> schema = GeminiSchema.from(IntentSchema.forDecision(
                new DecisionProjection.Vocabulary(List.of(), List.of(), List.of(), List.of())));

        assertFalse(property(schema, "decision").containsKey("enum"));
        assertEquals(List.of("outcome"), property(schema, "column").get("enum"),
                "with no columns, the result is still addressable");
    }

    @Test
    void theRuleNumberIsAnInteger() {
        Map<String, Object> schema = GeminiSchema.from(IntentSchema.forDecision(
                new DecisionProjection.Vocabulary(List.of("Member Tier"), List.of(),
                        List.of(), List.of("Approval Route"))));

        assertEquals("INTEGER", property(schema, "rule").get("type"));
    }

    @Test
    void nestedPropertiesAreConvertedToo() {
        Map<String, Object> nested = Map.of(
                "type", "object",
                "properties", Map.of("inner", Map.of("type", "array",
                        "items", Map.of("type", "string", "maxLength", 5))));

        Map<String, Object> converted = GeminiSchema.from(nested);
        Map<String, Object> inner = property(converted, "inner");

        assertEquals("ARRAY", inner.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) inner.get("items");
        assertEquals("STRING", items.get("type"));
        assertFalse(items.containsKey("maxLength"), "the drop applies at every level");
    }
}
