package com.processdesk.ai;

import com.processdesk.harness.ProcessProjection;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What this provider does on its own, without Ollama running.
 *
 * <p>Reply handling is shared and covered in {@link ModelRepliesTest}; what is left here is
 * the behaviour that belongs to this provider: when it declines to call the model at all,
 * and what the user is told when the call cannot be made.
 */
class OllamaAiProviderTest {

    private OllamaAiProvider unreachableProvider() {
        return new OllamaAiProvider("http://localhost:1", "unreachable", 8192, 0.1, "30m", false,
                -1, -1, -1, 512, 200, 1);
    }

    @Test
    void aClearRequestNeverReachesTheModel() {
        // No Ollama is running here; the fast path must answer on its own.
        var intent = unreachableProvider().interpret("Add \"Quality Check\" after \"Submit Request\"",
                new ProcessProjection("P", List.of("Submit Request")));

        assertEquals(AiProvider.EditIntent.Kind.ADD_AFTER, intent.kind());
        assertEquals("Submit Request", intent.targetStepName());
        assertEquals("Quality Check", intent.value());
    }

    @Test
    void anUnreachableModelDeclinesInsteadOfFailing() {
        var intent = unreachableProvider().interpret("make the approval step cheaper",
                new ProcessProjection("P", List.of("Manager Approval")));

        assertTrue(intent.isNone());
        assertTrue(intent.declineReason().contains("Ollama"), "the user is told what to check");
    }

    @Test
    void anEmptyProcessIsAnsweredWithoutCallingTheModel() {
        var intent = unreachableProvider().interpret("rename something",
                new ProcessProjection("P", List.of()));

        assertTrue(intent.isNone());
        assertTrue(intent.declineReason().contains("no steps"));
    }
}
