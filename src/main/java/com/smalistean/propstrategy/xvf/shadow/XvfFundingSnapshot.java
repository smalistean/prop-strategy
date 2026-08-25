package com.smalistean.propstrategy.xvf.shadow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable decision-time view of pending funding observations and settled-funding watermarks.
 *
 * <p>Missing values stay missing. In particular, a missing pending observation is represented by an
 * empty lookup, a missing venue watermark has {@link Freshness#MISSING}, and an interval that cannot
 * be established from stored target stamps is {@code null}; none of those cases is converted to a
 * zero rate, zero timestamp, or zero-hour interval.
 */
public record XvfFundingSnapshot(
        Instant cutoffUtc,
        Map<Instrument, PendingObservation> pendingByInstrument,
        Map<Instrument, List<PendingObservation>> pendingHistoryByInstrument,
        List<PendingVenueWatermark> pendingWatermarks,
        List<SettledVenueWatermark> settledWatermarks) {

    /** Compatibility constructor for fixtures or callers that only own the latest observation. */
    public XvfFundingSnapshot(
            Instant cutoffUtc,
            Map<Instrument, PendingObservation> pendingByInstrument,
            List<PendingVenueWatermark> pendingWatermarks,
            List<SettledVenueWatermark> settledWatermarks) {
        this(cutoffUtc, pendingByInstrument, singletonHistories(pendingByInstrument),
                pendingWatermarks, settledWatermarks);
    }

    public XvfFundingSnapshot {
        Objects.requireNonNull(cutoffUtc, "cutoffUtc");
        pendingByInstrument = Map.copyOf(Objects.requireNonNull(
                pendingByInstrument, "pendingByInstrument"));
        pendingHistoryByInstrument = immutableHistories(pendingHistoryByInstrument);
        pendingWatermarks = List.copyOf(Objects.requireNonNull(
                pendingWatermarks, "pendingWatermarks"));
        settledWatermarks = List.copyOf(Objects.requireNonNull(
                settledWatermarks, "settledWatermarks"));

        for (Map.Entry<Instrument, PendingObservation> entry : pendingByInstrument.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().instrument())) {
                throw new IllegalArgumentException(
                        "pendingByInstrument key must match the observation instrument");
            }
            if (entry.getValue().observedAt().isAfter(cutoffUtc)) {
                throw new IllegalArgumentException("pending observation cannot be after cutoffUtc");
            }
        }
        if (!pendingHistoryByInstrument.keySet().equals(pendingByInstrument.keySet())) {
            throw new IllegalArgumentException(
                    "pending history and latest pending instruments must match");
        }
        for (Map.Entry<Instrument, List<PendingObservation>> entry
                : pendingHistoryByInstrument.entrySet()) {
            List<PendingObservation> history = entry.getValue();
            if (history.isEmpty() || history.size() > 4) {
                throw new IllegalArgumentException(
                        "pending history must contain between one and four observations");
            }
            PendingObservation previous = null;
            for (PendingObservation observation : history) {
                if (!entry.getKey().equals(observation.instrument())) {
                    throw new IllegalArgumentException(
                            "pending history key must match every observation instrument");
                }
                if (observation.observedAt().isAfter(cutoffUtc)) {
                    throw new IllegalArgumentException(
                            "pending history observation cannot be after cutoffUtc");
                }
                if (previous != null && compareObservation(previous, observation) >= 0) {
                    throw new IllegalArgumentException(
                            "pending history must be strictly ordered oldest to newest");
                }
                previous = observation;
            }
            if (!history.getLast().equals(pendingByInstrument.get(entry.getKey()))) {
                throw new IllegalArgumentException(
                        "latest pending observation must equal the final history observation");
            }
        }
        for (SettledVenueWatermark watermark : settledWatermarks) {
            if (watermark.latestFundingTime() != null
                    && watermark.latestFundingTime().isAfter(cutoffUtc)) {
                throw new IllegalArgumentException("settled watermark cannot be after cutoffUtc");
            }
        }
    }

    /** Exact lookup; an absent observation is missing data, never a zero funding rate. */
    public Optional<PendingObservation> pending(String venue, String venueSymbol) {
        return Optional.ofNullable(pendingByInstrument.get(new Instrument(venue, venueSymbol)));
    }

    /** Up to four causal observations, ordered oldest to newest. */
    public List<PendingObservation> pendingHistory(String venue, String venueSymbol) {
        return pendingHistoryByInstrument.getOrDefault(
                new Instrument(venue, venueSymbol), List.of());
    }

    public enum Freshness { FRESH, STALE, MISSING }

    /** How {@link PendingObservation#fundingIntervalHours()} was established. */
    public enum IntervalSource { TARGET_STAMP_DELTA, UNKNOWN }

    public record Instrument(String venue, String venueSymbol) {
        public Instrument {
            requireText(venue, "venue");
            requireText(venueSymbol, "venueSymbol");
        }
    }

    /**
     * Latest row for one venue symbol at or before the snapshot cutoff.
     *
     * @param targetStamp nullable because the observation schema permits a venue not to publish one
     * @param fundingIntervalHours nullable when fewer than two distinct target stamps exist, or when
     *                             their difference is not a positive whole number of hours
     */
    public record PendingObservation(
            Instrument instrument,
            BigDecimal fundingRate,
            Instant observedHour,
            Instant observedAt,
            Instant targetStamp,
            Integer fundingIntervalHours,
            IntervalSource intervalSource,
            Freshness freshness) {

        public PendingObservation {
            Objects.requireNonNull(instrument, "instrument");
            Objects.requireNonNull(fundingRate, "fundingRate");
            Objects.requireNonNull(observedHour, "observedHour");
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(intervalSource, "intervalSource");
            Objects.requireNonNull(freshness, "freshness");
            if (observedHour.isAfter(observedAt)) {
                throw new IllegalArgumentException("observedHour cannot be after observedAt");
            }
            if (freshness == Freshness.MISSING) {
                throw new IllegalArgumentException("A stored pending observation cannot be MISSING");
            }
            if (fundingIntervalHours == null) {
                if (intervalSource != IntervalSource.UNKNOWN) {
                    throw new IllegalArgumentException(
                            "A missing funding interval requires IntervalSource.UNKNOWN");
                }
            } else {
                if (fundingIntervalHours <= 0) {
                    throw new IllegalArgumentException("fundingIntervalHours must be positive");
                }
                if (targetStamp == null || intervalSource != IntervalSource.TARGET_STAMP_DELTA) {
                    throw new IllegalArgumentException(
                            "A known funding interval requires a target stamp delta");
                }
            }
        }
    }

    /**
     * Pending-observation coverage for one active venue. Counts make a fresh newest row distinguishable
     * from a venue where most symbols are stale.
     */
    public record PendingVenueWatermark(
            String venue,
            Instant latestObservedAt,
            int symbolCount,
            int freshSymbolCount,
            int staleSymbolCount,
            Freshness freshness) {

        public PendingVenueWatermark {
            requireText(venue, "venue");
            Objects.requireNonNull(freshness, "freshness");
            if (symbolCount < 0 || freshSymbolCount < 0 || staleSymbolCount < 0
                    || freshSymbolCount + staleSymbolCount != symbolCount) {
                throw new IllegalArgumentException("invalid pending watermark counts");
            }
            if (freshness == Freshness.MISSING) {
                if (latestObservedAt != null || symbolCount != 0) {
                    throw new IllegalArgumentException(
                            "A missing pending watermark cannot contain observations");
                }
            } else if (latestObservedAt == null || symbolCount == 0) {
                throw new IllegalArgumentException(
                        "A present pending watermark requires observations and a timestamp");
            }
        }
    }

    /** Maximum settled funding time at or before cutoff for one active venue. */
    public record SettledVenueWatermark(
            String venue,
            Instant latestFundingTime,
            Freshness freshness) {

        public SettledVenueWatermark {
            requireText(venue, "venue");
            Objects.requireNonNull(freshness, "freshness");
            if ((freshness == Freshness.MISSING) != (latestFundingTime == null)) {
                throw new IllegalArgumentException(
                        "MISSING settled freshness and latestFundingTime must agree");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static Map<Instrument, List<PendingObservation>> singletonHistories(
            Map<Instrument, PendingObservation> pending) {
        Objects.requireNonNull(pending, "pendingByInstrument");
        return pending.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.of(entry.getValue())));
    }

    private static Map<Instrument, List<PendingObservation>> immutableHistories(
            Map<Instrument, List<PendingObservation>> histories) {
        Objects.requireNonNull(histories, "pendingHistoryByInstrument");
        return histories.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(Objects.requireNonNull(
                        entry.getValue(), "pending history"))));
    }

    private static int compareObservation(PendingObservation left, PendingObservation right) {
        int hour = left.observedHour().compareTo(right.observedHour());
        return hour != 0 ? hour : left.observedAt().compareTo(right.observedAt());
    }
}
