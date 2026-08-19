package com.processdesk.kogito;

import org.drools.io.ByteArrayResource;
import org.kie.api.io.Resource;
import org.kie.dmn.api.core.*;
import org.kie.dmn.core.internal.utils.DMNRuntimeBuilder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates DMN decisions on Kogito's DMN engine.
 *
 * <p>Decision logic is never reimplemented here. The DMN file is handed to
 * {@link DMNRuntime} and Kogito evaluates it, so what this application reports is
 * what the deployed decision service would produce for the same inputs.
 */
public class DecisionRuntime {

    public record DecisionOutcome(String decisionName, Object result, boolean succeeded, List<String> messages) {}

    public record EvaluationResult(String modelName, String modelNamespace,
                                   List<DecisionOutcome> decisions, Map<String, Object> allValues) {}

    /** Loads a DMN model and evaluates every decision in it against the given inputs. */
    public EvaluationResult evaluateAll(String dmnXml, Map<String, Object> inputs) {
        DMNRuntime runtime = buildRuntime(dmnXml);
        DMNModel model = runtime.getModels().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This file doesn't contain a decision model."));

        DMNContext context = runtime.newContext();
        inputs.forEach(context::set);

        DMNResult result = runtime.evaluateAll(model, context);

        List<DecisionOutcome> decisions = result.getDecisionResults().stream()
                .map(dr -> new DecisionOutcome(
                        dr.getDecisionName(),
                        dr.getResult(),
                        dr.getEvaluationStatus() == DMNDecisionResult.DecisionEvaluationStatus.SUCCEEDED,
                        dr.getMessages().stream().map(DMNMessage::getText).toList()))
                .toList();

        return new EvaluationResult(model.getName(), model.getNamespace(), decisions,
                new LinkedHashMap<>(result.getContext().getAll()));
    }

    /** Compiles the model without evaluating it — used to check a DMN file is loadable. */
    public DMNModel load(String dmnXml) {
        return buildRuntime(dmnXml).getModels().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This file doesn't contain a decision model."));
    }

    private DMNRuntime buildRuntime(String dmnXml) {
        Resource resource = new ByteArrayResource(dmnXml.getBytes(StandardCharsets.UTF_8));
        resource.setSourcePath("decision.dmn");
        return DMNRuntimeBuilder.fromDefaults()
                .buildConfiguration()
                .fromResources(List.of(resource))
                .getOrElseThrow(e -> new IllegalArgumentException("Kogito couldn't load this decision model: "
                        + e.getMessage(), e));
    }
}
