package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.XvfConfig;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Frozen inputs for one report-only shadow decision run. */
public record XvfShadowConfiguration(
        BigDecimal capitalUsd,
        Map<String, BigDecimal> venueCapitalUsd,
        Map<String, FeeSchedule> feeSchedules,
        int plannedHoldHours,
        Duration maximumPendingFundingAge,
        Duration maximumSettledFundingAge,
        Duration maximumQuoteAge,
        Duration maximumCrossVenueQuoteSkew,
        BigDecimal maximumTakerSlippageBps,
        BigDecimal expectedBasisCaptureFactor,
        BigDecimal riskPenaltyBps,
        ZoneId productionZone,
        String codeRevision,
        String strategyVersion) {

    public static final String DEFAULT_STRATEGY_VERSION = "xvf-shadow-v1";
    public static final String MEASURED_FEE_PROVENANCE = "measured-live-fills-2026-08";
    public static final String ASSUMED_FEE_PROVENANCE = "assumption-from-xvf-config-not-live-measured";

    public XvfShadowConfiguration {
        requirePositive(capitalUsd, "capitalUsd");
        venueCapitalUsd = immutableDecimals(venueCapitalUsd, "venueCapitalUsd");
        feeSchedules = Map.copyOf(Objects.requireNonNull(feeSchedules, "feeSchedules"));
        for (String venue : XvfConfig.VENUES) {
            requirePositive(venueCapitalUsd.get(venue), "venue capital for " + venue);
            Objects.requireNonNull(feeSchedules.get(venue), "fee schedule for " + venue);
        }
        BigDecimal allocated = venueCapitalUsd.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (allocated.compareTo(capitalUsd) != 0) {
            throw new IllegalArgumentException("venue capital must sum exactly to capitalUsd");
        }
        if (plannedHoldHours <= 0) {
            throw new IllegalArgumentException("plannedHoldHours must be positive");
        }
        requirePositiveDuration(maximumPendingFundingAge, "maximumPendingFundingAge");
        requirePositiveDuration(maximumSettledFundingAge, "maximumSettledFundingAge");
        requirePositiveDuration(maximumQuoteAge, "maximumQuoteAge");
        requirePositiveDuration(maximumCrossVenueQuoteSkew, "maximumCrossVenueQuoteSkew");
        requireNonNegative(maximumTakerSlippageBps, "maximumTakerSlippageBps");
        requireNonNegative(expectedBasisCaptureFactor, "expectedBasisCaptureFactor");
        if (expectedBasisCaptureFactor.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("expectedBasisCaptureFactor cannot exceed one");
        }
        requireNonNegative(riskPenaltyBps, "riskPenaltyBps");
        Objects.requireNonNull(productionZone, "productionZone");
        requireText(codeRevision, "codeRevision");
        requireText(strategyVersion, "strategyVersion");
    }

    /**
     * Defaults mirror the current strategy and the measured fee assumptions used by the two-year
     * replay. Capital is declared, not read from an exchange account, and is split equally with the
     * rounding remainder assigned to the final venue.
     */
    public static XvfShadowConfiguration fromSystemProperties() {
        BigDecimal capital = new BigDecimal(System.getProperty("xvfCapital", "10000"));
        Map<String, BigDecimal> allocations = declaredAllocations(capital);
        return new XvfShadowConfiguration(
                capital,
                allocations,
                configuredFees(),
                XvfConfig.REBALANCE_DAYS * 24,
                Duration.ofMinutes(Long.parseLong(
                        System.getProperty("xvfShadowMaximumFundingAgeMinutes", "100"))),
                Duration.ofHours(Long.parseLong(
                        System.getProperty("xvfShadowMaximumSettledAgeHours", "36"))),
                Duration.ofSeconds(Long.parseLong(
                        System.getProperty("xvfShadowMaximumQuoteAgeSeconds", "30"))),
                Duration.ofSeconds(Long.parseLong(
                        System.getProperty("xvfShadowMaximumQuoteSkewSeconds", "30"))),
                new BigDecimal(System.getProperty("xvfShadowMaximumSlippageBps",
                        Double.toString(XvfConfig.MAX_TAKER_SLIPPAGE_BPS))),
                new BigDecimal(System.getProperty("xvfShadowExpectedBasisCaptureFactor", "0")),
                new BigDecimal(System.getProperty("xvfShadowRiskPenaltyBps", "0")),
                ZoneId.of(System.getProperty("xvfZone", "Europe/Chisinau")),
                requiredCodeRevision(),
                System.getProperty("xvfShadowStrategyVersion", DEFAULT_STRATEGY_VERSION));
    }

    static Map<String, FeeSchedule> measuredFees() {
        return Map.of(
                "binance", new FeeSchedule(new BigDecimal("1.8"), new BigDecimal("4.5"),
                        ASSUMED_FEE_PROVENANCE, MEASURED_FEE_PROVENANCE),
                "bybit", new FeeSchedule(new BigDecimal("3.6"), new BigDecimal("10.0"),
                        MEASURED_FEE_PROVENANCE, MEASURED_FEE_PROVENANCE),
                "hyperliquid", new FeeSchedule(new BigDecimal("1.8"), new BigDecimal("4.5"),
                        ASSUMED_FEE_PROVENANCE, MEASURED_FEE_PROVENANCE));
    }

    private static Map<String, FeeSchedule> configuredFees() {
        Map<String, FeeSchedule> defaults = measuredFees();
        Map<String, FeeSchedule> out = new LinkedHashMap<>();
        for (String venue : XvfConfig.VENUES) {
            FeeSchedule fallback = defaults.get(venue);
            String prefix = "xvfShadowFee." + venue + ".";
            BigDecimal maker = new BigDecimal(System.getProperty(prefix + "makerBps",
                    fallback.makerBps().toPlainString()));
            BigDecimal taker = new BigDecimal(System.getProperty(prefix + "takerBps",
                    fallback.takerBps().toPlainString()));
            String makerProvenance = System.getProperty(prefix + "makerProvenance",
                    fallback.makerProvenance());
            String takerProvenance = System.getProperty(prefix + "takerProvenance",
                    fallback.takerProvenance());
            out.put(venue, new FeeSchedule(maker, taker, makerProvenance, takerProvenance));
        }
        return out;
    }

    private static Map<String, BigDecimal> declaredAllocations(BigDecimal capital) {
        boolean overridden = false;
        for (String venue : XvfConfig.VENUES) {
            overridden |= System.getProperty("xvfShadowCapital." + venue) != null;
        }
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (overridden) {
            for (String venue : XvfConfig.VENUES) {
                String value = System.getProperty("xvfShadowCapital." + venue);
                if (value == null) {
                    throw new IllegalArgumentException(
                            "When one xvfShadowCapital.<venue> is set, all venues must be set");
                }
                out.put(venue, new BigDecimal(value));
            }
            return out;
        }
        BigDecimal equal = capital.divide(BigDecimal.valueOf(XvfConfig.VENUES.length),
                12, RoundingMode.DOWN);
        BigDecimal assigned = BigDecimal.ZERO;
        for (int index = 0; index < XvfConfig.VENUES.length; index++) {
            String venue = XvfConfig.VENUES[index];
            BigDecimal amount = index == XvfConfig.VENUES.length - 1
                    ? capital.subtract(assigned) : equal;
            out.put(venue, amount);
            assigned = assigned.add(amount);
        }
        return out;
    }

    private static Map<String, BigDecimal> immutableDecimals(Map<String, BigDecimal> source,
                                                               String name) {
        Objects.requireNonNull(source, name);
        Map<String, BigDecimal> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            requireText(key, name + " key");
            copy.put(key, Objects.requireNonNull(value, name + " value"));
        });
        return Map.copyOf(copy);
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requirePositiveDuration(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String requiredCodeRevision() {
        String revision = System.getProperty("xvfCodeRevision");
        if (revision == null || revision.isBlank()) {
            revision = System.getenv("GIT_COMMIT");
        }
        if (revision == null || revision.isBlank()) {
            throw new IllegalArgumentException(
                    "Set -DxvfCodeRevision=<git SHA/build id> or GIT_COMMIT for an auditable run");
        }
        return revision;
    }

    public record FeeSchedule(
            BigDecimal makerBps,
            BigDecimal takerBps,
            String makerProvenance,
            String takerProvenance) {
        public FeeSchedule {
            requireNonNegative(makerBps, "makerBps");
            requireNonNegative(takerBps, "takerBps");
            requireText(makerProvenance, "makerProvenance");
            requireText(takerProvenance, "takerProvenance");
        }
    }
}
