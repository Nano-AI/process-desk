package com.processdesk.ai;

import com.processdesk.harness.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The assistant's two endpoints.
 *
 * <p>The model sees only a {@link ProcessProjection}; step names are turned into
 * identifiers by {@link StepResolver}, and edits are performed by {@link ProcessEditor}.
 * A proposal that fails any gate is returned without its file, so the UI cannot offer to
 * apply a change that would break the process.
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final AiProvider ai;
    private final StepResolver resolver;
    private final ProcessEditor editor;
    private final DecisionEditor decisionEditor;
    private final ValidationGates gates;
    private final DecisionGates decisionGates;
    private final AuditLog audit;
    private final DecisionToolLoop toolLoop;
    private final boolean toolLoopEnabled;

    public AiController(AiProvider ai, StepResolver resolver, ProcessEditor editor,
                        DecisionEditor decisionEditor, ValidationGates gates,
                        DecisionGates decisionGates, AuditLog audit, DecisionToolLoop toolLoop,
                        @org.springframework.beans.factory.annotation.Value(
                                "${processdesk.tool-loop.enabled:true}") boolean toolLoopEnabled) {
        this.ai = ai;
        this.resolver = resolver;
        this.editor = editor;
        this.decisionEditor = decisionEditor;
        this.gates = gates;
        this.decisionGates = decisionGates;
        this.audit = audit;
        this.toolLoop = toolLoop;
        this.toolLoopEnabled = toolLoopEnabled;
    }

    public record ChatRequest(String message, String xml) {}

    public record ChatResponse(String answer, String provider) {}

    public record ProposeResponse(boolean ok, String explanation, String proposedXml,
                                  List<GateResult> gates, String message) {

        static ProposeResponse declined(String message) {
            return new ProposeResponse(false, null, null, List.of(), message);
        }
    }

    /** Either an answer, or a proposed change. Never both. */
    public record AskResponse(String answer, ProposeResponse proposal, String provider) {}

    /**
     * One entry point for anything the user types.
     *
     * <p>Whether a sentence is a question or a change is a judgement about language, which
     * is the model's job — not something the browser should guess at with its own pattern
     * list. Splitting that decision across two endpoints meant "stick a quality check after
     * submit" was answered as a question instead of being offered as an edit.
     */
    @PostMapping("/ask")
    public AskResponse ask(@RequestBody ChatRequest request) {
        String xml = request.xml() == null ? "" : request.xml();

        // Decision models take a different route throughout: their own interpreter, their
        // own editor, and gates built on the DMN compiler rather than the BPMN parser.
        if (DecisionProjection.looksLikeDecisionModel(xml)) {
            // A provider that can call tools gets to look at the file, change it, check its own
            // work and fix what it broke. That is a different protocol, not a different harness:
            // the same editor writes and the same gates decide.
            if (toolLoopEnabled && toolLoop.available()) {
                return viaToolLoop(request.message(), xml);
            }

            DecisionProjection decision = DecisionProjection.of(xml);

            // Whether a sentence is a question or an instruction is a judgement about
            // language, so the model makes it — the same way it already does for processes.
            // A regex made it here until "I want to change Income Risk score for under 18's
            // to -150" was classified as a question because it opens with a pronoun, and the
            // model's correct reading of it was discarded before anyone saw it.
            AiProvider.EditIntent intent = ai.interpretDecision(request.message(), decision);
            if (intent.kind() == AiProvider.EditIntent.Kind.QUESTION) {
                return new AskResponse(ai.explain(request.message(), decision), null, ai.name());
            }
            if (!intent.isDecisionEdit()) {
                return new AskResponse(intent.declineReason() == null
                        ? "I couldn't work out which rule you meant." : intent.declineReason(), null, ai.name());
            }
            return new AskResponse(null, applyDecisionIntent(request.message(), xml, intent), ai.name());
        }

        ProcessProjection process = ProcessProjection.of(xml);
        AiProvider.EditIntent intent = ai.interpret(request.message(), process);

        return switch (intent.kind()) {
            case RENAME, ADD_AFTER -> new AskResponse(null, applyIntent(request.message(), xml, intent), ai.name());
            case QUESTION -> new AskResponse(ai.explain(request.message(), process), null, ai.name());
            default -> new AskResponse(intent.declineReason() == null
                    ? RequestPatterns.UNSUPPORTED : intent.declineReason(), null, ai.name());
        };
    }

    /**
     * The same answer as {@link #ask}, but reporting each step as it happens.
     *
     * <p>Newline-delimited JSON rather than server-sent events, because the request carries the
     * file and SSE is GET-only. One {@code step} object per tool call as it starts, then one
     * {@code result} holding exactly what {@code /ask} would have returned — so a client that
     * cannot stream loses the progress and nothing else.
     *
     * <p>Steps are never announced in advance. The loop does not know its own next move, and a
     * predicted list is what left the old panel showing rows that never ran.
     */
    @PostMapping(value = "/ask/stream", produces = "application/x-ndjson")
    public org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
            askStream(@RequestBody ChatRequest request) {
        String xml = request.xml() == null ? "" : request.xml();
        boolean streamable = DecisionProjection.looksLikeDecisionModel(xml)
                && toolLoopEnabled && toolLoop.available();

        return out -> {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            // Flushed per line: buffering the whole reply would deliver every step at the end,
            // which is the same as not having them.
            java.util.function.BiConsumer<String, Object> emit = (type, payload) -> {
                try {
                    Map<String, Object> envelope = new java.util.LinkedHashMap<>();
                    envelope.put("type", type);
                    envelope.put("payload", payload);
                    out.write(mapper.writeValueAsBytes(envelope));
                    out.write('\n');
                    out.flush();
                } catch (java.io.IOException e) {
                    // The browser navigated away mid-request. Nothing to salvage and nothing
                    // to report to: the work is abandoned, not failed.
                    throw new java.io.UncheckedIOException(e);
                }
            };

            try {
                if (!streamable) {
                    emit.accept("result", ask(request));
                    return;
                }
                DecisionToolLoop.Outcome outcome =
                        toolLoop.run(request.message(), xml, step -> emit.accept("step", step));
                emit.accept("result", fromOutcome(request.message(), outcome));
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        };
    }

    /**
     * Runs the tool loop and reports what it concluded.
     *
     * <p>The two outcomes are the two the one-shot path already had — an answer, or a gated
     * proposal — so nothing downstream of here needs to know which protocol produced it.
     */
    private AskResponse viaToolLoop(String message, String xml) {
        return fromOutcome(message, toolLoop.run(message, xml));
    }

    /** Audits the run and shapes it as the two outcomes the UI already understands. */
    private AskResponse fromOutcome(String message, DecisionToolLoop.Outcome outcome) {
        audit.record("tool-loop", Map.of(
                "provider", ai.name(),
                "request", String.valueOf(message),
                "turns", outcome.turns(),
                "edits", outcome.edits(),
                "passed", outcome.ok()));

        if (outcome.isAnswer()) {
            return new AskResponse(outcome.answer(), null, ai.name());
        }
        return new AskResponse(null, new ProposeResponse(outcome.ok(),
                outcome.ok() ? outcome.message() : null, outcome.proposedXml(),
                outcome.gates(), outcome.ok() ? null : outcome.message()), ai.name());
    }

    @PostMapping("/chat")
    public ChatResponse chat(@RequestBody ChatRequest request) {
        String xml = request.xml() == null ? "" : request.xml();
        return new ChatResponse(ai.explain(request.message(), projectionOf(xml)), ai.name());
    }

    /**
     * Shows the model the right view of whichever kind of file is open.
     *
     * <p>A decision model has no steps, so projecting it as a process yields nothing and
     * the assistant answers "I can't see any steps in this file" to a perfectly good
     * question about a decision table.
     */
    private AssetProjection projectionOf(String xml) {
        return DecisionProjection.looksLikeDecisionModel(xml)
                ? DecisionProjection.of(xml)
                : ProcessProjection.of(xml);
    }

    @PostMapping("/propose")
    public ProposeResponse propose(@RequestBody ChatRequest request) {
        String xml = request.xml() == null ? "" : request.xml();
        AiProvider.EditIntent intent = ai.interpret(request.message(), ProcessProjection.of(xml));
        if (intent.kind() != AiProvider.EditIntent.Kind.RENAME
                && intent.kind() != AiProvider.EditIntent.Kind.ADD_AFTER) {
            return ProposeResponse.declined(
                    intent.declineReason() == null ? RequestPatterns.UNSUPPORTED : intent.declineReason());
        }
        return applyIntent(request.message(), xml, intent);
    }

    /** Resolves the step, performs the edit, and runs the gates. */
    private ProposeResponse applyIntent(String message, String xml, AiProvider.EditIntent intent) {
        BpmnDocument doc;
        try {
            doc = BpmnDocument.parse(xml);
        } catch (Exception e) {
            return ProposeResponse.declined("I couldn't read this process file.");
        }

        StepResolver.Resolution target = resolver.resolve(doc, intent.targetStepName());
        if (!target.resolved()) {
            return ProposeResponse.declined(describeMiss(intent.targetStepName(), target));
        }

        try {
            ProcessEditor.EditResult result = switch (intent.kind()) {
                case RENAME -> editor.rename(xml, target.id(), intent.value());
                case ADD_AFTER -> editor.addTaskAfter(xml, target.id(), intent.value());
                default -> throw new IllegalStateException("not a process edit: " + intent.kind());
            };

            List<GateResult> checked = gates.run(result.xml());
            boolean ok = ValidationGates.allPassed(checked);

            audit.record("proposed", Map.of(
                    "provider", ai.name(),
                    "request", message,
                    "intent", intent.kind().name(),
                    "requestedStep", String.valueOf(intent.targetStepName()),
                    "resolvedStep", target.name(),
                    "exactMatch", target.exact(),
                    "value", String.valueOf(intent.value()),
                    "passed", ok,
                    "gates", checked));

            return new ProposeResponse(ok, result.summary(), ok ? result.xml() : null, checked,
                    ok ? null : whyNot(checked, "This change wouldn't be safe to make, so I haven't offered it."));
        } catch (Exception e) {
            audit.record("propose-failed", Map.of(
                    "request", message,
                    "error", String.valueOf(e.getMessage())));
            return ProposeResponse.declined("I couldn't make that change safely. Nothing was modified.");
        }
    }

    /** Performs a decision edit and runs the decision gates over the result. */
    private ProposeResponse applyDecisionIntent(String message, String xml, AiProvider.EditIntent intent) {
        try {
            DecisionEditor.EditResult result = switch (intent.kind()) {
                case SET_CELL -> decisionEditor.setCell(xml, intent.cell().decision(),
                        intent.cell().rule(), intent.cell().column(),
                        intent.cell().expect(), intent.value());
                case RENAME_DECISION -> decisionEditor.renameDecision(
                        xml, intent.targetStepName(), intent.value());
                default -> throw new IllegalStateException("not a decision edit: " + intent.kind());
            };

            List<GateResult> checked = decisionGates.run(xml, result.xml());
            boolean ok = DecisionGates.allPassed(checked);

            audit.record("proposed-decision", Map.of(
                    "provider", ai.name(),
                    "request", message,
                    "intent", intent.kind().name(),
                    "cell", String.valueOf(intent.cell()),
                    "to", String.valueOf(intent.value()),
                    "cellsChanged", result.cellsChanged(),
                    "passed", ok,
                    "gates", checked));

            return new ProposeResponse(ok, result.summary(), ok ? result.xml() : null, checked,
                    ok ? null : whyNot(checked, "That change would leave the decision model in a state Kogito can't use."));
        } catch (IllegalArgumentException e) {
            // The editor could not find what the request named — say exactly that.
            return ProposeResponse.declined(e.getMessage());
        } catch (Exception e) {
            audit.record("propose-decision-failed", Map.of("request", message, "error", String.valueOf(e.getMessage())));
            return ProposeResponse.declined("I couldn't make that change safely. Nothing was modified.");
        }
    }

    /**
     * The reason a proposal was withheld, in the words of the check that withheld it.
     *
     * <p>A gate goes to the trouble of explaining itself — "two rules can both apply, so the
     * table wouldn't say which answer wins; check the neighbouring rule" — and replacing that
     * with "this wouldn't be safe" throws away the only part the user can act on.
     */
    private static String whyNot(List<GateResult> gates, String fallback) {
        return gates.stream().filter(g -> !g.ok()).map(GateResult::detail)
                .filter(detail -> detail != null && !detail.isBlank())
                .findFirst().orElse(fallback);
    }

    /**
     * Names the problem and offers the way out. An ambiguous name and an unknown name need
     * different wording: one asks the user to choose, the other tells them what exists.
     */
    private String describeMiss(String requested, StepResolver.Resolution resolution) {
        List<String> options = resolution.options();
        if (options.isEmpty()) {
            return "I couldn't find a step called \"" + requested + "\", and this file has no steps to change.";
        }
        String list = options.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));
        if (resolution.ambiguous()) {
            return "\"" + requested + "\" could mean more than one step: " + list + ". Which one did you mean?";
        }
        return "I couldn't find a step called \"" + requested + "\". This process has: " + list + ".";
    }
}
