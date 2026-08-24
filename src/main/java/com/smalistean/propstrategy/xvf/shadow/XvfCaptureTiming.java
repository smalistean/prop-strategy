package com.smalistean.propstrategy.xvf.shadow;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Immutable wall-clock timing for one scheduled shadow capture.
 *
 * <p>The scheduled decision time is recorded before any market fetch. The capture window bounds
 * the period during which public market data was collected. These fields are separate from the
 * source timestamps carried inside each venue snapshot so that cross-venue skew and overall
 * capture duration can be measured directly.
 */
public record XvfCaptureTiming(
        Instant scheduledDecisionAt,
        Instant cutoffUtc,
        Instant captureStartedAt,
        Instant captureEndedAt,
        String scheduledAttemptId) {

    public XvfCaptureTiming {
        Objects.requireNonNull(scheduledDecisionAt, "scheduledDecisionAt");
        requireMicrosecondPrecision(scheduledDecisionAt, "scheduledDecisionAt");
        Objects.requireNonNull(cutoffUtc, "cutoffUtc");
        requireMicrosecondPrecision(cutoffUtc, "cutoffUtc");
        if (cutoffUtc.isBefore(scheduledDecisionAt)) {
            throw new IllegalArgumentException("cutoffUtc cannot precede scheduledDecisionAt");
        }
        Objects.requireNonNull(captureStartedAt, "captureStartedAt");
        requireMicrosecondPrecision(captureStartedAt, "captureStartedAt");
        Objects.requireNonNull(captureEndedAt, "captureEndedAt");
        requireMicrosecondPrecision(captureEndedAt, "captureEndedAt");
        if (captureEndedAt.isBefore(captureStartedAt)) {
            throw new IllegalArgumentException("captureEndedAt cannot precede captureStartedAt");
        }
        requireText(scheduledAttemptId, "scheduledAttemptId");
    }

    private static void requireMicrosecondPrecision(Instant value, String name) {
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    name + " exceeds PostgreSQL microsecond precision and would be truncated");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
