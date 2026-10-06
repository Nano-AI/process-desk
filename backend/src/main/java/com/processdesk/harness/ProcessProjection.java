package com.processdesk.harness;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What the model is allowed to see of a process: its name and the order work moves
 * through it. Nothing else.
 *
 * <p>A BPMN file is mostly diagram geometry, namespaces and identifiers — material the
 * model must never act on, and which would otherwise dominate the prompt. Projecting the
 * file down to the few facts a request actually depends on cuts the prompt by roughly
 * thirty-five times, and means the model cannot reason about layout it never saw.
 */
public record ProcessProjection(String processName, List<String> steps, List<List<String>> stages)
        implements AssetProjection {

    public static final ProcessProjection EMPTY = new ProcessProjection("", List.of(), List.of());

    public ProcessProjection(String processName, List<String> steps) {
        this(processName, steps, steps.stream().map(List::of).toList());
    }

    public static ProcessProjection of(String xml) {
        try {
            BpmnDocument doc = BpmnDocument.parse(xml);
            String name = doc.elements("process").stream()
                    .map(BpmnDocument::displayName)
                    .findFirst()
                    .orElse("");
            List<List<String>> stages = doc.stepNamesByDepth();
            return new ProcessProjection(name, stages.stream().flatMap(List::stream).toList(), stages);
        } catch (Exception e) {
            return EMPTY;
        }
    }

    /**
     * Whether a proposed step name is really this projection's own flow line, handed back.
     *
     * <p>The check lives here because the string it is defending against is rendered here.
     * {@link #render()} gives the model "Flow: A → B or C → D", and a model that has been
     * asked for something the schema cannot express — deleting a step, which is neither
     * RENAME nor ADD_AFTER — will reach for the nearest field it can fill and copy that
     * line into it. That is not hypothetical: a request to remove two steps arrived as
     * RENAME with the whole flow as the new name, separators swapped from → to -, and every
     * gate passed it, because renaming a step cannot break a graph.
     *
     * <p>Matching on the separator alone is not enough — the copy came back with hyphens —
     * so this asks the stronger question: does the candidate split into segments that are
     * mostly steps this process already has? One is a legitimate name ("Submit Request -
     * Refund"). Two or more is a path someone pasted into a label.
     */
    public boolean looksLikeFlowPath(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String[] segments = candidate.split("\\s+(?:→|->|—|–|-)\\s+");
        if (segments.length < 2) {
            return false;
        }
        long known = Arrays.stream(segments)
                .map(String::trim)
                .filter(segment -> steps.stream().anyMatch(step -> step.equalsIgnoreCase(segment)))
                .count();
        return known >= 2;
    }

    @Override
    public boolean isEmpty() {
        return steps.isEmpty();
    }

    /**
     * The prompt form: a name, and the path work takes.
     *
     * <p>Steps reachable in the same number of hops are alternatives on different branches,
     * and are shown as "A or B". Writing them as a straight chain would tell the model that
     * one runs after the other, which is not what the process does.
     */
    @Override
    public String render() {
        if (isEmpty()) {
            return "Process: (this file has no steps)";
        }
        String flow = stages.stream()
                .map(stage -> String.join(" or ", stage))
                .collect(Collectors.joining(" → "));
        return "Process: " + processName + "\nFlow: " + flow;
    }
}
