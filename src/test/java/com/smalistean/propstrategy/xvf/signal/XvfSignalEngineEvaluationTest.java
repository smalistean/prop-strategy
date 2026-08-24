package com.smalistean.propstrategy.xvf.signal;

import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Candidate;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.EvaluatedPair;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Leg;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.PairType;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.SignalEvaluation;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Characterizes the production projection while exposing the larger diagnostic candidate set. */
class XvfSignalEngineEvaluationTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 8, 21);
    private static final double LIQUID = 2_000_000;

    @Test
    void preservesWidestPairFreshnessDiscountFilteringAndProductionOrder() {
        List<Leg> today = List.of(
                leg("binance", "BTCUSDT", 50, LIQUID),
                leg("bybit", "BTCUSDT", 10, LIQUID),
                leg("binance", "ETHUSDT", 42, LIQUID),
                leg("bybit", "ETHUSDT", 10, LIQUID),
                leg("binance", "SOLUSDT", 35, LIQUID),
                leg("bybit", "SOLUSDT", 10, LIQUID));
        List<Leg> yesterday = List.of(
                leg("binance", "BTCUSDT", 50, LIQUID),
                leg("bybit", "BTCUSDT", 10, LIQUID),
                leg("binance", "SOLUSDT", 35, LIQUID),
                leg("bybit", "SOLUSDT", 10, LIQUID));

        SignalEvaluation evaluation = XvfSignalEngine.evaluateLoadedLegs(AS_OF, today, yesterday);

        // Old production semantics: BTC is discounted from 40 to 26, ETH stays fresh at 32,
        // and stale SOL falls from 25 to 16.25 and is removed by the adjusted threshold.
        assertEquals(List.of("ETH", "BTC"), evaluation.baselineFullBook().stream()
                .map(Candidate::base).toList());
        assertEquals(32.0, evaluation.baselineFullBook().get(0).spreadAnnualPct(), 1e-12);
        assertEquals(26.0, evaluation.baselineFullBook().get(1).spreadAnnualPct(), 1e-12);

        assertEquals(List.of("BTC", "ETH", "SOL"), evaluation.alternatives().stream()
                .map(pair -> pair.alternative().base()).toList());
        EvaluatedPair btc = byBase(evaluation, "BTC");
        assertTrue(btc.eligibleYesterday());
        assertEquals(XvfConfig.STALE_SIGNAL_DISCOUNT, btc.staleDiscountFactor(), 0.0);
        assertEquals(40.0, btc.alternative().rawSpreadAnnualPct(), 1e-12);
        assertEquals(26.0, btc.adjustedSpreadAnnualPct(), 1e-12);
        assertEquals(2, btc.baselineBookRank());

        EvaluatedPair sol = byBase(evaluation, "SOL");
        assertTrue(sol.rawSpreadPass());
        assertTrue(sol.volumePass());
        assertFalse(sol.adjustedSpreadPass());
        assertNull(sol.baselineBookRank());
    }

    @Test
    void exposesEveryUniqueCrossVenuePairButNeverASameVenuePair() {
        List<Leg> legs = List.of(
                leg("binance", "KAITOUSDT", 60, LIQUID),
                leg("binance", "KAITOUSDC", 50, LIQUID),
                leg("bybit", "KAITOUSDT", 10, LIQUID),
                leg("hyperliquid", "KAITO", 0, LIQUID));

        SignalEvaluation evaluation = XvfSignalEngine.evaluateLoadedLegs(AS_OF, legs, List.of());

        // Six unordered combinations minus the one Binance/Binance combination.
        assertEquals(5, evaluation.alternatives().size());
        assertTrue(evaluation.alternatives().stream().noneMatch(pair ->
                pair.alternative().shortLeg().venue().equals(pair.alternative().longLeg().venue())));
        assertEquals(2, evaluation.alternatives().stream()
                .filter(pair -> pair.alternative().pairType() == PairType.CEX_CEX).count());
        assertEquals(3, evaluation.alternatives().stream()
                .filter(pair -> pair.alternative().pairType() == PairType.CEX_DEX).count());
        assertEquals(1, evaluation.alternatives().stream()
                .filter(EvaluatedPair::widestForBase).count());
        assertEquals("hyperliquid", evaluation.baselineFullBook().getFirst().longLeg().venue());
    }

    @Test
    void doesNotBackfillANarrowerLiquidPairWhenTheWidestPairIsIlliquid() {
        List<Leg> legs = List.of(
                leg("binance", "ILLUSDT", 60, 100_000),
                leg("bybit", "ILLUSDT", 30, LIQUID),
                leg("hyperliquid", "ILL", 0, LIQUID));

        SignalEvaluation evaluation = XvfSignalEngine.evaluateLoadedLegs(AS_OF, legs, List.of());

        assertTrue(evaluation.baselineFullBook().isEmpty(),
                "production currently rejects the base when its widest pair fails liquidity");
        assertEquals(3, evaluation.alternatives().size());
        EvaluatedPair narrowerLiquid = evaluation.alternatives().stream()
                .filter(pair -> pair.alternative().shortLeg().venue().equals("bybit"))
                .filter(pair -> pair.alternative().longLeg().venue().equals("hyperliquid"))
                .findFirst().orElseThrow();
        assertFalse(narrowerLiquid.widestForBase());
        assertTrue(narrowerLiquid.rawSpreadPass());
        assertTrue(narrowerLiquid.volumePass());
        assertTrue(narrowerLiquid.adjustedSpreadPass());
        assertNull(narrowerLiquid.baselineBookRank());
    }

    @Test
    void retainsTheExistingStrictSpreadAndInclusiveVolumeBoundaries() {
        double staleRawAtAdjustedThreshold =
                XvfConfig.MIN_SPREAD_ANNUAL_PCT / XvfConfig.STALE_SIGNAL_DISCOUNT;
        List<Leg> today = List.of(
                leg("binance", "RAWUSDT", XvfConfig.MIN_SPREAD_ANNUAL_PCT, LIQUID),
                leg("bybit", "RAWUSDT", 0, LIQUID),
                leg("binance", "VOLUSDT", 30, XvfConfig.MIN_WEEKLY_QUOTE_VOLUME),
                leg("bybit", "VOLUSDT", 0, XvfConfig.MIN_WEEKLY_QUOTE_VOLUME),
                leg("binance", "ADJUSDT", staleRawAtAdjustedThreshold, LIQUID),
                leg("bybit", "ADJUSDT", 0, LIQUID));
        List<Leg> yesterday = List.of(
                leg("binance", "ADJUSDT", staleRawAtAdjustedThreshold, LIQUID),
                leg("bybit", "ADJUSDT", 0, LIQUID));

        SignalEvaluation evaluation = XvfSignalEngine.evaluateLoadedLegs(AS_OF, today, yesterday);

        EvaluatedPair rawBoundary = byBase(evaluation, "RAW");
        assertEquals(XvfConfig.MIN_SPREAD_ANNUAL_PCT,
                rawBoundary.alternative().rawSpreadAnnualPct(), 1e-12);
        assertFalse(rawBoundary.rawSpreadPass(), "the existing raw gate is strictly greater than");

        EvaluatedPair volumeBoundary = byBase(evaluation, "VOL");
        assertEquals(XvfConfig.MIN_WEEKLY_QUOTE_VOLUME,
                volumeBoundary.alternative().thinLegWeeklyVolume(), 0.0);
        assertTrue(volumeBoundary.volumePass(), "the existing volume gate is greater-than-or-equal");
        assertEquals(1, volumeBoundary.baselineBookRank());

        EvaluatedPair adjustedBoundary = byBase(evaluation, "ADJ");
        assertEquals(XvfConfig.MIN_SPREAD_ANNUAL_PCT,
                adjustedBoundary.adjustedSpreadAnnualPct(), 1e-12);
        assertFalse(adjustedBoundary.adjustedSpreadPass(),
                "the existing post-discount gate is strictly greater than");
        assertNull(adjustedBoundary.baselineBookRank());
    }

    @Test
    void yesterdayEligibilityRemainsBaseLevelWhenTheVenuePairChanges() {
        List<Leg> today = List.of(
                leg("binance", "SWITCHUSDT", 40, LIQUID),
                leg("hyperliquid", "SWITCH", 0, LIQUID));
        List<Leg> yesterday = List.of(
                leg("binance", "SWITCHUSDT", 40, LIQUID),
                leg("bybit", "SWITCHUSDT", 0, LIQUID));

        EvaluatedPair switched = XvfSignalEngine.evaluateLoadedLegs(AS_OF, today, yesterday)
                .alternatives().getFirst();

        assertTrue(switched.eligibleYesterday());
        assertEquals("hyperliquid", switched.alternative().longLeg().venue());
        assertEquals(40 * XvfConfig.STALE_SIGNAL_DISCOUNT,
                switched.adjustedSpreadAnnualPct(), 1e-12);
    }

    @Test
    void diagnosticRanksAreDeterministicAndReturnedListsAreImmutable() {
        List<Leg> forward = List.of(
                leg("binance", "BBBUSDT", 30, LIQUID),
                leg("bybit", "BBBUSDT", 0, LIQUID),
                leg("binance", "AAAUSDT", 30, LIQUID),
                leg("bybit", "AAAUSDT", 0, LIQUID));
        List<Leg> reverse = new ArrayList<>(forward);
        java.util.Collections.reverse(reverse);

        SignalEvaluation first = XvfSignalEngine.evaluateLoadedLegs(AS_OF, forward, List.of());
        SignalEvaluation second = XvfSignalEngine.evaluateLoadedLegs(AS_OF, reverse, List.of());

        assertEquals(List.of("AAA", "BBB"), first.alternatives().stream()
                .map(pair -> pair.alternative().base()).toList());
        assertEquals(first.alternatives().stream().map(pair -> pair.alternative().base()).toList(),
                second.alternatives().stream().map(pair -> pair.alternative().base()).toList());
        assertEquals(List.of(1, 2), first.alternatives().stream()
                .map(EvaluatedPair::grossRank).toList());
        assertThrows(UnsupportedOperationException.class, () -> first.alternatives().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.baselineFullBook().clear());
    }

    @Test
    void rejectsANewCandidateWhenTheShortVenueIsAlreadyAdverselyCheapInPrice() {
        List<Leg> today = List.of(
                legWithPrice("binance", "ADVUSDT", 40, LIQUID, 99.0),
                legWithPrice("bybit", "ADVUSDT", 0, LIQUID, 100.0));
        // ln(99/100)*10000 ~= -100.5bp, past XvfConfig.ADVERSE_ENTRY_BASIS_FLOOR_BPS.

        SignalEvaluation evaluation = XvfSignalEngine.evaluateLoadedLegs(AS_OF, today, List.of());

        assertTrue(evaluation.baselineFullBook().isEmpty(),
                "an adverse entry basis rejects an otherwise-eligible new candidate");
    }

    @Test
    void aMissingLivePriceDoesNotBlockAnOtherwiseEligibleCandidate() {
        List<Leg> today = List.of(
                leg("binance", "NOPXUSDT", 40, LIQUID),
                leg("bybit", "NOPXUSDT", 0, LIQUID));

        SignalEvaluation evaluation = XvfSignalEngine.evaluateLoadedLegs(AS_OF, today, List.of());

        assertEquals(List.of("NOPX"), evaluation.baselineFullBook().stream()
                .map(Candidate::base).toList());
    }

    private static EvaluatedPair byBase(SignalEvaluation evaluation, String base) {
        return evaluation.alternatives().stream()
                .filter(pair -> pair.alternative().base().equals(base))
                .findFirst().orElseThrow();
    }

    private static Leg leg(String venue, String symbol, double annualPct, double weeklyVolume) {
        return legWithPrice(venue, symbol, annualPct, weeklyVolume, 0.0);
    }

    /** {@code price} of 0.0 means "unmeasured", matching {@link Leg}'s own convention. */
    private static Leg legWithPrice(String venue, String symbol, double annualPct,
                                    double weeklyVolume, double price) {
        double trailingRate = annualPct
                / (365.0 / XvfConfig.LOOKBACK_DAYS * 100);
        return new Leg(venue, symbol, trailingRate, trailingRate, weeklyVolume, price);
    }
}
