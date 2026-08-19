package com.processdesk.harness;

/**
 * What the model is shown of a file — never the file itself.
 *
 * <p>A process and a decision model are different kinds of thing and read differently, so
 * each projects to its own shape. Both stay small enough that the prompt is dominated by
 * the user's question rather than by markup.
 */
public interface AssetProjection {

    /** True when there is nothing worth showing, so the assistant can say so and stop. */
    boolean isEmpty();

    /** The prompt form. */
    String render();
}
