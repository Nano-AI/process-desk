package com.processdesk.ai;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Counts calls actually made to a metered provider, and resets when its quota does.
 *
 * <p>A free-tier key is capped per day and per model, and Google publishes no endpoint for
 * how much of that is left — the 429 is the first and only notification, and by then the
 * work has stopped. Counting locally is not the same number as Google's (a key shared with
 * another machine, or a request that failed before reaching us, will not match) but it is
 * the number for this application, which is the one being spent.
 *
 * <p>The audit log cannot do this job: it records proposals, so questions, refusals and
 * retries — the majority of calls — never appear in it.
 */
class CallBudget {

    /** Free-tier daily quotas reset at midnight Pacific, not at local midnight. */
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");

    private final String model;
    private final int dailyLimit;
    private LocalDate day = LocalDate.now(QUOTA_ZONE);
    private int calls;

    CallBudget(String model, int dailyLimit) {
        this.model = model;
        this.dailyLimit = dailyLimit;
    }

    synchronized void record() {
        rollOver();
        calls++;
    }

    /**
     * Called when the provider is told it is out of quota, so the estimate self-corrects.
     *
     * <p>Only for the daily quota. A free-tier key is capped twice — flash-lite is 15 requests
     * per minute as well as 500 per day — and a tool loop spends five to eight calls on one
     * request, so two requests in quick succession hit the per-minute cap while the day is
     * barely touched. Treating that as the day being gone wrote the counter to 501/500 after
     * about thirty real calls and disabled the provider until midnight, which is a far worse
     * outcome than the ten-second wait the minute limit actually asks for.
     */
    synchronized void exhausted() {
        rollOver();
        if (dailyLimit > 0) {
            calls = Math.max(calls, dailyLimit);
        }
    }

    /**
     * Whether a 429's own wording says the daily allowance is gone rather than the per-minute one.
     *
     * <p>Google names the metric it refused on — {@code generate_content_free_tier_requests} with
     * {@code limit: 15} for the minute, and a {@code per_day} metric for the day. Read from the
     * message because there is no structured field for it, and defaulting to "not the day" when
     * the wording is unfamiliar: over-counting stops the assistant working, under-counting costs
     * one rejected call.
     */
    static boolean saysDailyQuota(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("per_day") || lower.contains("perday") || lower.contains("per day");
    }

    private void rollOver() {
        LocalDate today = LocalDate.now(QUOTA_ZONE);
        if (!today.equals(day)) {
            day = today;
            calls = 0;
        }
    }

    /** What the UI shows: calls used today, and against what, when a limit is configured. */
    synchronized Map<String, Object> snapshot() {
        rollOver();
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("model", model);
        usage.put("callsToday", calls);
        // 0 means "not known for this model". Publishing a wrong ceiling would be worse than
        // publishing none: the number people plan against has to be one they can trust.
        if (dailyLimit > 0) {
            usage.put("dailyLimit", dailyLimit);
            usage.put("remaining", Math.max(0, dailyLimit - calls));
        }
        usage.put("resetsAt", "midnight America/Los_Angeles");
        return usage;
    }
}
