package com.smalistean.propstrategy.xvf.shadow;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One immutable attempt to capture the facts needed to measure a candidate outcome. */
public record XvfCandidateOutcome(
        UUID outcomeAttemptId,
        UUID signalRunId,
        int evaluationOrder,
        int horizonHours,
        Instant targetExitUtc,
        Instant captureStartedAt,
        Instant capturedAt,
        int captureToleranceSeconds,
        CaptureStatus captureStatus,
        JsonDocument shortExitSnapshot,
        JsonDocument longExitSnapshot,
        JsonDocument fundingObservations,
        JsonDocument fundingWatermarks,
        JsonDocument dataIssues,
        short formulaInputsVersion) {

    public XvfCandidateOutcome {
        Objects.requireNonNull(outcomeAttemptId, "outcomeAttemptId");
        Objects.requireNonNull(signalRunId, "signalRunId");
        if (evaluationOrder <= 0 || horizonHours <= 0 || captureToleranceSeconds < 0) {
            throw new IllegalArgumentException("Outcome ordinals/horizon must be positive and tolerance non-negative");
        }
        targetExitUtc = micros(targetExitUtc, "targetExitUtc");
        captureStartedAt = micros(captureStartedAt, "captureStartedAt");
        capturedAt = micros(capturedAt, "capturedAt");
        if (capturedAt.isBefore(captureStartedAt)) {
            throw new IllegalArgumentException("capturedAt cannot precede captureStartedAt");
        }
        Objects.requireNonNull(captureStatus, "captureStatus");
        requireObject(shortExitSnapshot, "shortExitSnapshot");
        requireObject(longExitSnapshot, "longExitSnapshot");
        requireArray(fundingObservations, "fundingObservations");
        requireObject(fundingWatermarks, "fundingWatermarks");
        requireArray(dataIssues, "dataIssues");
        if (formulaInputsVersion <= 0) {
            throw new IllegalArgumentException("formulaInputsVersion must be positive");
        }
        if (captureStatus == CaptureStatus.COMPLETE
                && (shortExitSnapshot.size() == 0 || longExitSnapshot.size() == 0)) {
            throw new IllegalArgumentException("A COMPLETE outcome requires both exit snapshots");
        }
        if (captureStatus != CaptureStatus.COMPLETE && dataIssues.size() == 0) {
            throw new IllegalArgumentException("A non-COMPLETE outcome requires data issues");
        }
    }

    public enum CaptureStatus { COMPLETE, PARTIAL, FAILED }

    private static Instant micros(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(name + " exceeds PostgreSQL microsecond precision");
        }
        return value;
    }

    private static void requireObject(JsonDocument value, String name) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
    }

    private static void requireArray(JsonDocument value, String name) {
        if (value == null || !value.isArray()) {
            throw new IllegalArgumentException(name + " must be a JSON array");
        }
    }
}
