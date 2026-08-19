package com.processdesk.ai;

import com.processdesk.harness.DecisionProjection;
import com.processdesk.harness.ProcessProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What this provider does without reaching Gemini.
 *
 * <p>No test here makes a network call: a suite that needs an API key and a quota is a suite
 * that stops running. Reply handling is covered in {@link ModelRepliesTest} and the schema
 * translation in {@link GeminiSchemaTest}; what is left is the provider's own behaviour —
 * when it declines to call at all, and what the user is told when a call cannot be made.
 */
class GeminiAiProviderTest {

    private static final ProcessProjection PROCESS =
            new ProcessProjection("Refund", List.of("Submit Request", "Manager Approval"));

    private GeminiAiProvider withoutKey() {
        return new GeminiAiProvider("http://localhost:1", "gemini-3.5-flash", "", 0.1, 0, 1, 20);
    }

    private GeminiAiProvider unreachable() {
        return new GeminiAiProvider("http://localhost:1", "gemini-3.5-flash", "test-key", 0.1, 0, 1, 20);
    }

    @Test
    void aMissingKeySaysWhereToPutOne() {
        var intent = withoutKey().interpret("make the approval step cheaper", PROCESS);

        assertTrue(intent.isNone());
        assertTrue(intent.declineReason().contains("GEMINI_API_KEY"));
        assertTrue(intent.declineReason().contains(".env"), "the user is told which file");
    }

    @Test
    void aMissingKeyAlsoSaysSoWhenAsked() {
        assertTrue(withoutKey().explain("what does this do?", PROCESS).contains("GEMINI_API_KEY"));
    }

    @Test
    void aClearRequestNeverReachesTheModel() {
        // The fast path is shared with Ollama, and matters more here: an unambiguous rename
        // should not cost a network round trip or a slice of the free tier's quota.
        var intent = withoutKey().interpret("Add \"Quality Check\" after \"Submit Request\"", PROCESS);

        assertEquals(AiProvider.EditIntent.Kind.ADD_AFTER, intent.kind());
        assertEquals("Submit Request", intent.targetStepName());
        assertEquals("Quality Check", intent.value());
    }

    @Test
    void anEmptyFileIsAnsweredWithoutCalling() {
        var intent = unreachable().interpret("rename something", new ProcessProjection("P", List.of()));

        assertTrue(intent.isNone());
        assertTrue(intent.declineReason().contains("no steps"));
    }

    @Test
    void anEmptyDecisionIsAnsweredWithoutCalling() {
        var intent = unreachable().interpretDecision("change something", DecisionProjection.EMPTY);

        assertTrue(intent.isNone());
        assertTrue(intent.declineReason().contains("no decisions"));
    }

    @Test
    void anUnreachableApiDeclinesInsteadOfFailing() {
        var intent = unreachable().interpret("make the approval step cheaper", PROCESS);

        assertTrue(intent.isNone());
        assertTrue(intent.declineReason().contains("Gemini"), "the user is told what did not answer");
    }

    @Test
    void anUnreachableApiStillAnswersQuestionsWithAMessage() {
        String answer = unreachable().explain("what does this do?", PROCESS);

        assertTrue(answer.contains("Gemini"));
    }

    @Test
    void theProviderNamesTheModelItIsUsing() {
        // Surfaced in the UI: which model answered is not something to have to guess at.
        assertEquals("gemini-3.5-flash", unreachable().name());
    }

    @Test
    void aMeteredProviderReportsWhatItHasSpent() {
        var provider = unreachable();
        assertEquals(0, provider.usage().get("callsToday"));
        assertEquals(20, provider.usage().get("dailyLimit"));

        // The call fails — nothing is listening on that port — and still counts, because a
        // request that leaves the machine has spent the quota whatever comes back.
        provider.interpret("make the approval step cheaper", PROCESS);

        assertEquals(1, provider.usage().get("callsToday"));
        assertEquals(19, provider.usage().get("remaining"));
    }

    @Test
    void aFastPathEditCostsNothing() {
        var provider = unreachable();
        provider.interpret("Add \"Quality Check\" after \"Submit Request\"", PROCESS);

        assertEquals(0, provider.usage().get("callsToday"), "the model was never called");
    }

    @Test
    void anUnknownLimitReportsTheCountWithoutACeiling() {
        // Publishing a guessed ceiling is worse than publishing none: the number people plan
        // against has to be one they can trust.
        var provider = new GeminiAiProvider("http://localhost:1", "gemini-3.5-flash-lite",
                "test-key", 0.1, 0, 1, 0);

        assertEquals(0, provider.usage().get("callsToday"));
        assertFalse(provider.usage().containsKey("dailyLimit"));
        assertFalse(provider.usage().containsKey("remaining"));
    }
}
