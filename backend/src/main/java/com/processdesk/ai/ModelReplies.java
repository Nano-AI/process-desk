package com.processdesk.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Turns whatever a model actually replied into something the harness can act on.
 *
 * <p>Shared by every provider, because the failure modes are the model's rather than the
 * transport's: a missing field, a stray case, a blank string where a step name should be.
 * A reply that is half an edit has to land on {@code NONE} no matter who produced it, and
 * having one copy of that rule means a provider swap cannot quietly change it.
 */
final class ModelReplies {

    private ModelReplies() {}

    private static final int MAX_BULLETS = 3;

    /** Maps a schema-shaped process reply onto an intent. */
    static AiProvider.EditIntent toIntent(JsonNode reply) {
        String kind = text(reply, "kind");
        String target = text(reply, "targetStepName");
        String value = text(reply, "value");
        String reason = text(reply, "declineReason");

        return switch (kind == null ? "" : kind.trim().toUpperCase()) {
            case "RENAME" -> (target == null || value == null)
                    ? AiProvider.EditIntent.none("I understood a rename but not which step, or what to call it.")
                    : AiProvider.EditIntent.rename(target, value);
            case "ADD_AFTER" -> (target == null || value == null)
                    ? AiProvider.EditIntent.none("I understood a new step but not where it goes, or what to call it.")
                    : AiProvider.EditIntent.addAfter(target, value);
            default -> AiProvider.EditIntent.none(plainLanguage(reason, RequestPatterns.UNSUPPORTED));
        };
    }

    /**
     * Keeps the schema's own vocabulary out of what the user reads.
     *
     * <p>The enum values are names for our benefit, and a model asked for a plain-language
     * refusal will still reach for them: gemini-3.5-flash answered "delete the approval
     * step" with <em>"Only RENAME, ADD_AFTER, and QUESTION operations are allowed"</em>,
     * which is precisely the technical vocabulary this system exists to remove. The prompt
     * asks for plain language and mostly gets it; this is what makes it certain.
     *
     * <p>Falling back to a fixed sentence loses the model's specific reason, which is a real
     * cost. It is worth paying: a user who is told about ADD_AFTER has been handed a
     * question they cannot answer.
     */
    static String plainLanguage(String reason, String fallback) {
        if (reason == null || reason.isBlank()) {
            return fallback;
        }
        for (AiProvider.EditIntent.Kind kind : AiProvider.EditIntent.Kind.values()) {
            if (reason.contains(kind.name())) {
                return fallback;
            }
        }
        return reason;
    }

    /**
     * Maps a schema-shaped decision reply onto an intent.
     *
     * <p>One shape now. Every change to a rule — what it tests, what it produces, found by
     * either — is a coordinate and a value, so there is nothing left for the model to choose
     * wrongly between.
     */
    static AiProvider.EditIntent toDecisionIntent(JsonNode reply) {
        String kind = text(reply, "kind");
        String decision = text(reply, "decision");
        String column = text(reply, "column");
        String expect = text(reply, "expect");
        String to = text(reply, "to");
        String reason = text(reply, "declineReason");
        JsonNode ruleNode = reply.get("rule");
        int rule = ruleNode == null ? 0 : ruleNode.asInt(0);

        return switch (kind == null ? "" : kind.trim().toUpperCase()) {
            case "SET_CELL" -> (decision == null || rule < 1 || to == null)
                    ? AiProvider.EditIntent.none("I understood a change to a rule, but not which "
                            + "rule, or what to change it to.")
                    : AiProvider.EditIntent.setCell(decision, rule, column, expect, to);
            case "RENAME_DECISION" -> (decision == null || to == null)
                    ? AiProvider.EditIntent.none("I understood a rename but not which decision, or to what.")
                    : AiProvider.EditIntent.renameDecision(decision, to);
            case "QUESTION" -> AiProvider.EditIntent.question();
            default -> AiProvider.EditIntent.none(
                    plainLanguage(reason, "I can change a rule in a decision table, or rename a decision."));
        };
    }

    private static final int MAX_LISTED = 8;

    /**
     * The refusal shown when a request names a value the table does not hold.
     *
     * <p>Scoped to the column in question wherever one is known. The unscoped version listed
     * every condition in the file — fifty values across unrelated decisions, offering
     * "Employed" as a legal setting for a credit score — which is unreadable and, worse,
     * untrue. A user is owed the handful of values the column they named actually holds.
     */
    static String valueNotFound(com.processdesk.harness.DecisionProjection.Vocabulary vocabulary,
                                String column) {
        List<String> forColumn = vocabulary.conditionsFor(column);
        if (!forColumn.isEmpty()) {
            return "I couldn't find that value. " + column.trim() + " is set to "
                    + list(forColumn) + " in this table.";
        }
        if (!vocabulary.columns().isEmpty()) {
            return "I couldn't find that value. This decision looks at "
                    + list(vocabulary.columns()) + ".";
        }
        return "I couldn't find the value you asked me to change.";
    }

    /** Joins a few values readably, and says how many were left out rather than printing them. */
    private static String list(List<String> values) {
        if (values.size() <= MAX_LISTED) {
            return String.join(", ", values);
        }
        return String.join(", ", values.subList(0, MAX_LISTED))
                + " and " + (values.size() - MAX_LISTED) + " more";
    }

    /**
     * Holds the reply to the shape the prompt asked for: a lead sentence and at most three
     * bullets.
     *
     * <p>Measured on ornith 9b, the prompt is reliably obeyed on bullet *format* and
     * unreliably on bullet *count* — four or five where three were asked for. Counting is
     * something code does perfectly and a small model does approximately, so the prompt
     * requests the shape and this enforces it. A larger model needs it less, but a cap that
     * only sometimes applies is a cap nobody can rely on.
     *
     * <p>Constraining prose with a JSON schema was tried and is worse: three times slower,
     * and the model filled the array by echoing the step names back instead of summarising
     * them. Structured output is for constraining a *choice*, not for shaping prose.
     */
    static String trimToShape(String reply) {
        List<String> lines = reply.strip().lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
        if (lines.isEmpty()) {
            return "";
        }

        StringBuilder out = new StringBuilder();
        int bullets = 0;
        for (String line : lines) {
            boolean isBullet = line.startsWith("-") || line.startsWith("*") || line.startsWith("•");
            if (isBullet) {
                if (bullets == MAX_BULLETS) {
                    continue;
                }
                bullets++;
                out.append("\n- ").append(line.replaceFirst("^[-*•]\\s*", ""));
            } else if (out.isEmpty()) {
                out.append(line);
            }
            // Prose after the bullets have started is the model restating itself; drop it.
        }
        return out.toString().strip();
    }

    static String unquote(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
                ? trimmed.substring(1, trimmed.length() - 1).trim()
                : trimmed;
    }

    static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String asText = value.asText();
        return asText.isBlank() ? null : asText;
    }
}
