package com.processdesk.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * The vocabulary of a tool-calling conversation, in the one shape both providers translate to.
 *
 * <p>This exists because the one-shot protocol it replaces was the thing limiting the model,
 * not the harness underneath it. A single call showed a guessed-at projection and demanded one
 * intent back, so a request naming something ambiguous ("the DTI", which is both a decision and
 * a column), or needing two edits, or touching a decision the projection renders blank, had no
 * way to succeed — the model could not ask, and could not act twice.
 *
 * <p>Ollama calls these {@code tools} and Gemini calls them {@code functionDeclarations}; the
 * difference is a field name. Keeping the shape here means {@link DecisionToolLoop} never learns
 * which provider it is talking to, exactly as {@link Prompts} and {@link ModelReplies} already
 * ensure for the one-shot path.
 */
public final class Tools {

    private Tools() {
    }

    /**
     * A tool the model may call: its name, what it is for, and a JSON Schema for its arguments.
     *
     * <p>The description is read by the model and nobody else, so it is written for that
     * reader — what the tool does and when to reach for it, not how it is implemented.
     */
    public record Spec(String name, String description, Map<String, Object> parameters) {}

    /**
     * A call the model asked for. {@code arguments} is whatever it filled the schema with.
     *
     * <p>{@code thoughtSignature} is an opaque token some providers attach to a call and require
     * back when the call is replayed in history. Gemini 3 rejects a conversation without it —
     * "Function call is missing a thought_signature in functionCall parts" — so it has to survive
     * the round trip even though nothing here can read it. Null for providers that do not use one.
     */
    public record Call(String id, String name, JsonNode arguments, String thoughtSignature) {

        public Call(String id, String name, JsonNode arguments) {
            this(id, name, arguments, null);
        }

        /**
         * An argument as text, or empty. Missing and null read the same to a caller.
         *
         * <p>Unwraps one layer of double encoding. Models sometimes fill a string argument with
         * the whole arguments object again — gpt-oss:20b returned
         * {@code {"summary": "{\"summary\": \"Added a new category…\"}"}} — and this value is
         * shown to the user, so the raw JSON would be printed in the panel as the description
         * of the change they are being asked to approve.
         */
        public String text(String field) {
            JsonNode value = arguments == null ? null : arguments.get(field);
            if (value == null || value.isNull()) {
                return "";
            }
            if (value.isObject()) {
                JsonNode inner = value.get(field);
                return inner == null || inner.isNull() ? "" : inner.asText("");
            }
            String text = value.asText("");
            // Repeatedly, because the wrapping is not always one layer deep — gpt-oss:20b was
            // measured returning the object nested inside itself twice. Bounded, so a value
            // that genuinely is JSON about itself cannot spin.
            for (int depth = 0; depth < 4; depth++) {
                String unwrapped = unwrapOnce(text, field);
                if (unwrapped == null) {
                    return text;
                }
                text = unwrapped;
            }
            return text;
        }

        /** One layer of {@code {"field": "…"}} removed, or null when there is none to remove. */
        private static String unwrapOnce(String text, String field) {
            String trimmed = text == null ? "" : text.trim();
            if (!trimmed.startsWith("{") || !trimmed.contains("\"" + field + "\"")) {
                return null;
            }
            try {
                JsonNode inner = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(trimmed).get(field);
                return inner != null && inner.isTextual() ? inner.asText() : null;
            } catch (Exception e) {
                // Not JSON after all, so it is the answer and belongs to the user as written.
                return null;
            }
        }

        /**
         * An argument as a list of strings.
         *
         * <p>A single value is accepted where a list is expected, because a one-column table
         * invites exactly that mistake and rejecting it would be pedantry: the model said what
         * it meant, in a shape one element away from the schema.
         */
        public java.util.List<String> strings(String field) {
            JsonNode value = arguments == null ? null : arguments.get(field);
            if (value == null || value.isNull()) {
                return java.util.List.of();
            }
            if (!value.isArray()) {
                return java.util.List.of(value.asText(""));
            }
            java.util.List<String> out = new java.util.ArrayList<>();
            value.forEach(item -> out.add(item.asText("")));
            return out;
        }

        /**
         * An argument as a whole number.
         *
         * <p>Tolerant of a number arriving as a string, because it does: a model that has been
         * told a rule number is an integer will still sometimes emit {@code "3"}, and refusing
         * that would be a failure of parsing dressed up as a failure of understanding.
         */
        public int number(String field) {
            JsonNode value = arguments == null ? null : arguments.get(field);
            if (value == null || value.isNull()) {
                return -1;
            }
            if (value.isNumber()) {
                return value.asInt();
            }
            try {
                return Integer.parseInt(value.asText("").trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    /**
     * One reply from the model: prose, tool calls, or both.
     *
     * <p>Both is legal and worth allowing. Small models narrate before they act, and discarding
     * a call because it arrived with a sentence attached would throw away the useful half.
     */
    public record Turn(String text, List<Call> calls) {

        public boolean hasCalls() {
            return calls != null && !calls.isEmpty();
        }
    }

    /**
     * A message in the running conversation.
     *
     * <p>The conversation is kept and replayed rather than summarised, because a tool result the
     * model can no longer see is a tool call it will make again.
     */
    public record Message(String role, String content, List<Call> calls, String toolName) {

        public static Message user(String content) {
            return new Message("user", content, List.of(), null);
        }

        public static Message assistant(String content, List<Call> calls) {
            return new Message("assistant", content == null ? "" : content,
                    calls == null ? List.of() : calls, null);
        }

        /** The result of a tool call, labelled with the tool that produced it. */
        public static Message tool(String toolName, String content) {
            return new Message("tool", content, List.of(), toolName);
        }
    }
}
