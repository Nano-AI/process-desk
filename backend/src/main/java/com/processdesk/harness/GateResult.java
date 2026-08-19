package com.processdesk.harness;

/**
 * One validation gate's verdict. {@code detail} is written for a business user,
 * not an engineer — it is shown verbatim in the UI when a gate fails.
 */
public record GateResult(String id, String label, boolean ok, String detail) {

    public static GateResult pass(String id, String label, String detail) {
        return new GateResult(id, label, true, detail);
    }

    public static GateResult fail(String id, String label, String detail) {
        return new GateResult(id, label, false, detail);
    }
}
