package com.processdesk.ai;

import com.processdesk.harness.AssetProjection;
import com.processdesk.harness.DecisionProjection;
import com.processdesk.harness.ProcessProjection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a hosted Gemini model.
 *
 * <p>What crosses this seam is the same thing that crosses it for the local model: a system
 * prompt and an {@link AssetProjection} — a process's step names, or a decision's rules.
 * Never the file, never an identifier, never the diagram. That is worth being precise about
 * here in a way it was not for Ollama, because here the projection leaves the machine: about
 * 150 tokens of business vocabulary, and nothing that would let a reader reconstruct the
 * asset.
 *
 * <p>Structurally this is {@link OllamaAiProvider} with a different transport. The prompts
 * are shared ({@link Prompts}), the reply handling is shared ({@link ModelReplies}), and the
 * same {@link IntentSchema} constrains generation — through Gemini's {@code responseSchema}
 * rather than Ollama's {@code format}, translated by {@link GeminiSchema}. Only the request
 * body and the error messages differ, which is the whole point of the provider seam.
 */
@Component
@ConditionalOnProperty(name = "processdesk.ai-provider", havingValue = "gemini")
public class GeminiAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiAiProvider.class);

    private static final String NO_KEY =
            "No Gemini API key is set. Put GEMINI_API_KEY in the .env file at the project root, then restart.";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final double temperature;
    private final int thinkingBudget;
    private final Duration timeout;

    /**
     * Set once this model has refused {@code thinkingConfig}, so the refusal is paid for
     * once rather than on every request. It matters more than it looks: a rejected call
     * still counts against the quota, and the free tier's daily allowance is small enough
     * that retrying every time would halve it.
     */
    private volatile boolean thinkingConfigRejected;

    private final CallBudget budget;

    public GeminiAiProvider(
            @Value("${processdesk.gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${processdesk.gemini.model:gemini-3.5-flash}") String model,
            @Value("${processdesk.gemini.api-key:}") String apiKey,
            @Value("${processdesk.gemini.temperature:0}") double temperature,
            @Value("${processdesk.gemini.thinking-budget:0}") int thinkingBudget,
            @Value("${processdesk.gemini.timeout-seconds:60}") int timeoutSeconds,
            @Value("${processdesk.gemini.daily-limit:0}") int dailyLimit) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.temperature = temperature;
        this.thinkingBudget = thinkingBudget;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.budget = new CallBudget(model, dailyLimit);

        if (this.apiKey.isEmpty()) {
            // Not a startup failure: the app is useful without the assistant, and a missing
            // key should be a message in the panel rather than a stack trace at boot.
            log.warn("processdesk.ai-provider=gemini but no API key is set. "
                    + "Set GEMINI_API_KEY in .env at the project root.");
        }
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public Map<String, Object> usage() {
        return budget.snapshot();
    }

    @Override
    public String explain(String question, AssetProjection asset) {
        if (asset.isEmpty()) {
            return asset instanceof DecisionProjection
                    ? "I can't see any decisions in this file."
                    : "I can't see any steps in this file. Open a process and ask me again.";
        }
        if (apiKey.isEmpty()) {
            return NO_KEY;
        }
        String system = asset instanceof DecisionProjection ? Prompts.EXPLAIN_DECISION : Prompts.EXPLAIN;
        try {
            return ModelReplies.trimToShape(generate(system, asset.render() + "\n\nQuestion: " + question, null));
        } catch (Exception e) {
            log.error("Gemini explain failed", e);
            return unreachable(e);
        }
    }

    @Override
    public EditIntent interpret(String request, ProcessProjection process) {
        // A clearly phrased request is already an intent; no inference needed, and no call.
        Optional<EditIntent> direct = RequestPatterns.match(request, process.steps());
        if (direct.isPresent()) {
            return direct.get();
        }
        if (process.isEmpty()) {
            return EditIntent.none("This file has no steps to change.");
        }
        if (apiKey.isEmpty()) {
            return EditIntent.none(NO_KEY);
        }

        try {
            String content = generate(Prompts.INTERPRET, process.render() + "\n\nRequest: " + request,
                    GeminiSchema.from(IntentSchema.forProcess(process)));
            return ModelReplies.toIntent(mapper.readTree(content));
        } catch (Exception e) {
            log.error("Gemini interpret failed", e);
            return EditIntent.none(unreachable(e));
        }
    }

    @Override
    public EditIntent interpretDecision(String request, DecisionProjection whole) {
        // Narrowed to the decisions this request is about, so the prose the model reads and
        // the enums it is constrained to describe the same rules.
        DecisionProjection decision = whole.focusedOn(request);
        if (decision.isEmpty()) {
            return EditIntent.none("This file has no decisions to change.");
        }
        if (apiKey.isEmpty()) {
            return EditIntent.none(NO_KEY);
        }

        try {
            String content = generate(Prompts.INTERPRET_DECISION, decision.render() + "\n\nRequest: " + request,
                    GeminiSchema.from(IntentSchema.forDecision(decision.vocabulary())));
            // No guard here any more. The model states what it expects the cell to contain
            // and the editor refuses if the cell holds anything else, which is exact where
            // the check it replaced was a guess about whether the request mentioned a value.
            return ModelReplies.toDecisionIntent(mapper.readTree(content));
        } catch (Exception e) {
            log.error("Gemini decision interpret failed", e);
            return EditIntent.none(unreachable(e));
        }
    }

    /**
     * One non-streaming turn. {@code schema} constrains generation to JSON when present;
     * without it the reply is prose.
     */
    private String generate(String system, String user, Map<String, Object> schema) throws Exception {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", temperature);
        if (schema != null) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseSchema", schema);
        }
        // Gemini thinks by default and pays for it in latency on a request whose whole job is
        // picking one of four kinds. A budget of 0 turns it off; -1 leaves it to the model.
        // Measured on gemini-3.5-flash: 1.3s and 81 thought tokens against 0.7s and none.
        if (thinkingBudget >= 0 && !thinkingConfigRejected) {
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", thinkingBudget));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", user)))));
        body.put("generationConfig", generationConfig);

        try {
            return post(body);
        } catch (GeminiException e) {
            // Which models accept thinkingConfig varies, and the refusal is an unhelpful
            // "Request contains an invalid argument". Measured across four models on one
            // key: gemini-3.5-flash accepts it; flash-lite and flash-latest answer 400.
            // Since the setting is an optimisation rather than a requirement, dropping it
            // and retrying is better than making the user find out which family they are on.
            if (e.status == 400 && generationConfig.containsKey("thinkingConfig")) {
                log.info("{} rejected thinkingConfig; dropping it for the rest of this run", model);
                thinkingConfigRejected = true;
                generationConfig.remove("thinkingConfig");
                return post(body);
            }
            throw e;
        }
    }

    private String post(Map<String, Object> body) throws Exception {
        return firstText(postJson(body));
    }

    private JsonNode postJson(Map<String, Object> body) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/models/" + model + ":generateContent"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                // In the header rather than the query string: a URL is logged by proxies and
                // kept in shell history, and a key in one of those is a key to rotate.
                .header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        // Counted before the status is known: a rejected request still spends the quota,
        // which is exactly why the thinkingConfig retry had to stop firing every time.
        budget.record();

        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            String detail = briefError(response.body());
            // Only the daily cap is worth recording. The per-minute cap is the one a tool loop
            // actually meets, and marking the day spent over it disables the assistant for
            // hours in exchange for a limit that clears in under a minute.
            if (response.statusCode() == 429 && CallBudget.saysDailyQuota(detail)) {
                budget.exhausted();
            }
            throw new GeminiException(response.statusCode(), detail);
        }
        return mapper.readTree(response.body());
    }

    @Override
    public boolean supportsTools() {
        return !apiKey.isEmpty();
    }

    /**
     * One turn of a tool-calling conversation, through Gemini's {@code functionDeclarations}.
     *
     * <p>Structurally the same as {@link OllamaAiProvider#nextTurn}, with three renames: tools
     * are declared under {@code functionDeclarations}, a call comes back as a {@code functionCall}
     * part rather than a {@code tool_calls} array, and a result goes back as a
     * {@code functionResponse} part on a user turn.
     *
     * <p>Each turn is a billed request. A loop that takes six turns costs six of the free tier's
     * daily allowance, so the ~500/day of flash-lite is closer to eighty conversations — the one
     * real cost of this architecture, and the reason the local provider is the one it is aimed at.
     */
    @Override
    public Tools.Turn nextTurn(String system, List<Tools.Message> conversation,
                               List<Tools.Spec> tools) {
        if (apiKey.isEmpty()) {
            throw new IllegalStateException(NO_KEY);
        }
        try {
            List<Map<String, Object>> contents = new java.util.ArrayList<>();
            for (Tools.Message message : conversation) {
                contents.add(toGemini(message));
            }

            Map<String, Object> generationConfig = new LinkedHashMap<>();
            generationConfig.put("temperature", temperature);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", system))));
            body.put("contents", contents);
            body.put("tools", List.of(Map.of("functionDeclarations",
                    tools.stream().map(GeminiAiProvider::toGemini).toList())));
            body.put("generationConfig", generationConfig);

            JsonNode candidate = postJson(body).path("candidates").path(0);
            StringBuilder text = new StringBuilder();
            List<Tools.Call> calls = new java.util.ArrayList<>();
            int index = 0;
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.has("functionCall")) {
                    JsonNode call = part.path("functionCall");
                    // A sibling of functionCall on the part, not a field inside it.
                    String thought = part.path("thoughtSignature").asText(null);
                    calls.add(new Tools.Call("call_" + index++, call.path("name").asText(""),
                            call.path("args"), thought));
                } else {
                    text.append(part.path("text").asText(""));
                }
            }
            return new Tools.Turn(text.toString(), calls);
        } catch (GeminiException e) {
            throw new IllegalStateException(unreachable(e), e);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini tool call failed: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> toGemini(Tools.Spec spec) {
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("name", spec.name());
        declaration.put("description", spec.description());
        Object properties = spec.parameters().get("properties");
        // A tool that takes nothing must declare nothing. Gemini answers 400 to a parameters
        // block with an empty properties map, where Ollama accepts it — the same difference in
        // strictness that GeminiSchema exists to absorb for response schemas.
        if (properties instanceof Map<?, ?> map && !map.isEmpty()) {
            declaration.put("parameters", GeminiSchema.from(spec.parameters()));
        }
        return declaration;
    }

    private static Map<String, Object> toGemini(Tools.Message message) {
        if ("tool".equals(message.role())) {
            return Map.of("role", "user", "parts", List.of(Map.of("functionResponse", Map.of(
                    "name", message.toolName() == null ? "" : message.toolName(),
                    "response", Map.of("result", message.content() == null ? "" : message.content())))));
        }
        if ("assistant".equals(message.role())) {
            List<Map<String, Object>> parts = new java.util.ArrayList<>();
            if (message.content() != null && !message.content().isBlank()) {
                parts.add(Map.of("text", message.content()));
            }
            for (Tools.Call call : message.calls()) {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("functionCall", Map.of(
                        "name", call.name(),
                        "args", call.arguments() == null ? Map.of() : call.arguments()));
                // Handed straight back, unread. Gemini 3 refuses the whole conversation without
                // it once a functionCall appears in history.
                if (call.thoughtSignature() != null && !call.thoughtSignature().isBlank()) {
                    part.put("thoughtSignature", call.thoughtSignature());
                }
                parts.add(part);
            }
            return Map.of("role", "model", "parts", parts);
        }
        return Map.of("role", "user", "parts", List.of(Map.of("text",
                message.content() == null ? "" : message.content())));
    }

    /** Pulls the reply text out, and says why there isn't one when there isn't one. */
    private static String firstText(JsonNode root) {
        JsonNode candidate = root.path("candidates").path(0);
        if (candidate.isMissingNode()) {
            String blocked = root.path("promptFeedback").path("blockReason").asText("");
            throw new IllegalStateException(blocked.isEmpty()
                    ? "Gemini returned no answer." : "Gemini declined to answer: " + blocked);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            text.append(part.path("text").asText(""));
        }
        if (text.isEmpty()) {
            // MAX_TOKENS with thinking on is the common way to land here: the budget went on
            // reasoning and none was left for the answer.
            throw new IllegalStateException("Gemini returned an empty answer (finish reason: "
                    + candidate.path("finishReason").asText("unknown") + ").");
        }
        return text.toString();
    }

    /** The API's own message, without the surrounding envelope. */
    private String briefError(String body) {
        try {
            String message = mapper.readTree(body).path("error").path("message").asText("");
            return message.isEmpty() ? body : message;
        } catch (Exception e) {
            return body;
        }
    }

    /**
     * Turns a transport failure into something a business user can act on.
     *
     * <p>The rate limit is called out by name because on the free tier it is the failure
     * people will actually meet, and "something went wrong" would send them looking for a
     * bug that isn't there.
     */
    private String unreachable(Exception e) {
        if (e instanceof GeminiException gemini) {
            return switch (gemini.status) {
                // Two different waits, and saying which one saves the user guessing whether to
                // try again in ten seconds or tomorrow. A tool loop spends 5-8 calls per
                // request, so on the free tier's 15/minute the first is the common one.
                case 429 -> CallBudget.saysDailyQuota(gemini.detail)
                        ? "This key has used up its free requests for today. It resets at "
                                + "midnight Pacific, or switch to the local model."
                        : "Too many requests in the last minute. Wait about a minute, then ask "
                                + "me again.";
                case 400 -> "Gemini rejected the request: " + gemini.detail;
                case 401, 403 -> "Gemini rejected the API key. Check GEMINI_API_KEY in .env.";
                // Worth its own case and worth quoting: models are retired, and a key issued
                // after the retirement gets a 404 rather than a deprecation warning. The
                // API's own wording ("no longer available to new users") is the whole
                // diagnosis, and swallowing it sends people looking for a bug in the code.
                case 404 -> "Gemini has no model called \"" + model + "\" for this key: "
                        + gemini.detail + " Set GEMINI_MODEL in .env to a current model.";
                // Google's own capacity, not this key and not this request. Worth naming so it
                // is not mistaken for a limit the user has hit or a bug in the change they made.
                case 500, 502, 503 -> "Gemini is busy right now. Wait a few seconds and ask me "
                        + "again — nothing was changed.";
                default -> "Gemini returned an error (" + gemini.status + "). Try again in a moment.";
            };
        }
        return "I couldn't reach Gemini. Check your connection, then ask me again.";
    }

    private static class GeminiException extends RuntimeException {
        private final int status;
        private final String detail;

        GeminiException(int status, String detail) {
            super("Gemini returned " + status + ": " + detail);
            this.status = status;
            this.detail = detail;
        }
    }
}
