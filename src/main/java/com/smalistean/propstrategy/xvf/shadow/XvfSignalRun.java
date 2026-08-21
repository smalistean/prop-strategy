package com.smalistean.propstrategy.xvf.shadow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One immutable XVF decision-time capture and every candidate evaluated in it.
 *
 * <p>This is an audit contract, not an execution instruction. Nothing in the execution path reads
 * it yet. Variable exchange payloads remain validated JSON documents; values that decide ranking,
 * capital use, or expected net return are strongly typed so they can be queried without reverse-
 * engineering JSON later.
 */
public record XvfSignalRun(
        UUID signalRunId,
        short snapshotSchemaVersion,
        Instant cutoffUtc,
        LocalDate productionDate,
        ZoneId productionZone,
        Instant generatedAt,
        String codeRevision,
        String strategyVersion,
        String configurationHash,
        JsonDocument configurationSnapshot,
        JsonDocument settledFundingWatermarks,
        JsonDocument pendingFundingWatermarks,
        JsonDocument venueStateSnapshot,
        BigDecimal capitalUsd,
        JsonDocument dataIssues,
        CaptureStatus captureStatus,
        String failureCode,
        String failureDetail,
        List<Candidate> candidates) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public XvfSignalRun {
        Objects.requireNonNull(signalRunId, "signalRunId");
        if (snapshotSchemaVersion <= 0) {
            throw new IllegalArgumentException("snapshotSchemaVersion must be positive");
        }
        Objects.requireNonNull(cutoffUtc, "cutoffUtc");
        requireMicrosecondPrecision(cutoffUtc, "cutoffUtc");
        Objects.requireNonNull(productionDate, "productionDate");
        Objects.requireNonNull(productionZone, "productionZone");
        if (!productionDate.equals(cutoffUtc.atZone(productionZone).toLocalDate())) {
            throw new IllegalArgumentException(
                    "productionDate must be the cutoffUtc date in productionZone");
        }
        Objects.requireNonNull(generatedAt, "generatedAt");
        requireMicrosecondPrecision(generatedAt, "generatedAt");
        if (generatedAt.isBefore(cutoffUtc)) {
            throw new IllegalArgumentException("generatedAt cannot precede cutoffUtc");
        }
        requireText(codeRevision, "codeRevision");
        requireText(strategyVersion, "strategyVersion");
        if (configurationHash == null || !SHA_256.matcher(configurationHash).matches()) {
            throw new IllegalArgumentException("configurationHash must be a lowercase SHA-256 hex string");
        }
        requireObject(configurationSnapshot, "configurationSnapshot");
        requireObject(settledFundingWatermarks, "settledFundingWatermarks");
        requireObject(pendingFundingWatermarks, "pendingFundingWatermarks");
        requireObject(venueStateSnapshot, "venueStateSnapshot");
        requireArray(dataIssues, "dataIssues");
        Objects.requireNonNull(captureStatus, "captureStatus");
        candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
        validateCandidateOrdinals(candidates);

        if (capitalUsd != null) {
            requireNumeric(capitalUsd, 30, 12, "capitalUsd");
            if (capitalUsd.signum() <= 0) {
                throw new IllegalArgumentException("capitalUsd must be positive when present");
            }
        }
        switch (captureStatus) {
            case COMPLETE -> {
                if (capitalUsd == null) {
                    throw new IllegalArgumentException("A COMPLETE run requires capitalUsd");
                }
                if (failureCode != null || failureDetail != null) {
                    throw new IllegalArgumentException("A COMPLETE run cannot contain failure fields");
                }
            }
            case PARTIAL -> {
                if (capitalUsd == null) {
                    throw new IllegalArgumentException("A PARTIAL run requires capitalUsd");
                }
                requireFailure(failureCode, dataIssues, "PARTIAL");
            }
            case FAILED -> {
                requireFailure(failureCode, dataIssues, "FAILED");
                if (!candidates.isEmpty()) {
                    throw new IllegalArgumentException("A FAILED run cannot contain candidates");
                }
            }
        }
    }

    public enum CaptureStatus { COMPLETE, PARTIAL, FAILED }

    public enum PairType { CEX_DEX, CEX_CEX, DEX_DEX }

    public enum ScoreStatus { SCORABLE, UNSCORABLE }

    public record Pair(
            String base,
            PairType pairType,
            String shortVenue,
            String shortVenueSymbol,
            String longVenue,
            String longVenueSymbol) {

        public Pair {
            requireText(base, "base");
            Objects.requireNonNull(pairType, "pairType");
            requireText(shortVenue, "shortVenue");
            requireText(shortVenueSymbol, "shortVenueSymbol");
            requireText(longVenue, "longVenue");
            requireText(longVenueSymbol, "longVenueSymbol");
            if (shortVenue.equals(longVenue)) {
                throw new IllegalArgumentException("A candidate must cross two different venues");
            }
        }
    }

    public record Ranks(int grossRank, Integer baselineBookRank, Integer shadowBookRank) {
        public Ranks {
            requirePositive(grossRank, "grossRank");
            requirePositiveWhenPresent(baselineBookRank, "baselineBookRank");
            requirePositiveWhenPresent(shadowBookRank, "shadowBookRank");
        }
    }

    public record SignalScore(
            BigDecimal rawSpreadAnnualPct,
            boolean eligibleYesterday,
            BigDecimal staleDiscountFactor,
            BigDecimal adjustedSpreadAnnualPct,
            Boolean pendingFundingFresh,
            BigDecimal thinLegWeeklyQuoteVolumeUsd) {

        public SignalScore {
            requireNonNegative(rawSpreadAnnualPct, "rawSpreadAnnualPct");
            requireNumeric(rawSpreadAnnualPct, 20, 8, "rawSpreadAnnualPct");
            Objects.requireNonNull(staleDiscountFactor, "staleDiscountFactor");
            requireNumeric(staleDiscountFactor, 20, 12, "staleDiscountFactor");
            if (staleDiscountFactor.signum() <= 0 || staleDiscountFactor.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("staleDiscountFactor must be in (0, 1]");
            }
            requireNonNegative(adjustedSpreadAnnualPct, "adjustedSpreadAnnualPct");
            requireNumeric(adjustedSpreadAnnualPct, 20, 8, "adjustedSpreadAnnualPct");
            requireNonNegativeWhenPresent(thinLegWeeklyQuoteVolumeUsd,
                    "thinLegWeeklyQuoteVolumeUsd");
            requireNumericWhenPresent(thinLegWeeklyQuoteVolumeUsd, 38, 12,
                    "thinLegWeeklyQuoteVolumeUsd");
        }
    }

    public record Route(String makerVenue, String takerVenue, int plannedHoldHours) {
        public Route {
            requireText(makerVenue, "makerVenue");
            requireText(takerVenue, "takerVenue");
            if (makerVenue.equals(takerVenue)) {
                throw new IllegalArgumentException("Maker and taker venues must differ");
            }
            requirePositive(plannedHoldHours, "plannedHoldHours");
        }

        private void validateAgainst(Pair pair) {
            if ((!makerVenue.equals(pair.shortVenue()) && !makerVenue.equals(pair.longVenue()))
                    || (!takerVenue.equals(pair.shortVenue()) && !takerVenue.equals(pair.longVenue()))) {
                throw new IllegalArgumentException("Route venues must be the candidate's two venues");
            }
        }
    }

    /** All figures are signed hold-period basis points except non-negative costs and penalties. */
    public record ExpectedNet(
            BigDecimal pendingFundingSpreadBps,
            BigDecimal entryBasisBps,
            BigDecimal expectedFundingBps,
            BigDecimal expectedBasisPnlBps,
            BigDecimal expectedEntryFeeBps,
            BigDecimal expectedExitFeeBps,
            BigDecimal expectedSlippageBps,
            BigDecimal riskPenaltyBps,
            BigDecimal expectedNetBps) {

        public ExpectedNet {
            requireNumericWhenPresent(pendingFundingSpreadBps, 20, 8,
                    "pendingFundingSpreadBps");
            requireNumericWhenPresent(entryBasisBps, 20, 8, "entryBasisBps");
            requireNumericWhenPresent(expectedFundingBps, 20, 8, "expectedFundingBps");
            requireNumericWhenPresent(expectedBasisPnlBps, 20, 8, "expectedBasisPnlBps");
            requireNonNegativeWhenPresent(expectedEntryFeeBps, "expectedEntryFeeBps");
            requireNonNegativeWhenPresent(expectedExitFeeBps, "expectedExitFeeBps");
            requireNonNegativeWhenPresent(expectedSlippageBps, "expectedSlippageBps");
            requireNonNegativeWhenPresent(riskPenaltyBps, "riskPenaltyBps");
            requireNumericWhenPresent(expectedEntryFeeBps, 20, 8, "expectedEntryFeeBps");
            requireNumericWhenPresent(expectedExitFeeBps, 20, 8, "expectedExitFeeBps");
            requireNumericWhenPresent(expectedSlippageBps, 20, 8, "expectedSlippageBps");
            requireNumericWhenPresent(riskPenaltyBps, 20, 8, "riskPenaltyBps");
            requireNumericWhenPresent(expectedNetBps, 20, 8, "expectedNetBps");
        }
    }

    public record Candidate(
            int evaluationOrder,
            Pair pair,
            Ranks ranks,
            SignalScore signalScore,
            Route route,
            ExpectedNet expectedNet,
            BigDecimal requestedLegNotionalUsd,
            JsonDocument shortLegSnapshot,
            JsonDocument longLegSnapshot,
            JsonDocument scoreComponents,
            JsonDocument gateResults,
            ScoreStatus scoreStatus,
            JsonDocument decisionReasons) {

        public Candidate {
            requirePositive(evaluationOrder, "evaluationOrder");
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(ranks, "ranks");
            Objects.requireNonNull(signalScore, "signalScore");
            Objects.requireNonNull(route, "route").validateAgainst(pair);
            Objects.requireNonNull(expectedNet, "expectedNet");
            if (requestedLegNotionalUsd != null && requestedLegNotionalUsd.signum() <= 0) {
                throw new IllegalArgumentException("requestedLegNotionalUsd must be positive when present");
            }
            requireNumericWhenPresent(requestedLegNotionalUsd, 30, 12,
                    "requestedLegNotionalUsd");
            requireObject(shortLegSnapshot, "shortLegSnapshot");
            requireObject(longLegSnapshot, "longLegSnapshot");
            requireObject(scoreComponents, "scoreComponents");
            requireObject(gateResults, "gateResults");
            Objects.requireNonNull(scoreStatus, "scoreStatus");
            requireArray(decisionReasons, "decisionReasons");
            if (scoreStatus == ScoreStatus.SCORABLE) {
                if (!Boolean.TRUE.equals(signalScore.pendingFundingFresh())) {
                    throw new IllegalArgumentException("A SCORABLE candidate requires fresh pending funding");
                }
                requirePresent(signalScore.thinLegWeeklyQuoteVolumeUsd(),
                        "thinLegWeeklyQuoteVolumeUsd");
                requirePresent(expectedNet.pendingFundingSpreadBps(), "pendingFundingSpreadBps");
                requirePresent(expectedNet.entryBasisBps(), "entryBasisBps");
                requirePresent(expectedNet.expectedFundingBps(), "expectedFundingBps");
                requirePresent(expectedNet.expectedBasisPnlBps(), "expectedBasisPnlBps");
                requirePresent(expectedNet.expectedEntryFeeBps(), "expectedEntryFeeBps");
                requirePresent(expectedNet.expectedExitFeeBps(), "expectedExitFeeBps");
                requirePresent(expectedNet.expectedSlippageBps(), "expectedSlippageBps");
                requirePresent(expectedNet.riskPenaltyBps(), "riskPenaltyBps");
                requirePresent(expectedNet.expectedNetBps(), "expectedNetBps");
                requirePresent(requestedLegNotionalUsd, "requestedLegNotionalUsd");
            } else {
                if (expectedNet.expectedNetBps() != null) {
                    throw new IllegalArgumentException(
                            "An UNSCORABLE candidate cannot contain expectedNetBps");
                }
                if (decisionReasons.size() == 0) {
                    throw new IllegalArgumentException(
                            "An UNSCORABLE candidate requires at least one decision reason");
                }
                if (ranks.shadowBookRank() != null) {
                    throw new IllegalArgumentException(
                            "An UNSCORABLE candidate cannot have a shadowBookRank");
                }
            }
        }
    }

    private static void validateCandidateOrdinals(List<Candidate> candidates) {
        Set<Integer> grossRanks = new HashSet<>();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = Objects.requireNonNull(candidates.get(index),
                    "candidates must not contain null");
            int expectedOrder = index + 1;
            if (candidate.evaluationOrder() != expectedOrder) {
                throw new IllegalArgumentException(
                        "Candidate evaluationOrder must be contiguous and match list order; expected "
                                + expectedOrder + " but found " + candidate.evaluationOrder());
            }
            int grossRank = candidate.ranks().grossRank();
            if (grossRank > candidates.size() || !grossRanks.add(grossRank)) {
                throw new IllegalArgumentException(
                        "Candidate grossRank must be a unique value in 1..candidate_count");
            }
        }
    }

    private static void requireFailure(String failureCode, JsonDocument issues, String status) {
        requireText(failureCode, "failureCode");
        if (issues.size() == 0) {
            throw new IllegalArgumentException("A " + status + " run requires at least one data issue");
        }
    }

    private static void requireObject(JsonDocument document, String name) {
        Objects.requireNonNull(document, name);
        if (!document.isObject()) {
            throw new IllegalArgumentException(name + " must be a JSON object");
        }
    }

    private static void requireArray(JsonDocument document, String name) {
        Objects.requireNonNull(document, name);
        if (!document.isArray()) {
            throw new IllegalArgumentException(name + " must be a JSON array");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requirePositiveWhenPresent(Integer value, String name) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException(name + " must be positive when present");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requireNonNegativeWhenPresent(BigDecimal value, String name) {
        if (value != null && value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative when present");
        }
    }

    private static void requireNumericWhenPresent(BigDecimal value, int precision, int scale,
                                                  String name) {
        if (value != null) {
            requireNumeric(value, precision, scale, name);
        }
    }

    private static void requireNumeric(BigDecimal value, int precision, int scale, String name) {
        BigDecimal stored;
        try {
            stored = value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    name + " exceeds PostgreSQL scale " + scale + " and would be rounded", e);
        }
        if (stored.precision() > precision) {
            throw new IllegalArgumentException(name + " exceeds PostgreSQL precision " + precision);
        }
    }

    private static void requireMicrosecondPrecision(Instant value, String name) {
        if (value.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    name + " exceeds PostgreSQL microsecond precision and would be truncated");
        }
    }

    private static void requirePresent(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("A SCORABLE candidate requires " + name);
        }
    }
}
