package com.processdesk.corpus;

import com.processdesk.harness.DecisionProjection;
import java.nio.file.*;

/**
 * Shows which decisions a request narrows to, and the vocabulary that goes with them.
 *
 * <p>The counterpart of {@code inspect} for the search: when the assistant answers about the
 * wrong decision, this says whether the ranking dropped it or the model misread it, without
 * spending a request.
 */
public final class Focus {
    public static void main(String[] args) throws Exception {
        String xml = Files.readString(Path.of("assets/loan-recommendation.dmn"));
        for (String request : args) {
            DecisionProjection focused = DecisionProjection.of(xml).focusedOn(request);
            System.out.println("── " + request);
            focused.decisions().forEach(d -> System.out.println("     " + d.name()));
            System.out.println("   columns: " + focused.vocabulary().columns());
            System.out.println("   outcomes: " + focused.vocabulary().outcomes());
        }
    }
}
