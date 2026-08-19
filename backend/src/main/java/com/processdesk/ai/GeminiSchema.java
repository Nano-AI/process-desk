package com.processdesk.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates the schema in {@link IntentSchema} into the dialect Gemini accepts.
 *
 * <p>Gemini's {@code responseSchema} is a subset of OpenAPI 3.0 rather than JSON Schema, and
 * the differences are small but fatal: types are written in upper case, and an unrecognised
 * keyword is rejected with a 400 rather than ignored. Ollama takes plain JSON Schema.
 *
 * <p>Translating here keeps {@link IntentSchema} the single description of what a reply may
 * contain. The alternative — a second schema builder per provider — means the enum that
 * constrains the model can drift from the vocabulary the editor will accept, and that drift
 * is invisible until a model picks a value the harness then refuses.
 */
final class GeminiSchema {

    private GeminiSchema() {}

    /**
     * Keywords Gemini's schema subset recognises. Anything else is dropped rather than
     * passed through: {@code maxLength} on {@code declineReason} is the current example —
     * useful against a small model that writes a hundred words of reasoning, unsupported
     * here, and a 400 if sent. The word cap in the field's description carries it instead,
     * which a frontier model follows.
     */
    private static final Set<String> SUPPORTED =
            Set.of("type", "description", "enum", "properties", "required", "items", "nullable", "format");

    /** Converts a JSON Schema map into Gemini's {@code responseSchema} shape. */
    static Map<String, Object> from(Map<String, Object> jsonSchema) {
        Map<String, Object> out = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : jsonSchema.entrySet()) {
            String key = entry.getKey();
            if (!SUPPORTED.contains(key)) {
                continue;
            }
            Object value = entry.getValue();

            switch (key) {
                case "type" -> out.put("type", String.valueOf(value).toUpperCase());
                case "properties" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> properties = (Map<String, Object>) value;
                    Map<String, Object> converted = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> property : properties.entrySet()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) property.getValue();
                        converted.put(property.getKey(), from(child));
                    }
                    out.put("properties", converted);
                    // Fields are generated in the order given. Putting "kind" first means the
                    // choice of edit is made before the values that depend on it, rather than
                    // after — the same reason the prompt states the kinds before the examples.
                    out.put("propertyOrdering", new ArrayList<>(properties.keySet()));
                }
                case "items" -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> items = (Map<String, Object>) value;
                    out.put("items", from(items));
                }
                case "enum" -> {
                    // An empty enum is not a constraint, it is an impossibility: no value
                    // satisfies it, so the model is left with nothing legal to emit.
                    if (value instanceof List<?> values && !values.isEmpty()) {
                        out.put("enum", values);
                    }
                }
                default -> out.put(key, value);
            }
        }
        return out;
    }
}
