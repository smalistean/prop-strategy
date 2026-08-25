package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingObservation;
import com.smalistean.propstrategy.xvf.shadow.XvfShadowConfiguration.FeeSchedule;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Pair;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Route;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ScoreStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure report-only evaluation of the frozen narrow XVF gates.
 *
 * <p>The input pair and direction come from the existing signal candidate and are never reversed.
 * This class has no repository or venue gateway and cannot alter ranks, books, or orders.
 */
public final class XvfNarrowShadowPolicy {

    public static final String POLICY_VERSION = "xvf-narrow-all-pairs-v1";

    private static final BigDecimal HOURS_PER_DAY = new BigDecimal("24");
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final BigDecimal TWO = new BigDecimal("2");
    private static final Set<String> SUPPORTED_VENUES =
            Set.of("binance", "bybit", "hyperliquid");

    public Evaluation evaluate(Input input) {
        Objects.requireNonNull(input, "input");
        Pair pair = input.pair();
        Route route = input.route();
        BigDecimal roundTripFee = roundTripFee(pair, route, input.feeSchedules());
        BigDecimal requiredFunding = scale8(roundTripFee.multiply(TWO));

        List<PairedObservation> paired = pairObservations(
                input.shortHistory(), input.longHistory());
        boolean fourPaired = paired.size() == 4;
        boolean consecutive = fourPaired && consecutiveHours(paired);
        boolean intervalsKnown = fourPaired && paired.stream().allMatch(observation ->
                observation.shortObservation().fundingIntervalHours() != null
                        && observation.longObservation().fundingIntervalHours() != null);

        List<HourlyGap> gaps = intervalsKnown
                ? paired.stream().map(XvfNarrowShadowPolicy::hourlyGap).toList()
                : List.of();
        boolean directionPositive = gaps.size() == 4
                && gaps.stream().allMatch(gap -> gap.expected24hGapBps().signum() > 0);
        BigDecimal median = gaps.size() == 4 ? median(gaps) : null;
        boolean fundingHurdlePass = median != null
                && median.compareTo(requiredFunding) > 0;
        boolean basisHurdlePass = input.entryBasisBps() != null
                && input.entryBasisBps().compareTo(roundTripFee) >= 0;
        boolean candidateScorable = input.scoreStatus() == ScoreStatus.SCORABLE;
        boolean pairSupported = supported(pair);

        List<String> reasons = new ArrayList<>();
        if (!candidateScorable) {
            reasons.add("CURRENT_SHADOW_CANDIDATE_UNSCORABLE");
        }
        if (!pairSupported) {
            reasons.add("VENUE_PAIR_NOT_SUPPORTED_BY_NARROW_V1");
        }
        if (!fourPaired) {
            reasons.add("FOUR_PAIRED_OBSERVATIONS_REQUIRED");
        } else if (!consecutive) {
            reasons.add("OBSERVATIONS_NOT_CONSECUTIVE_HOURLY");
        }
        if (fourPaired && !intervalsKnown) {
            reasons.add("FUNDING_INTERVAL_UNKNOWN");
        }
        if (intervalsKnown && !directionPositive) {
            reasons.add("FUNDING_DIRECTION_NOT_PERSISTENT");
        }
        if (median != null && !fundingHurdlePass) {
            reasons.add("MEDIAN_EXPECTED_FUNDING_NOT_ABOVE_TWO_TIMES_FEES");
        }
        if (!basisHurdlePass) {
            reasons.add(input.entryBasisBps() == null
                    ? "EXECUTABLE_ENTRY_BASIS_MISSING"
                    : "ENTRY_BASIS_BELOW_ROUND_TRIP_FEES");
        }

        boolean eligible = candidateScorable
                && pairSupported
                && fourPaired
                && consecutive
                && intervalsKnown
                && directionPositive
                && fundingHurdlePass
                && basisHurdlePass;
        BigDecimal fundingSurplus = median == null
                ? null : scale8(median.subtract(requiredFunding));
        Gates gates = new Gates(
                candidateScorable,
                pairSupported,
                fourPaired,
                consecutive,
                intervalsKnown,
                directionPositive,
                fundingHurdlePass,
                basisHurdlePass);
        return new Evaluation(
                POLICY_VERSION,
                eligible,
                pair,
                route,
                gaps,
                median,
                roundTripFee,
                requiredFunding,
                input.entryBasisBps() == null ? null : scale8(input.entryBasisBps()),
                fundingSurplus,
                gates,
                List.copyOf(reasons));
    }

    private static BigDecimal roundTripFee(
            Pair pair,
            Route route,
            Map<String, FeeSchedule> fees) {
        FeeSchedule maker = requiredFee(fees, route.makerVenue());
        FeeSchedule taker = requiredFee(fees, route.takerVenue());
        FeeSchedule shortVenue = requiredFee(fees, pair.shortVenue());
        FeeSchedule longVenue = requiredFee(fees, pair.longVenue());
        return scale8(maker.makerBps()
                .add(taker.takerBps())
                .add(shortVenue.takerBps())
                .add(longVenue.takerBps()));
    }

    private static FeeSchedule requiredFee(Map<String, FeeSchedule> fees, String venue) {
        FeeSchedule fee = fees.get(venue);
        if (fee == null) {
            throw new IllegalArgumentException("Missing fee schedule for " + venue);
        }
        return fee;
    }

    private static List<PairedObservation> pairObservations(
            List<PendingObservation> shortHistory,
            List<PendingObservation> longHistory) {
        Map<Instant, PendingObservation> shortByHour = byHour(shortHistory);
        Map<Instant, PendingObservation> longByHour = byHour(longHistory);
        List<PairedObservation> paired = shortByHour.entrySet().stream()
                .filter(entry -> longByHour.containsKey(entry.getKey()))
                .map(entry -> new PairedObservation(
                        entry.getKey(), entry.getValue(), longByHour.get(entry.getKey())))
                .sorted(Comparator.comparing(PairedObservation::observedHour))
                .toList();
        return paired.size() <= 4 ? paired : paired.subList(paired.size() - 4, paired.size());
    }

    private static Map<Instant, PendingObservation> byHour(List<PendingObservation> history) {
        Map<Instant, PendingObservation> byHour = new LinkedHashMap<>();
        for (PendingObservation observation : history) {
            PendingObservation replaced = byHour.put(observation.observedHour(), observation);
            if (replaced != null) {
                throw new IllegalArgumentException(
                        "Funding history contains duplicate observed_hour values");
            }
        }
        return byHour;
    }

    private static boolean consecutiveHours(List<PairedObservation> paired) {
        for (int index = 1; index < paired.size(); index++) {
            if (!Duration.between(
                    paired.get(index - 1).observedHour(), paired.get(index).observedHour())
                    .equals(Duration.ofHours(1))) {
                return false;
            }
        }
        return true;
    }

    private static HourlyGap hourlyGap(PairedObservation paired) {
        PendingObservation shortObservation = paired.shortObservation();
        PendingObservation longObservation = paired.longObservation();
        BigDecimal shortHourly = shortObservation.fundingRate().divide(
                BigDecimal.valueOf(shortObservation.fundingIntervalHours()),
                20, RoundingMode.HALF_UP);
        BigDecimal longHourly = longObservation.fundingRate().divide(
                BigDecimal.valueOf(longObservation.fundingIntervalHours()),
                20, RoundingMode.HALF_UP);
        BigDecimal gap = scale8(shortHourly.subtract(longHourly)
                .multiply(HOURS_PER_DAY).multiply(BPS));
        return new HourlyGap(paired.observedHour(), gap);
    }

    private static BigDecimal median(List<HourlyGap> gaps) {
        List<BigDecimal> sorted = gaps.stream().map(HourlyGap::expected24hGapBps)
                .sorted().toList();
        return scale8(sorted.get(1).add(sorted.get(2)).divide(TWO));
    }

    private static boolean supported(Pair pair) {
        return SUPPORTED_VENUES.contains(pair.shortVenue())
                && SUPPORTED_VENUES.contains(pair.longVenue())
                && !pair.shortVenue().equals(pair.longVenue());
    }

    private static BigDecimal scale8(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP);
    }

    public record Input(
            Pair pair,
            Route route,
            ScoreStatus scoreStatus,
            BigDecimal entryBasisBps,
            List<PendingObservation> shortHistory,
            List<PendingObservation> longHistory,
            Map<String, FeeSchedule> feeSchedules) {

        public Input {
            Objects.requireNonNull(pair, "pair");
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(scoreStatus, "scoreStatus");
            shortHistory = List.copyOf(Objects.requireNonNull(shortHistory, "shortHistory"));
            longHistory = List.copyOf(Objects.requireNonNull(longHistory, "longHistory"));
            feeSchedules = Map.copyOf(Objects.requireNonNull(feeSchedules, "feeSchedules"));
            validateRoute(pair, route);
            validateHistory(pair.shortVenue(), pair.shortVenueSymbol(), shortHistory);
            validateHistory(pair.longVenue(), pair.longVenueSymbol(), longHistory);
        }

        private static void validateRoute(Pair pair, Route route) {
            Set<String> pairVenues = Set.of(pair.shortVenue(), pair.longVenue());
            if (!pairVenues.equals(Set.of(route.makerVenue(), route.takerVenue()))) {
                throw new IllegalArgumentException("Route must contain exactly the pair venues");
            }
        }

        private static void validateHistory(
                String venue,
                String venueSymbol,
                List<PendingObservation> history) {
            for (PendingObservation observation : history) {
                if (!venue.equals(observation.instrument().venue())
                        || !venueSymbol.equals(observation.instrument().venueSymbol())) {
                    throw new IllegalArgumentException(
                            "Funding history instrument does not match policy pair");
                }
            }
        }
    }

    public record Evaluation(
            String policyVersion,
            boolean eligible,
            Pair pair,
            Route route,
            List<HourlyGap> hourlyGaps,
            BigDecimal medianExpected24hGapBps,
            BigDecimal roundTripFeeBps,
            BigDecimal requiredFundingBps,
            BigDecimal entryBasisBps,
            BigDecimal fundingSurplusBps,
            Gates gates,
            List<String> rejectionReasons) {

        public Evaluation {
            hourlyGaps = List.copyOf(hourlyGaps);
            rejectionReasons = List.copyOf(rejectionReasons);
        }
    }

    public record Gates(
            boolean candidateScorable,
            boolean supportedVenuePair,
            boolean fourPairedObservations,
            boolean consecutiveHourlyObservations,
            boolean fundingIntervalsKnown,
            boolean fundingDirectionPersistent,
            boolean fundingHurdlePass,
            boolean entryBasisHurdlePass) { }

    public record HourlyGap(Instant observedHour, BigDecimal expected24hGapBps) { }

    private record PairedObservation(
            Instant observedHour,
            PendingObservation shortObservation,
            PendingObservation longObservation) { }
}
