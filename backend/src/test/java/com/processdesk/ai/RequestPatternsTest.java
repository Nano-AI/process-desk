package com.processdesk.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fast path decides, without the model, whether a sentence is already an intent.
 *
 * <p>Getting it wrong is worse than not having it: an over-eager question rule once caught
 * "can you call the approval step Supervisor Review instead" and answered the user instead
 * of offering them the edit. These cases pin the boundary.
 */
class RequestPatternsTest {

    private static final List<String> STEPS = List.of(
            "Submit Request", "Verify Membership", "Decide Approval Route",
            "Approve Automatically", "Manager Approval", "Issue Refund", "Notify Member");

    private AiProvider.EditIntent.Kind kindOf(String request) {
        return RequestPatterns.match(request, STEPS)
                .map(AiProvider.EditIntent::kind)
                .orElse(null);
    }

    private java.util.Optional<AiProvider.EditIntent> match(String request) {
        return RequestPatterns.match(request, STEPS);
    }

    @Test
    void matchesAPlainRename() {
        var intent = match("Rename \"Manager Approval\" to \"Supervisor Review\"").orElseThrow();

        assertEquals(AiProvider.EditIntent.Kind.RENAME, intent.kind());
        assertEquals("Manager Approval", intent.targetStepName());
        assertEquals("Supervisor Review", intent.value());
    }

    @Test
    void matchesAPlainAdd() {
        var intent = match("Add \"Quality Check\" after \"Submit Request\"").orElseThrow();

        assertEquals(AiProvider.EditIntent.Kind.ADD_AFTER, intent.kind());
        assertEquals("Submit Request", intent.targetStepName());
        assertEquals("Quality Check", intent.value());
    }




    @Test
    void looselyPhrasedEditsFallThroughToTheModel() {
        // Not an error: the fast path is only for certainty, and these need judgement.
        for (String request : new String[] {
                "stick a quality check right after the submit step",
                "the approval step should be called Supervisor Review",
                "delete the approval step",
        }) {
            assertTrue(match(request).isEmpty(), request);
        }
    }

    @Test
    void handlesEmptyInput() {
        assertTrue(match("").isEmpty());
        assertTrue(match(null).isEmpty());
    }



    @Test
    void aTrailingClauseIsNotSwallowedIntoTheStepName() {
        // "add X after Y" is the shape these patterns know. Given the name last, the greedy
        // capture offered 'Verify Membership called "Send Request"' as the step to change.
        assertTrue(match("Add new step after Verify Membership called \"Send Request\"").isEmpty(),
                "a sentence this shape must go to the model, not be answered wrongly and fast");
    }

    @Test
    void aStepThatDoesNotExistIsNotAFastPath() {
        assertTrue(match("Add \"Quality Check\" after \"Shipping Label\"").isEmpty(),
                "if the captured anchor is not in the file, the pattern misread the sentence");
    }

    @Test
    void aTypoInTheAnchorStillTakesTheFastPath() {
        AiProvider.EditIntent intent = match("Rename \"Manger Aproval\" to \"Supervisor Review\"").orElseThrow();

        assertEquals(AiProvider.EditIntent.Kind.RENAME, intent.kind(),
                "verifying the capture must not cost the typo tolerance the resolver already has");
    }

    @Test
    void quotedFormsStillMatchWhenTheStepExists() {
        assertEquals(AiProvider.EditIntent.Kind.ADD_AFTER,
                kindOf("Add \"Fraud Check\" after \"Verify Membership\""));
    }




    @Test
    void theFastPathNeverClaimsSomethingIsAQuestion() {
        // These patterns are a cache for sentences that are already an intent, not a
        // classifier. An edit they recognise is checked against the file; a question has
        // nothing to check against, so deciding that is left to the model.
        for (String question : java.util.List.of(
                "what happens after Submit Request?",
                "why is Manager Approval needed?",
                "I want to change Income Risk score for under 18's to -150",
                "the score for under 18 should be -200")) {
            assertTrue(RequestPatterns.match(question, java.util.List.of("Submit Request")).isEmpty(),
                    () -> "should have fallen through to the model: " + question);
        }
    }
}
