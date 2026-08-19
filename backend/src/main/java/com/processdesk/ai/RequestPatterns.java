package com.processdesk.ai;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Requests that need no inference.
 *
 * <p>A clearly phrased request — "rename X to Y", "add X after Y" — is already an intent
 * written out longhand. Matching it here costs nothing and saves a call, which matters on a
 * metered provider. The model is for everything these do not catch.
 *
 * <p><b>These patterns may only recognise an edit, never a question.</b> The difference is
 * verification: a captured step name is checked against the open file, so a misread falls
 * through to the model instead of being answered wrongly and quickly. Deciding that a
 * sentence is a question has nothing to check against, and every regex that tried was
 * eventually confidently wrong — most plainly on <i>"I want to change Income Risk score for
 * under 18's to -150"</i>, which opens with a pronoun, and which the model reads correctly.
 * Working out what a person meant is the model's job; this is only a cache for the cases
 * where the sentence is already the answer.
 */
final class RequestPatterns {

    private static final Pattern RENAME = Pattern.compile(
            "rename\\s+\"?(.+?)\"?\\s+to\\s+\"?(.+?)\"?\\s*[.!]?$", Pattern.CASE_INSENSITIVE);

    private static final Pattern ADD_AFTER = Pattern.compile(
            "add\\s+(?:a\\s+)?(?:step|task\\s+)?\"?(.+?)\"?\\s+after\\s+\"?(.+?)\"?\\s*[.!]?$",
            Pattern.CASE_INSENSITIVE);

    private RequestPatterns() {}

    /**
     * @param knownSteps the steps in the open file. A pattern that captures a step name not
     *                   in this list has misread the sentence, so the request goes to the
     *                   model instead of being answered wrongly and quickly.
     */
    static Optional<AiProvider.EditIntent> match(String request, List<String> knownSteps) {
        String text = request == null ? "" : request.trim();

        Matcher rename = RENAME.matcher(text);
        if (rename.matches() && namesAStep(rename.group(1), knownSteps)) {
            return Optional.of(AiProvider.EditIntent.rename(rename.group(1), rename.group(2)));
        }

        Matcher add = ADD_AFTER.matcher(text);
        if (add.matches() && namesAStep(add.group(2), knownSteps)) {
            return Optional.of(AiProvider.EditIntent.addAfter(add.group(2), add.group(1)));
        }

        return Optional.empty();
    }

    /**
     * These patterns assume the shape "add X after Y". Given "add a step after Y called X"
     * the greedy capture swallows the trailing clause and offers "Y called X" as the step to
     * change — a confident answer to a sentence it did not understand. Checking the captured
     * name against the file turns that into a fall-through, and the model reads it correctly.
     */
    private static boolean namesAStep(String captured, List<String> knownSteps) {
        return knownSteps.stream().anyMatch(step -> StepResolver.plausiblyNames(captured, step));
    }

    static final String UNSUPPORTED =
            "I can make two kinds of changes right now: rename a step "
                    + "(\"Rename Manager Approval to Supervisor Review\") or add a step "
                    + "(\"Add Quality Check after Submit Request\").";
}
