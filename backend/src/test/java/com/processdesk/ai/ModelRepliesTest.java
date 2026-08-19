package com.processdesk.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the mapping from a model's reply onto an intent, without needing any model running.
 *
 * <p>Every provider shares this code, so these are the guarantees that hold whichever model
 * answers: a reply with a missing field, a stray case or an empty string has to land
 * somewhere safe rather than becoming half an edit.
 */
class ModelRepliesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private AiProvider.EditIntent parse(String json) throws Exception {
        return ModelReplies.toIntent(mapper.readTree(json));
    }

    @Test
    void readsARenameReply() throws Exception {
        var intent = parse("""
                {"kind":"RENAME","targetStepName":"Manager Approval","value":"Supervisor Review"}""");

        assertEquals(AiProvider.EditIntent.Kind.RENAME, intent.kind());
        assertEquals("Manager Approval", intent.targetStepName());
        assertEquals("Supervisor Review", intent.value());
    }

    @Test
    void readsAnAddReply() throws Exception {
        var intent = parse("""
                {"kind":"ADD_AFTER","targetStepName":"Submit Request","value":"Quality Check"}""");

        assertEquals(AiProvider.EditIntent.Kind.ADD_AFTER, intent.kind());
        assertEquals("Submit Request", intent.targetStepName());
        assertEquals("Quality Check", intent.value());
    }

    @Test
    void acceptsLowercaseKind() throws Exception {
        var intent = parse("""
                {"kind":"rename","targetStepName":"A","value":"B"}""");

        assertEquals(AiProvider.EditIntent.Kind.RENAME, intent.kind());
    }

    @Test
    void passesThroughTheModelsOwnRefusal() throws Exception {
        var intent = parse("""
                {"kind":"NONE","declineReason":"I can't delete steps yet."}""");

        assertTrue(intent.isNone());
        assertEquals("I can't delete steps yet.", intent.declineReason());
    }

    @Test
    void declinesWhenARenameIsMissingItsNewName() throws Exception {
        var intent = parse("""
                {"kind":"RENAME","targetStepName":"Manager Approval"}""");

        assertTrue(intent.isNone(), "a half-formed edit must not become an edit");
        assertNotNull(intent.declineReason());
    }

    @Test
    void declinesWhenAnAddIsMissingItsAnchor() throws Exception {
        var intent = parse("""
                {"kind":"ADD_AFTER","value":"Quality Check"}""");

        assertTrue(intent.isNone());
    }

    @Test
    void treatsBlankFieldsAsMissing() throws Exception {
        var intent = parse("""
                {"kind":"RENAME","targetStepName":"   ","value":"B"}""");

        assertTrue(intent.isNone(), "whitespace is not a step name");
    }

    @Test
    void fallsBackWhenTheReplyIsNothingLikeTheSchema() throws Exception {
        var intent = parse("{\"foo\":\"bar\"}");

        assertTrue(intent.isNone());
        assertEquals(RequestPatterns.UNSUPPORTED, intent.declineReason());
    }

    @Test
    void keepsALeadSentenceAndAtMostThreeBullets() {
        String shaped = ModelReplies.trimToShape("""
                This process handles refund requests.
                - Submit a refund request to start
                - Verify membership status
                - Decide whether a manager is needed
                - Issue the refund
                - Notify the member
                """);

        assertEquals(4, shaped.lines().count(), "one lead sentence plus three bullets");
        assertTrue(shaped.startsWith("This process handles refund requests."));
        assertTrue(shaped.contains("- Decide whether a manager is needed"));
        assertFalse(shaped.contains("Notify the member"), "the fourth bullet onwards is dropped");
    }

    @Test
    void normalisesBulletMarkers() {
        String shaped = ModelReplies.trimToShape("Lead line.\n* starred\n• dotted");

        assertTrue(shaped.contains("- starred"));
        assertTrue(shaped.contains("- dotted"));
        assertFalse(shaped.contains("*"));
    }

    @Test
    void dropsProseTackedOnAfterTheBullets() {
        // Small models like to restate themselves once the list is done.
        String shaped = ModelReplies.trimToShape(
                "Lead line.\n- first point\nIn summary, this process handles refunds.");

        assertEquals(2, shaped.lines().count());
        assertFalse(shaped.contains("In summary"));
    }

    @Test
    void survivesAReplyWithNoBullets() {
        assertEquals("Just one sentence.", ModelReplies.trimToShape("  Just one sentence.  "));
    }

    @Test
    void survivesAnEmptyReply() {
        assertEquals("", ModelReplies.trimToShape("   \n  \n "));
    }





    @Test
    void aRefusalThatLeaksTheSchemasVocabularyIsReplaced() throws Exception {
        // Verbatim from gemini-3.5-flash, asked to delete a step. Fluent, accurate, and
        // written in the vocabulary this whole system exists to keep away from the user.
        var intent = parse("{\"kind\":\"NONE\",\"declineReason\":"
                + "\"Deleting a step is not supported. Only RENAME, ADD_AFTER, and "
                + "QUESTION operations are allowed.\"}");

        assertTrue(intent.isNone());
        assertEquals(RequestPatterns.UNSUPPORTED, intent.declineReason());
        assertFalse(intent.declineReason().contains("ADD_AFTER"));
    }

    @Test
    void aDecisionRefusalIsCheckedTheSameWay() throws Exception {
        var intent = ModelReplies.toDecisionIntent(mapper.readTree(
                "{\"kind\":\"NONE\",\"declineReason\":\"Use SET_CELL for this.\"}"));

        assertTrue(intent.isNone());
        assertFalse(intent.declineReason().contains("SET_CELL"));
        assertTrue(intent.declineReason().contains("decision table"),
                "the fallback fits the kind of file that was open");
    }

    @Test
    void aPlainRefusalIsKeptAsTheModelWroteIt() throws Exception {
        // The guard must not swallow a good reason — that is the whole cost of having it.
        var intent = parse("{\"kind\":\"NONE\",\"declineReason\":"
                + "\"I can rename a step, or add a new one after an existing step.\"}");

        assertEquals("I can rename a step, or add a new one after an existing step.",
                intent.declineReason());
    }

    @Test
    void aRefusalQuotesOnlyTheColumnTheUserNamed() {
        // The unscoped version listed every condition in the file — fifty values across
        // unrelated decisions, offering "Employed" as a legal setting for a credit score.
        var vocabulary = new com.processdesk.harness.DecisionProjection.Vocabulary(
                java.util.List.of("Income Risk Score", "Borrower.EmploymentStatus"),
                java.util.List.of(">70", "(10..70]", "<=10", "\"Employed\"", "\"Retired\""),
                java.util.List.of("\"High\""), java.util.List.of("Income Risk Category"),
                java.util.Map.of("Income Risk Score", java.util.List.of(">70", "(10..70]", "<=10"),
                        "Borrower.EmploymentStatus", java.util.List.of("\"Employed\"", "\"Retired\"")));

        String message = ModelReplies.valueNotFound(vocabulary, "Income Risk Score");

        assertTrue(message.contains(">70") && message.contains("<=10"));
        assertFalse(message.contains("Employed"), "a value from another decision is not an option here");
        assertTrue(message.length() < 120, () -> "should be readable, was " + message.length() + " chars");
    }

    @Test
    void aRefusalWithNoColumnFallsBackToNamingTheColumns() {
        var vocabulary = new com.processdesk.harness.DecisionProjection.Vocabulary(
                java.util.List.of("Refund Amount", "Member Tier"),
                java.util.List.of("<= 50"), java.util.List.of("\"Automatic\""),
                java.util.List.of("Approval Route"));

        String message = ModelReplies.valueNotFound(vocabulary, null);

        assertTrue(message.contains("Refund Amount"));
        assertTrue(message.contains("Member Tier"));
    }

    @Test
    void aLongListIsSummarisedRatherThanRecited() {
        java.util.List<String> many = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> "v" + i).toList();
        var vocabulary = new com.processdesk.harness.DecisionProjection.Vocabulary(
                java.util.List.of("Score"), many, java.util.List.of(), java.util.List.of(),
                java.util.Map.of("Score", many));

        String message = ModelReplies.valueNotFound(vocabulary, "Score");

        assertTrue(message.contains("and 22 more"), message);
        assertFalse(message.contains("v29"), "the tail is counted, not printed");
    }

    @Test
    void aDecisionReplyCanSayTheRequestWasAQuestion() throws Exception {
        // Question-or-instruction is decided by the model, for decisions as for processes.
        // A regex made this call until "I want to change Income Risk score for under 18's to
        // -150" was classified as a question because it opens with a pronoun — and the
        // model's correct reading was discarded before the user ever saw it.
        var intent = ModelReplies.toDecisionIntent(mapper.readTree(
                "{\"kind\":\"QUESTION\",\"column\":\"\",\"fromCondition\":\"\","
                        + "\"fromOutcome\":\"\",\"to\":\"\",\"declineReason\":\"\"}"));

        assertEquals(AiProvider.EditIntent.Kind.QUESTION, intent.kind());
        assertFalse(intent.isDecisionEdit(), "a question must not reach the editor");
        assertFalse(intent.isNone(), "a question is not a refusal");
    }









    @Test
    void readsACellEdit() throws Exception {
        // One shape for every table edit: a coordinate, what is believed to be there, and
        // what it should say instead.
        var intent = ModelReplies.toDecisionIntent(mapper.readTree(
                "{\"kind\":\"SET_CELL\",\"decision\":\"Income Risk Category\",\"rule\":3,"
                        + "\"column\":\"Income Risk Score\",\"expect\":\"<=10\","
                        + "\"to\":\"<=20\",\"declineReason\":\"\"}"));

        assertEquals(AiProvider.EditIntent.Kind.SET_CELL, intent.kind());
        assertEquals("Income Risk Category", intent.cell().decision());
        assertEquals(3, intent.cell().rule());
        assertEquals("Income Risk Score", intent.cell().column());
        assertEquals("<=10", intent.cell().expect());
        assertEquals("<=20", intent.value());
        assertTrue(intent.isDecisionEdit());
    }

    @Test
    void aCellEditWithoutARuleNumberDeclines() throws Exception {
        // Rules are numbered from 1, so a missing or zero rule is not a coordinate. Treating
        // it as the first rule would write confidently to somewhere nobody chose.
        var intent = ModelReplies.toDecisionIntent(mapper.readTree(
                "{\"kind\":\"SET_CELL\",\"decision\":\"Income Risk Category\",\"rule\":0,"
                        + "\"column\":\"Income Risk Score\",\"expect\":\"<=10\","
                        + "\"to\":\"<=20\",\"declineReason\":\"\"}"));

        assertTrue(intent.isNone());
    }

    @Test
    void aCellEditWithoutANewValueDeclines() throws Exception {
        var intent = ModelReplies.toDecisionIntent(mapper.readTree(
                "{\"kind\":\"SET_CELL\",\"decision\":\"Income Risk Category\",\"rule\":3,"
                        + "\"column\":\"Income Risk Score\",\"expect\":\"<=10\","
                        + "\"to\":\"\",\"declineReason\":\"\"}"));

        assertTrue(intent.isNone(), "a half-formed edit must not become an edit");
    }

    @Test
    void readsADecisionRename() throws Exception {
        var intent = ModelReplies.toDecisionIntent(mapper.readTree(
                "{\"kind\":\"RENAME_DECISION\",\"decision\":\"Approval Route\",\"rule\":0,"
                        + "\"column\":\"\",\"expect\":\"\",\"to\":\"Routing\",\"declineReason\":\"\"}"));

        assertEquals(AiProvider.EditIntent.Kind.RENAME_DECISION, intent.kind());
        assertEquals("Approval Route", intent.targetStepName());
        assertEquals("Routing", intent.value());
    }
}
