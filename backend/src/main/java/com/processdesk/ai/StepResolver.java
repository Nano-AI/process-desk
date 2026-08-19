package com.processdesk.ai;

import com.processdesk.harness.BpmnDocument;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a step name into the identifier of a step that exists.
 *
 * <p>This never asks the model. The set of step names in a file is small and closed, so
 * matching against it is a decided question rather than a guessed one: exact first, then
 * case and spacing, then a bounded edit distance for typos. A name that matches more than
 * one step is reported as ambiguous rather than resolved arbitrarily — picking one would
 * be a silent guess about which step the user meant.
 */
@Component
public class StepResolver {

    /**
     * @param id       identifier of the matched step, null unless {@code resolved}
     * @param name     the matched step's real name, null unless {@code resolved}
     * @param exact    true when the request named the step exactly
     * @param options  step names to offer back when nothing resolved
     */
    public record Resolution(boolean resolved, String id, String name, boolean exact,
                             boolean ambiguous, List<String> options) {

        static Resolution hit(Element step, boolean exact) {
            return new Resolution(true, step.getAttribute("id"), BpmnDocument.displayName(step),
                    exact, false, List.of());
        }

        /** Nothing came close. {@code options} are the steps that do exist. */
        static Resolution notFound(List<String> allNames) {
            return new Resolution(false, null, null, false, false, allNames);
        }

        /** Several steps matched equally well. {@code options} are those candidates. */
        static Resolution ambiguous(List<String> candidates) {
            return new Resolution(false, null, null, false, true, candidates);
        }
    }

    public Resolution resolve(BpmnDocument doc, String requested) {
        List<Element> steps = doc.elements(BpmnDocument.FLOW_NODES).stream()
                .filter(el -> !BpmnDocument.displayName(el).isBlank())
                .toList();
        List<String> allNames = doc.stepNames();

        if (requested == null || requested.isBlank()) {
            return Resolution.notFound(allNames);
        }

        for (Element step : steps) {
            if (BpmnDocument.displayName(step).equals(requested)) {
                return Resolution.hit(step, true);
            }
        }

        String wanted = normalize(requested);
        List<Element> normalized = steps.stream()
                .filter(step -> normalize(BpmnDocument.displayName(step)).equals(wanted))
                .toList();
        if (normalized.size() == 1) {
            return Resolution.hit(normalized.get(0), true);
        }
        if (normalized.size() > 1) {
            return Resolution.ambiguous(namesOf(normalized));
        }

        int budget = typoBudget(wanted.length());
        if (budget == 0) {
            return Resolution.notFound(allNames);
        }
        List<Element> near = new ArrayList<>();
        for (Element step : steps) {
            if (editDistance(wanted, normalize(BpmnDocument.displayName(step))) <= budget) {
                near.add(step);
            }
        }
        if (near.size() == 1) {
            return Resolution.hit(near.get(0), false);
        }
        return near.isEmpty() ? Resolution.notFound(allNames) : Resolution.ambiguous(namesOf(near));
    }

    /**
     * How many character edits still count as a typo, by name length.
     *
     * <p>Short words are not given any tolerance: plenty of distinct short words sit one
     * edit apart ("Pick" and "Pack", "Ship" and "Skip"), and quietly correcting one into
     * the other would edit a step the user did not mean. Asking is cheap; editing the
     * wrong step is not.
     */
    private static int typoBudget(int length) {
        if (length < 5) {
            return 0;
        }
        return length <= 8 ? 1 : 2;
    }

    private static List<String> namesOf(List<Element> steps) {
        return steps.stream().map(BpmnDocument::displayName).toList();
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Whether {@code requested} plausibly names {@code actual}: the same rule the resolver
     * itself applies, exposed so the fast path can check its own work before trusting it.
     */
    static boolean plausiblyNames(String requested, String actual) {
        if (requested == null || actual == null || requested.isBlank()) {
            return false;
        }
        String wanted = normalize(requested);
        String candidate = normalize(actual);
        if (wanted.equals(candidate)) {
            return true;
        }
        int budget = typoBudget(wanted.length());
        return budget > 0 && editDistance(wanted, candidate) <= budget;
    }

    /** Levenshtein distance, two rows rather than a full matrix. */
    static int editDistance(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
