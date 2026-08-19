package com.processdesk.ai;

import com.processdesk.harness.AssetProjection;
import com.processdesk.harness.DecisionProjection;
import com.processdesk.harness.ProcessProjection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Placeholder for the local model.
 *
 * <p>Answers are assembled from the projection so the rest of the system can be built and
 * demonstrated honestly, and intents come from two regular expressions rather than a model.
 *
 * <p>It recognises exactly the requests {@link RequestPatterns} matches — the ones that
 * need no inference. {@link OllamaAiProvider} handles those the same way and calls the
 * model only for the rest, so swapping providers changes what the system understands, not
 * how it behaves on requests both understand.
 */
@Component
@ConditionalOnProperty(name = "processdesk.ai-provider", havingValue = "dummy", matchIfMissing = true)
public class DummyAiProvider implements AiProvider {

    private static final Pattern CHANGE_FROM_TO = Pattern.compile(
            "(?:change|set|update)\\s+\"?(.+?)\"?\\s+from\\s+\"?(.+?)\"?\\s+to\\s+\"?(.+?)\"?\\s*[.!]?$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CHANGE_TO = Pattern.compile(
            "(?:change|set|update)\\s+\"?(.+?)\"?\\s+to\\s+\"?(.+?)\"?\\s*[.!]?$",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String name() {
        return "placeholder";
    }

    @Override
    public String explain(String question, AssetProjection asset) {
        if (asset instanceof DecisionProjection decision) {
            return explainDecision(decision);
        }
        List<String> steps = asset instanceof ProcessProjection process ? process.steps() : List.of();
        if (steps.isEmpty()) {
            return "I can't see any steps in this file. Open a process and ask me again.";
        }
        StringBuilder out = new StringBuilder("This process has " + steps.size() + " step"
                + (steps.size() == 1 ? "" : "s") + ", run in order.");
        steps.stream().limit(3).forEach(step -> out.append("\n- ").append(step));
        out.append("\n- (Placeholder answer — the local model isn't connected yet.)");
        return out.toString();
    }

    private String explainDecision(DecisionProjection decision) {
        if (decision.isEmpty()) {
            return "I can't see any decisions in this file.";
        }
        DecisionProjection.Decision first = decision.decisions().get(0);
        String needs = decision.inputs().isEmpty() ? "" :
                " It needs to know: " + String.join(", ", decision.inputs()) + ".";
        StringBuilder out = new StringBuilder("This decides \"" + first.name() + "\" using "
                + first.rules().size() + " rule" + (first.rules().size() == 1 ? "" : "s") + "." + needs);
        first.rules().stream().limit(2).forEach(rule -> out.append("\n- ").append(rule));
        out.append("\n- (Placeholder answer — the local model isn't connected yet.)");
        return out.toString();
    }

    /**
     * Decisions are not guessed at without a model: an edit is a coordinate now, and
     * inventing one from a regular expression would be a confident write to the wrong cell.
     */
    @Override
    public EditIntent interpretDecision(String request, DecisionProjection decision) {
        return EditIntent.none("Connect a model to change decision rules — "
                + "set AI_PROVIDER in .env to ollama or gemini.");
    }

    @Override
    public EditIntent interpret(String request, ProcessProjection process) {
        return RequestPatterns.match(request, process.steps())
                .orElseGet(() -> EditIntent.none(RequestPatterns.UNSUPPORTED));
    }
}
