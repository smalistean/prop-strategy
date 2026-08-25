package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Freshness;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Instrument;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.IntervalSource;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingObservation;
import com.smalistean.propstrategy.xvf.shadow.XvfNarrowShadowPolicy.Evaluation;
import com.smalistean.propstrategy.xvf.shadow.XvfNarrowShadowPolicy.Input;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Pair;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.PairType;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Route;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ScoreStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XvfNarrowShadowPolicyTest {

    private static final Instant DAY = Instant.parse("2026-08-21T00:00:00Z");
    private static final int[] CONSECUTIVE_HOURS = {6, 7, 8, 9};
    private static final XvfNarrowShadowPolicy POLICY = new XvfNarrowShadowPolicy();

    @Test
    void acceptsAllThreeSupportedVenuePairsWithPairSpecificRouteFees() {
        Evaluation binanceBybit = evaluate(
                pair("binance", "BTCUSDT", "bybit", "BTCUSDT", PairType.CEX_CEX),
                new Route("bybit", "binance", 24), "0.0100", "100", CONSECUTIVE_HOURS);
        Evaluation binanceHyperliquid = evaluate(
                pair("binance", "ETHUSDT", "hyperliquid", "ETH", PairType.CEX_DEX),
                new Route("hyperliquid", "binance", 24), "0.0100", "100", CONSECUTIVE_HOURS);
        Evaluation bybitHyperliquid = evaluate(
                pair("bybit", "SOLUSDT", "hyperliquid", "SOL", PairType.CEX_DEX),
                new Route("bybit", "hyperliquid", 24), "0.0100", "100", CONSECUTIVE_HOURS);
        Evaluation hyperliquidMaker = evaluate(
                pair("bybit", "XRPUSDT", "hyperliquid", "XRP", PairType.CEX_DEX),
                new Route("hyperliquid", "bybit", 24), "0.0100", "100", CONSECUTIVE_HOURS);

        assertTrue(binanceBybit.eligible());
        assertDecimal("22.60000000", binanceBybit.roundTripFeeBps());
        assertDecimal("45.20000000", binanceBybit.requiredFundingBps());
        assertTrue(binanceHyperliquid.eligible());
        assertDecimal("15.30000000", binanceHyperliquid.roundTripFeeBps());
        assertDecimal("30.60000000", binanceHyperliquid.requiredFundingBps());
        assertTrue(bybitHyperliquid.eligible());
        assertDecimal("22.60000000", bybitHyperliquid.roundTripFeeBps());
        assertTrue(hyperliquidMaker.eligible());
        assertDecimal("26.30000000", hyperliquidMaker.roundTripFeeBps());
    }

    @Test
    void fundingMedianMustBeStrictlyAboveTwiceTheCompleteRouteFee() {
        Pair pair = pair("binance", "BTCUSDT", "bybit", "BTCUSDT", PairType.CEX_CEX);
        Route route = new Route("bybit", "binance", 24);

        Evaluation equal = evaluate(pair, route, "0.00452", "22.6", CONSECUTIVE_HOURS);
        Evaluation above = evaluate(pair, route, "0.00453", "22.6", CONSECUTIVE_HOURS);

        assertDecimal("45.20000000", equal.medianExpected24hGapBps());
        assertFalse(equal.gates().fundingHurdlePass());
        assertFalse(equal.eligible());
        assertTrue(equal.rejectionReasons().contains(
                "MEDIAN_EXPECTED_FUNDING_NOT_ABOVE_TWO_TIMES_FEES"));
        assertDecimal("45.30000000", above.medianExpected24hGapBps());
        assertTrue(above.gates().fundingHurdlePass());
        assertTrue(above.eligible());
    }

    @Test
    void entryBasisMayEqualTheFeeHurdleButCannotFallBelowIt() {
        Pair pair = pair("binance", "BTCUSDT", "bybit", "BTCUSDT", PairType.CEX_CEX);
        Route route = new Route("bybit", "binance", 24);

        Evaluation equal = evaluate(pair, route, "0.00500", "22.6", CONSECUTIVE_HOURS);
        Evaluation below = evaluate(pair, route, "0.00500", "22.59999999", CONSECUTIVE_HOURS);

        assertTrue(equal.gates().entryBasisHurdlePass());
        assertTrue(equal.eligible());
        assertFalse(below.gates().entryBasisHurdlePass());
        assertFalse(below.eligible());
        assertTrue(below.rejectionReasons().contains("ENTRY_BASIS_BELOW_ROUND_TRIP_FEES"));
    }

    @Test
    void rejectsFourObservationsWhenTheirHoursAreNotConsecutive() {
        Evaluation evaluation = evaluate(
                pair("binance", "ETHUSDT", "hyperliquid", "ETH", PairType.CEX_DEX),
                new Route("hyperliquid", "binance", 24),
                "0.00500", "100", new int[]{5, 6, 8, 9});

        assertTrue(evaluation.gates().fourPairedObservations());
        assertFalse(evaluation.gates().consecutiveHourlyObservations());
        assertFalse(evaluation.eligible());
        assertTrue(evaluation.rejectionReasons().contains(
                "OBSERVATIONS_NOT_CONSECUTIVE_HOURLY"));
    }

    @Test
    void rejectsWhenOnlyThreeHoursPairAcrossTheTwoVenues() {
        Pair pair = pair("binance", "BTCUSDT", "bybit", "BTCUSDT", PairType.CEX_CEX);
        Input input = new Input(
                pair,
                new Route("bybit", "binance", 24),
                ScoreStatus.SCORABLE,
                new BigDecimal("100"),
                history(pair.shortVenue(), pair.shortVenueSymbol(), "0.005", CONSECUTIVE_HOURS),
                history(pair.longVenue(), pair.longVenueSymbol(), "0", new int[]{7, 8, 9}),
                XvfShadowConfiguration.measuredFees());

        Evaluation evaluation = POLICY.evaluate(input);

        assertFalse(evaluation.gates().fourPairedObservations());
        assertFalse(evaluation.eligible());
        assertTrue(evaluation.rejectionReasons().contains("FOUR_PAIRED_OBSERVATIONS_REQUIRED"));
    }

    @Test
    void preservesCurrentSignalDirectionAndRejectsAnOppositeFundingGap() {
        Evaluation evaluation = evaluate(
                pair("binance", "BTCUSDT", "bybit", "BTCUSDT", PairType.CEX_CEX),
                new Route("bybit", "binance", 24), "-0.005", "100", CONSECUTIVE_HOURS);

        assertEquals("binance", evaluation.pair().shortVenue());
        assertFalse(evaluation.gates().fundingDirectionPersistent());
        assertFalse(evaluation.eligible());
        assertTrue(evaluation.rejectionReasons().contains("FUNDING_DIRECTION_NOT_PERSISTENT"));
    }

    @Test
    void rejectsAnUnknownFundingIntervalWithoutInventingAZeroOrCadence() {
        Pair pair = pair("binance", "BTCUSDT", "bybit", "BTCUSDT", PairType.CEX_CEX);
        List<PendingObservation> shortHistory = history(
                pair.shortVenue(), pair.shortVenueSymbol(), "0.005", CONSECUTIVE_HOURS);
        List<PendingObservation> longHistory = history(
                pair.longVenue(), pair.longVenueSymbol(), "0", CONSECUTIVE_HOURS);
        PendingObservation unknown = longHistory.get(1);
        java.util.ArrayList<PendingObservation> changed = new java.util.ArrayList<>(longHistory);
        changed.set(1, new PendingObservation(
                unknown.instrument(), unknown.fundingRate(), unknown.observedHour(),
                unknown.observedAt(), null, null, IntervalSource.UNKNOWN, Freshness.FRESH));
        Input input = new Input(
                pair,
                new Route("bybit", "binance", 24),
                ScoreStatus.SCORABLE,
                new BigDecimal("100"),
                shortHistory,
                changed,
                XvfShadowConfiguration.measuredFees());

        Evaluation evaluation = POLICY.evaluate(input);

        assertFalse(evaluation.gates().fundingIntervalsKnown());
        assertFalse(evaluation.eligible());
        assertTrue(evaluation.rejectionReasons().contains("FUNDING_INTERVAL_UNKNOWN"));
        assertTrue(evaluation.hourlyGaps().isEmpty());
    }

    private static Evaluation evaluate(
            Pair pair,
            Route route,
            String shortRate,
            String entryBasisBps,
            int[] hours) {
        Input input = new Input(
                pair,
                route,
                ScoreStatus.SCORABLE,
                new BigDecimal(entryBasisBps),
                history(pair.shortVenue(), pair.shortVenueSymbol(), shortRate, hours),
                history(pair.longVenue(), pair.longVenueSymbol(), "0", hours),
                XvfShadowConfiguration.measuredFees());
        return POLICY.evaluate(input);
    }

    private static Pair pair(
            String shortVenue,
            String shortSymbol,
            String longVenue,
            String longSymbol,
            PairType pairType) {
        return new Pair("TEST", pairType,
                shortVenue, shortSymbol, longVenue, longSymbol);
    }

    private static List<PendingObservation> history(
            String venue,
            String symbol,
            String rate,
            int[] hours) {
        Instrument instrument = new Instrument(venue, symbol);
        return java.util.Arrays.stream(hours).mapToObj(hour -> {
            Instant observedHour = DAY.plusSeconds(hour * 3_600L);
            return new PendingObservation(
                    instrument,
                    new BigDecimal(rate),
                    observedHour,
                    observedHour.plusSeconds(50 * 60L),
                    DAY.plusSeconds(24 * 3_600L),
                    24,
                    IntervalSource.TARGET_STAMP_DELTA,
                    Freshness.FRESH);
        }).toList();
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> expected + " != " + actual);
    }
}
