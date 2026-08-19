package com.processdesk.ai;

import com.processdesk.harness.AssetProjection;
import com.processdesk.harness.DecisionProjection;
import com.processdesk.harness.ProcessProjection;

/**
 * The seam between this application and the language model.
 *
 * <p>Two things cross it, and neither is a file. Inbound, the model sees an
 * {@link AssetProjection} — for a process, the order work moves through it; for a decision
 * model, what it decides and the rules it decides by — never XML, identifiers or geometry. Outbound it returns prose, or an
 * {@link EditIntent} phrased in step names. The harness turns intents into edits, so
 * changing provider cannot change how files are written or which checks run.
 */
public interface AiProvider {

    /** Human-readable provider name, surfaced in the UI so it is obvious what is answering. */
    String name();

    /**
     * Calls made today, for providers that are metered. Empty when nothing is being spent —
     * a local model has no budget to report, and inventing one would be noise.
     */
    default java.util.Map<String, Object> usage() {
        return java.util.Map.of();
    }

    /** A plain-language answer about the process. */
    String explain(String question, AssetProjection asset);

    /**
     * The change the user asked for, or {@link EditIntent#none} when the request isn't a
     * change this system can make. Processes only — a decision is changed through
     * {@link #interpretDecision}, whose vocabulary is columns and values rather than steps.
     */
    EditIntent interpret(String request, ProcessProjection process);

    /**
     * The change to a decision model the user asked for. Separate from {@link #interpret}
     * because the vocabulary is different: a decision is changed by naming a column and the
     * value it currently holds, not by naming a step.
     */
    EditIntent interpretDecision(String request, DecisionProjection decision);

    /**
     * Whether this provider can hold a tool-calling conversation.
     *
     * <p>A capability rather than a requirement, because the providers genuinely differ:
     * {@link DummyAiProvider} answers from the file and has nothing to call tools with, and a
     * small local model may support the protocol while looping badly enough to be unusable.
     * The one-shot path remains the floor every provider meets.
     */
    default boolean supportsTools() {
        return false;
    }

    /**
     * One turn of a tool-calling conversation: the model's prose, its tool calls, or both.
     *
     * <p>The provider translates {@link Tools.Spec} into whatever its API calls a tool, and
     * translates the reply back. It does not run tools, decide when to stop, or touch a file —
     * {@link DecisionToolLoop} owns the loop, so the two providers cannot drift into having
     * different ideas of what the assistant is allowed to do.
     */
    default Tools.Turn nextTurn(String system, java.util.List<Tools.Message> conversation,
                                java.util.List<Tools.Spec> tools) {
        throw new UnsupportedOperationException(name() + " does not support tool calling.");
    }

    /**
     * A change, expressed in the user's vocabulary: step names and cell coordinates, never
     * identifiers.
     */
    record EditIntent(Kind kind, String targetStepName, String value, String declineReason,
                      String from, Cell cell) {

        public EditIntent(Kind kind, String targetStepName, String value, String declineReason) {
            this(kind, targetStepName, value, declineReason, null, null);
        }

        /**
         * Where in a decision table to write.
         *
         * <p>{@code expect} is the value the model believes is there. The editor refuses if
         * the cell holds something else, so a miscounted rule changes nothing rather than
         * changing the wrong thing — an exact check, where the vocabulary this replaced
         * needed a heuristic about whether the request mentioned the value.
         */
        record Cell(String decision, int rule, String column, String expect) {}

        public enum Kind {
            /** Process edits. */
            RENAME, ADD_AFTER,
            /** Decision edits: write one cell of one rule, or rename the decision. */
            SET_CELL, RENAME_DECISION,
            QUESTION, NONE
        }

        public static EditIntent rename(String targetStepName, String newName) {
            return new EditIntent(Kind.RENAME, targetStepName, newName, null);
        }

        /** True when this changes a decision model rather than a process. */
        public boolean isDecisionEdit() {
            return kind == Kind.SET_CELL || kind == Kind.RENAME_DECISION;
        }

        public static EditIntent addAfter(String targetStepName, String newStepName) {
            return new EditIntent(Kind.ADD_AFTER, targetStepName, newStepName, null);
        }

        /** The request is a question about the process, not a change to it. */
        public static EditIntent question() {
            return new EditIntent(Kind.QUESTION, null, null, null);
        }





        /** Write one cell of one rule. The single shape every table edit now takes. */
        public static EditIntent setCell(String decision, int rule, String column,
                                         String expect, String to) {
            return new EditIntent(Kind.SET_CELL, decision, to, null, expect,
                    new Cell(decision, rule, column, expect));
        }

        public static EditIntent renameDecision(String from, String to) {
            return new EditIntent(Kind.RENAME_DECISION, from, to, null, null, null);
        }

        public static EditIntent none(String reason) {
            return new EditIntent(Kind.NONE, null, null, reason);
        }

        public boolean isNone() {
            return kind == Kind.NONE;
        }
    }
}
