package com.processdesk.harness;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ranking decisions by the request, so the model is shown the ones it is about.
 *
 * <p>The assertions that matter here are the guards, not the ranking. A search that returns
 * the wrong order costs tokens; a search that quietly removes the decision the user meant
 * costs them their edit, and looks like the assistant refusing something reasonable.
 */
class Bm25Test {

    private DecisionProjection loanModel() throws Exception {
        Path path = Path.of("assets/loan-recommendation.dmn");
        assertTrue(Files.exists(path), () -> "missing asset: " + path.toAbsolutePath());
        return DecisionProjection.of(Files.readString(path));
    }

    private static List<String> namesOf(DecisionProjection projection) {
        return projection.decisions().stream().map(DecisionProjection.Decision::name).toList();
    }

    @Test
    @DisplayName("compound identifiers are split so a person's words can reach them")
    void tokenizesCompounds() {
        assertEquals(List.of("borrower", "employment", "statu"),
                Bm25.tokenize("Borrower.EmploymentStatus"));
        assertEquals(List.of("pre", "bureau", "risk", "category"),
                Bm25.tokenize("Pre-bureauRiskCategory"));
    }

    @Test
    @DisplayName("numbers survive, because a threshold is a word here")
    void keepsNumbers() {
        assertTrue(Bm25.tokenize("scores under 18").contains("18"));
        assertTrue(Bm25.tokenize("<=10").contains("10"));
    }

    @Test
    @DisplayName("a request finds the decision it is about")
    void findsTheRightDecision() throws Exception {
        DecisionProjection focused =
                loanModel().focusedOn("change the income risk category to high for scores under 20");

        assertTrue(namesOf(focused).contains("Income Risk Category"),
                () -> "expected the income decision, got " + namesOf(focused));
        assertTrue(focused.decisions().size() < 11, "the point is to send fewer");
    }

    @Test
    @DisplayName("narrowing takes the vocabulary with it")
    void vocabularyFollows() throws Exception {
        DecisionProjection focused = loanModel().focusedOn("income risk category for low scores");

        // The enums the model is constrained to must describe the rules it was shown. A
        // column from a decision that was left out is a value it can name and we cannot find.
        for (String column : focused.vocabulary().columns()) {
            assertTrue(focused.decisions().stream().anyMatch(d -> d.columns().contains(column)),
                    () -> column + " is in the vocabulary but not in any decision shown");
        }
    }

    @Test
    @DisplayName("a decision the request names by name is kept whatever it scored")
    void namedDecisionsAlwaysSurvive() throws Exception {
        // "Reserves Months" shares almost no vocabulary with the rest of the request, so
        // only the name check keeps it.
        DecisionProjection focused =
                loanModel().focusedOn("rename the Reserves Months decision to Cash Reserves");

        assertTrue(namesOf(focused).contains("Reserves Months"),
                () -> "a decision the user named must not be ranked away: " + namesOf(focused));
    }

    @Test
    @DisplayName("a request that matches nothing keeps everything")
    void noMatchKeepsEverything() throws Exception {
        DecisionProjection whole = loanModel();
        DecisionProjection focused = whole.focusedOn("zzzz qqqq");

        assertEquals(whole.decisions().size(), focused.decisions().size(),
                "keeping an arbitrary five would be a silent, unexplained truncation");
    }

    @Test
    @DisplayName("a small model is never narrowed")
    void smallModelsAreLeftAlone() {
        DecisionProjection small = DecisionProjection.of(com.processdesk.Fixtures.refundDecision());

        assertSame(small, small.focusedOn("change the member tier"),
                "with a handful of decisions there is nothing to gain and something to lose");
    }

    @Test
    @DisplayName("an empty request narrows nothing")
    void emptyRequest() throws Exception {
        DecisionProjection whole = loanModel();

        assertSame(whole, whole.focusedOn(""));
        assertSame(whole, whole.focusedOn(null));
    }

    @Test
    @DisplayName("narrowing measurably shrinks what the model is sent")
    void narrowingSaves() throws Exception {
        DecisionProjection whole = loanModel();
        DecisionProjection focused = whole.focusedOn("what score do under 18s get?");

        int before = whole.render().length();
        int after = focused.render().length();
        assertTrue(after < before, () -> after + " should be smaller than " + before);
        // Reported rather than asserted tightly: the ratio depends on the file, and pinning
        // it would make this a test of the fixture rather than of the search.
        System.out.printf("projection %d → %d chars (%d%% smaller)%n",
                before, after, 100 - (after * 100 / before));
    }
}
