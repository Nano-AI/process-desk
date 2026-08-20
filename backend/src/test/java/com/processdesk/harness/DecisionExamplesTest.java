package com.processdesk.harness;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The worked-example library, and the one thing that matters about it: it is optional.
 *
 * <p>{@code corpora/} is gitignored and rebuilt by {@code scripts/fetch-corpora.sh}, so a clone
 * that has never run that script has no examples. Every test here that needs the corpus says so
 * and is skipped without it; the ones that check the absent case run everywhere, because that
 * is the configuration most machines are in.
 */
class DecisionExamplesTest {

    private static final Path CORPUS = Path.of("../corpora/dmn-tck");

    private DecisionExamples library() {
        return new DecisionExamples(CORPUS.toString(), true);
    }

    private static void needsCorpus() {
        Assumptions.assumeTrue(Files.isDirectory(CORPUS),
                "corpora/dmn-tck not fetched; run scripts/fetch-corpora.sh");
    }

    @Test
    @DisplayName("a missing corpus is a missing corpus, not a failure")
    void anAbsentCorpusIsSilent() {
        DecisionExamples absent = new DecisionExamples("../corpora/does-not-exist", true);

        assertTrue(absent.like("Affordability Category", List.of("DTI"), "UNIQUE").isEmpty());
        // Twice, because the second call goes through the already-loaded path.
        assertTrue(absent.like("Affordability Category", List.of("DTI"), "UNIQUE").isEmpty());
    }

    @Test
    @DisplayName("switched off returns nothing without reading the disk")
    void disabledReturnsNothing() {
        assertTrue(new DecisionExamples(CORPUS.toString(), false)
                .like("Affordability Category", List.of("DTI"), "UNIQUE").isEmpty());
    }

    @Test
    @DisplayName("a range table finds a range table under the same hit policy")
    void aRangeTableFindsOne() {
        needsCorpus();

        String example = library()
                .like("Affordability Category", List.of("DTI"), "UNIQUE")
                .orElse(null);

        assertNotNull(example, "the TCK has range tables; none was offered");
        assertTrue(example.contains("hit policy UNIQUE"), example);
        assertTrue(example.contains("Columns: "), example);
        // Said out loud, because a model that is shown a table without being told why has to
        // guess what it is looking at.
        assertTrue(example.contains("It is not this file"), example);
        assertTrue(example.matches("(?s).*[<>\\[(].*"), "an example with no range is no example");
    }

    @Test
    @DisplayName("a COLLECT table is never shown a UNIQUE one")
    void hitPolicyIsNotCrossed() {
        needsCorpus();

        library().like("anything", List.of("x"), "COLLECT")
                .ifPresent(example -> assertTrue(example.contains("hit policy COLLECT"), example));
    }

    @Test
    @DisplayName("an example arrives on the second failure, not the first")
    void oneFailureIsNotEnough() throws Exception {
        needsCorpus();
        String xml = Files.readString(Path.of("assets/loan-recommendation.dmn"));
        DecisionWorkspace workspace = new DecisionWorkspace(
                xml, new DecisionEditor(), new DecisionGates(), library());
        workspace.showDecision("Affordability Category");

        // One boundary moved. The check fails, and this is the ordinary case the loop already
        // recovers from on the next turn, so nothing is fetched.
        String first = workspace.setCell("Affordability Category", 3, "DTI", "<0.33", "<0.15");
        assertFalse(first.startsWith("All checks pass"));
        assertFalse(first.contains("conformance suite"), first);

        // A second change that still does not close the gap. Now it is not converging.
        String second = workspace.setCell("Affordability Category", 1, "DTI", ">0.36", ">0.40");
        assertTrue(second.contains("conformance suite"),
                "the second failure on one decision is what fetches an example:\n" + second);
    }

    @Test
    @DisplayName("no library means the workspace behaves exactly as it did before")
    void withoutALibraryNothingChanges() throws Exception {
        String xml = Files.readString(Path.of("assets/loan-recommendation.dmn"));
        DecisionWorkspace workspace =
                new DecisionWorkspace(xml, new DecisionEditor(), new DecisionGates());
        workspace.showDecision("Affordability Category");

        workspace.setCell("Affordability Category", 3, "DTI", "<0.33", "<0.15");
        String second = workspace.setCell("Affordability Category", 1, "DTI", ">0.36", ">0.40");

        assertFalse(second.contains("conformance suite"), second);
        assertTrue(second.contains("The table now reads"), second);
    }
}
