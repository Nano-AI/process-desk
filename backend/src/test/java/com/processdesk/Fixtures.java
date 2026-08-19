package com.processdesk;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Test inputs, read from the test classpath.
 *
 * <p>Deliberately not the files under {@code assets/}: the running application writes to
 * those, so a test reading them would pass or fail depending on what someone last did in
 * the UI.
 */
public final class Fixtures {

    private Fixtures() {}

    public static String refundProcess() {
        return read("/fixtures/refund-request.bpmn");
    }

    /** A branching process: eight steps, a diverging gateway and a converging one. */
    public static String memberRefund() {
        return read("/fixtures/member-refund.bpmn");
    }

    public static String refundDecision() {
        return read("/fixtures/refund-approval.dmn");
    }

    private static String read(String path) {
        try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
