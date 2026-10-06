package com.processdesk.harness;

import com.processdesk.Fixtures;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The flow line coming back as a step name.
 *
 * <p>A request to remove two steps was classified RENAME — the four kinds the schema offers
 * have no way to say "delete" — and the new name it carried was the projection's own Flow
 * line with the arrows rewritten as hyphens. Every gate passed it, because renaming a step
 * cannot break a graph, so the panel reported three green checks over a step whose name was
 * the entire process. The strings below are verbatim from the audit log.
 */
class FlowPathNameTest {

    private final ProcessProjection projection = ProcessProjection.of(Fixtures.memberRefund());

    @Test
    void theNameThatWasActuallyWrittenIsRefused() {
        String written = "Submit Request (Refund) - Gatekeep - Verify Membership - Decide Approval "
                + "Route - Approve Automatically or Manager Approval - Issue Refund - Notify Member";

        assertTrue(projection.looksLikeFlowPath(written),
                "the name that reached the file must not be nameable again");
    }

    @Test
    void theSecondAttemptToUndoItIsAlsoRefused() {
        // Asked to rename the step back, the model returned the same path minus "(Refund)" —
        // so a guard that only knew the first string would have let the retry through.
        String retry = "Submit Request - Gatekeep - Verify Membership - Decide Approval Route - "
                + "Approve Automatically or Manager Approval - Issue Refund - Notify Member";

        assertTrue(projection.looksLikeFlowPath(retry));
    }

    @Test
    void theProjectionsOwnRenderingIsRefusedWhicheverSeparatorComesBack() {
        String flow = projection.render().lines()
                .filter(line -> line.startsWith("Flow: "))
                .findFirst()
                .orElseThrow()
                .substring("Flow: ".length());

        assertTrue(projection.looksLikeFlowPath(flow),
                () -> "the arrow form is what the model was shown: " + flow);
        assertTrue(projection.looksLikeFlowPath(flow.replace(" → ", " - ")),
                "the hyphen form is what it sent back");
        assertTrue(projection.looksLikeFlowPath(flow.replace(" → ", " -> ")));
    }

    @Test
    void ordinaryNamesAreStillNameable() {
        assertFalse(projection.looksLikeFlowPath("Submit Request (Refund)"));
        assertFalse(projection.looksLikeFlowPath("Gatekeep"));
        assertFalse(projection.looksLikeFlowPath("Quality Check"));
        // One existing step plus a qualifier is a name someone would reasonably type. Only a
        // second known step turns it into a path.
        assertFalse(projection.looksLikeFlowPath("Submit Request - Refund"));
        assertFalse(projection.looksLikeFlowPath("Re-check Membership"));
    }

    @Test
    void nothingIsNotAPath() {
        assertFalse(projection.looksLikeFlowPath(null));
        assertFalse(projection.looksLikeFlowPath("   "));
    }
}
