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
 * Runs the local model through Ollama.
 *
 * <p>What the model receives is deliberately small: a system prompt and an
 * {@link AssetProjection}, never a file. What it may reply with is deliberately narrow:
 * {@link IntentSchema} is passed as Ollama's {@code format}, so generation is constrained
 * to the schema and the step names in the open file. Naming a step that does not exist is
 * not an error to be caught afterwards — it is an answer the decoder cannot produce.
 *
 * <p>Nothing here writes a file or decides an identifier. Those remain the harness's job,
 * so a wrong answer costs a rejected proposal rather than a damaged process.
 *
 * <p>Prompts, reply handling and schema live outside this class ({@link Prompts},
 * {@link ModelReplies}, {@link IntentSchema}) because {@link GeminiAiProvider} uses the same
 * ones. Only the transport differs.
 */
@Component
@ConditionalOnProperty(name = "processdesk.ai-provider", havingValue = "ollama")
public class OllamaAiProvider implements AiProvider {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiProvider.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final String baseUrl;
    private final String model;
    private final int numCtx;
    private final double temperature;
    private final String keepAlive;
    private final boolean think;
    private final int numPredict;
    private final int numPredictProse;
    private final Duration timeout;
    // What the machine actually spent. On a CPU-only box prefill is the budget — a couple of
    // thousand tokens of preamble at 15-25 tok/s is most of a minute before a token comes out,
    // which is why this is counted rather than estimated.
    private final java.util.concurrent.atomic.AtomicLong promptTokens =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong promptNanos =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong replyTokens =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong replyNanos =
            new java.util.concurrent.atomic.AtomicLong();

    public OllamaAiProvider(
            @Value("${processdesk.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${processdesk.ollama.model:gpt-oss:20b}") String model,
            @Value("${processdesk.ollama.num-ctx:8192}") int numCtx,
            @Value("${processdesk.ollama.temperature:0.1}") double temperature,
            @Value("${processdesk.ollama.keep-alive:30m}") String keepAlive,
            @Value("${processdesk.ollama.think:false}") boolean think,
            @Value("${processdesk.ollama.num-predict:512}") int numPredict,
            @Value("${processdesk.ollama.num-predict-prose:200}") int numPredictProse,
            @Value("${processdesk.ollama.timeout-seconds:600}") int timeoutSeconds) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        this.model = model;
        this.numCtx = numCtx;
        this.temperature = temperature;
        this.keepAlive = keepAlive;
        this.think = think;
        this.numPredict = numPredict;
        this.numPredictProse = numPredictProse;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public String explain(String question, AssetProjection asset) {
        if (asset.isEmpty()) {
            return asset instanceof DecisionProjection
                    ? "I can't see any decisions in this file."
                    : "I can't see any steps in this file. Open a process and ask me again.";
        }
        String system = asset instanceof DecisionProjection ? Prompts.EXPLAIN_DECISION : Prompts.EXPLAIN;
        String prompt = asset.render() + "\n\nQuestion: " + question;
        try {
            return ModelReplies.trimToShape(chat(system, prompt, null));
        } catch (Exception e) {
            log.error("Ollama explain failed", e);
            return "I couldn't reach the local model. Check that Ollama is running, then ask me again.";
        }
    }

    @Override
    public EditIntent interpret(String request, ProcessProjection process) {
        // A clearly phrased request is already an intent; no inference needed.
        Optional<EditIntent> direct = RequestPatterns.match(request, process.steps());
        if (direct.isPresent()) {
            return direct.get();
        }
        if (process.isEmpty()) {
            return EditIntent.none("This file has no steps to change.");
        }

        String prompt = process.render() + "\n\nRequest: " + request;
        try {
            String content = chat(Prompts.INTERPRET, prompt, IntentSchema.forProcess(process));
            return ModelReplies.toIntent(mapper.readTree(content));
        } catch (Exception e) {
            log.error("Ollama interpret failed", e);
            return EditIntent.none("I couldn't reach the local model. Check that Ollama is running, then try again.");
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
        String prompt = decision.render() + "\n\nRequest: " + request;
        try {
            String content = chat(Prompts.INTERPRET_DECISION, prompt,
                    IntentSchema.forDecision(decision.vocabulary()));
            EditIntent intent = ModelReplies.toDecisionIntent(mapper.readTree(content));
            return intent;
        } catch (Exception e) {
            log.error("Ollama decision interpret failed", e);
            return EditIntent.none("I couldn't reach the local model. Check that Ollama is running, then try again.");
        }
    }

    @Override
    public boolean supportsTools() {
        return true;
    }

    /**
     * One constrained turn, three legal answers, no tool schemas loaded yet.
     *
     * <p>About 200 tokens of prefill against the 900 the write tools cost to advertise, and a
     * three-value enum under constrained decoding is the easiest classification a model can be
     * asked to make — it cannot answer anything else. A failure here costs nothing: UNKNOWN
     * means "load everything", which is what the loop did before this existed.
     */
    @Override
    public Reading classify(String request) {
        if (request == null || request.isBlank()) {
            return Reading.UNKNOWN;
        }
        try {
            String content = chat(Prompts.ROUTE, request, ROUTE_SCHEMA);
            String reading = mapper.readTree(content).path("reading").asText("").trim()
                    .toUpperCase(java.util.Locale.ROOT);
            return switch (reading) {
                case "QUESTION" -> Reading.QUESTION;
                case "EDIT" -> Reading.EDIT;
                default -> Reading.UNKNOWN;
            };
        } catch (Exception e) {
            // Never fatal. A router that cannot answer has to fall back to offering everything,
            // not to failing the request it was only meant to make cheaper.
            log.debug("routing failed; offering every tool", e);
            return Reading.UNKNOWN;
        }
    }

    private static final Map<String, Object> ROUTE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of("reading", Map.of(
                    "type", "string",
                    "enum", List.of("QUESTION", "EDIT"))),
            "required", List.of("reading"));

    /**
     * One turn of a tool-calling conversation, through Ollama's {@code tools} parameter.
     *
     * <p>The whole conversation is resent each turn, which is how the API works and is not as
     * wasteful as it looks: Ollama keeps the KV cache for a prefix it has already seen, so only
     * the newest messages are actually prefilled. That is worth knowing because it is the
     * difference between a turn costing about a second and costing ten — on the M4, prefill runs
     * at roughly 126 tokens/second, so re-reading a whole conversation from scratch would
     * dominate everything else the loop does.
     */
    @Override
    public Tools.Turn nextTurn(String system, List<Tools.Message> conversation,
                               List<Tools.Spec> tools) {
        try {
            List<Map<String, Object>> messages = new java.util.ArrayList<>();
            messages.add(Map.of("role", "system", "content", system));
            for (Tools.Message message : conversation) {
                messages.add(toOllama(message));
            }

            Map<String, Object> options = new LinkedHashMap<>();
            options.put("temperature", temperature);
            options.put("num_ctx", numCtx);
            options.put("num_predict", numPredict);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("tools", tools.stream().map(OllamaAiProvider::toOllama).toList());
            body.put("stream", false);
            body.put("keep_alive", keepAlive);
            body.put("think", think);
            body.put("options", options);

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Ollama returned " + response.statusCode() + ": " + response.body());
            }

            JsonNode body2 = mapper.readTree(response.body());
            record(body2);
            JsonNode message = body2.path("message");
            List<Tools.Call> calls = new java.util.ArrayList<>();
            int index = 0;
            for (JsonNode call : message.path("tool_calls")) {
                JsonNode function = call.path("function");
                calls.add(new Tools.Call("call_" + index++, function.path("name").asText(""),
                        function.path("arguments")));
            }
            return new Tools.Turn(message.path("content").asText(""), calls);
        } catch (Exception e) {
            throw new IllegalStateException("Ollama tool call failed: " + e.getMessage(), e);
        }
    }

    /** Ollama reports what each call cost. Nothing else here has to estimate it. */
    private void record(JsonNode body) {
        promptTokens.addAndGet(body.path("prompt_eval_count").asLong(0));
        promptNanos.addAndGet(body.path("prompt_eval_duration").asLong(0));
        replyTokens.addAndGet(body.path("eval_count").asLong(0));
        replyNanos.addAndGet(body.path("eval_duration").asLong(0));
    }

    /**
     * What has been spent, in the only currency this deployment has: time.
     *
     * <p>A hosted provider reports a quota. A local one on a CPU reports prefill, because that
     * is the resource that runs out — the tool loop pays for its system prompt and tool schemas
     * on every cold turn, and whether that is four seconds or forty decides whether this is a
     * tool or a wait. Reported through the same {@code usage()} seam so {@code /api/health}
     * needs no special case.
     */
    @Override
    public Map<String, Object> usage() {
        long prompt = promptTokens.get();
        long reply = replyTokens.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("promptTokens", prompt);
        out.put("promptSeconds", round(promptNanos.get()));
        out.put("prefillTokensPerSecond", rate(prompt, promptNanos.get()));
        out.put("replyTokens", reply);
        out.put("replySeconds", round(replyNanos.get()));
        out.put("generationTokensPerSecond", rate(reply, replyNanos.get()));
        return out;
    }

    /** Prefill and generation totals so far, for a caller measuring one request at a time. */
    public long[] spent() {
        return new long[] {promptTokens.get(), promptNanos.get(), replyTokens.get(), replyNanos.get()};
    }

    private static double round(long nanos) {
        return Math.round(nanos / 1_000_000.0) / 1000.0;
    }

    private static double rate(long tokens, long nanos) {
        return nanos <= 0 ? 0 : Math.round(tokens * 1_000_000_000.0 / nanos * 10) / 10.0;
    }

    private static Map<String, Object> toOllama(Tools.Spec spec) {
        return Map.of("type", "function", "function", Map.of(
                "name", spec.name(),
                "description", spec.description(),
                "parameters", spec.parameters()));
    }

    private Map<String, Object> toOllama(Tools.Message message) {
        Map<String, Object> out = new LinkedHashMap<>();
        // Ollama names the tool role's producer "tool_name"; without it a model with several
        // results in flight cannot tell which answer belongs to which call.
        out.put("role", "tool".equals(message.role()) ? "tool" : message.role());
        out.put("content", message.content() == null ? "" : message.content());
        if (message.toolName() != null) {
            out.put("tool_name", message.toolName());
        }
        if (message.calls() != null && !message.calls().isEmpty()) {
            out.put("tool_calls", message.calls().stream()
                    .map(call -> Map.of("function", Map.of(
                            "name", call.name(),
                            "arguments", call.arguments() == null
                                    ? mapper.createObjectNode() : call.arguments())))
                    .toList());
        }
        return out;
    }

    /** One non-streaming chat turn. {@code schema} constrains generation when present. */
    private String chat(String system, String user, Map<String, Object> schema) throws Exception {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temperature);
        options.put("num_ctx", numCtx);
        // Prose gets the tighter cap. A schema already bounds how long a reply can be; a
        // question like "explain this file" bounds nothing, and Ollama's default is unlimited.
        options.put("num_predict", schema == null ? numPredictProse : numPredict);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        body.put("stream", false);
        body.put("keep_alive", keepAlive);
        // ornith is a reasoning model. Its private reasoning is tokens we pay for and never
        // read: measured on this machine, leaving it on roughly tripled the time to a
        // decision (25s against 9s) without changing the answer. The schema is the thinking.
        body.put("think", think);
        body.put("options", options);
        if (schema != null) {
            body.put("format", schema);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/api/chat"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Ollama returned " + response.statusCode() + ": " + response.body());
        }
        JsonNode parsed = mapper.readTree(response.body());
        record(parsed);
        return parsed.path("message").path("content").asText("");
    }
}
