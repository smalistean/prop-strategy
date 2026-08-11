package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.database.PostgresVolumeProfileBinRepository;
import com.smalistean.propstrategy.database.VolumeProfileBin;
import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.ParameterizedFeatureGenerator;
import com.smalistean.propstrategy.feature.VariableBaseDetector;
import com.smalistean.propstrategy.feature.VariableBaseDetectorV5;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Ad hoc recall check: does VariableBaseDetectorV5 find a base whose window and boundaries roughly
 * match a source-labelled example? This is not a general labelling harness, just enough to compare
 * the two examples in APOLLO_LABELLED_EXAMPLES.md with real, exact price/date data (the August 2026
 * BTC/ETH screenshots already interpreted in APOLLO_COURSE_SOURCE_NOTES.md). A candidate is reported
 * as a "near miss" if its window overlaps the labelled date and its low/high is within the given
 * tolerance of the labelled boundary; this deliberately does not require an exact match, since the
 * course selects bases visually.
 */
public final class ApolloV5LabelComparisonApplication {
    private ApolloV5LabelComparisonApplication() {}

    public static void main(String[] args) {
        String symbol = System.getProperty("labelSymbol", "ETHUSDT");
        Instant windowStart = Instant.parse(System.getProperty("labelWindowStart", "2026-07-01T00:00:00Z"));
        Instant windowEnd = Instant.parse(System.getProperty("labelWindowEnd", "2026-08-10T00:00:00Z"));
        Instant expectedStart = Instant.parse(System.getProperty("labelExpectedStart", "2026-08-01T00:00:00Z"));
        Instant expectedEnd = Instant.parse(System.getProperty("labelExpectedEnd", "2026-08-05T00:00:00Z"));
        BigDecimal expectedLow = new BigDecimal(System.getProperty("labelExpectedLow", "1827"));
        BigDecimal expectedHigh = new BigDecimal(System.getProperty("labelExpectedHigh", "1986"));
        BigDecimal tolerance = new BigDecimal(System.getProperty("labelTolerance", "40"));
        Integer dumpBars = System.getProperty("labelDumpBars") == null ? null
                : Integer.valueOf(System.getProperty("labelDumpBars"));

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        PostgresKlineRepository klineRepository = new PostgresKlineRepository(database);
        int atrPeriod = 14, volumePeriod = 20;
        int lookbackBars = 7 * 96; // matches strategy.baseMapLookbackDays=7 in apollo-v5-btc.properties
        int warmupCandles = Math.max(lookbackBars, 50) + 10;
        List<Kline> candles = klineRepository.findRangeWithWarmup(symbol, "15m", windowStart, windowEnd, warmupCandles);
        List<FeatureSnapshot> technical = new ParameterizedFeatureGenerator().generate(candles,
                Set.of(FeatureKey.open(), FeatureKey.close(), FeatureKey.high(), FeatureKey.low(),
                        FeatureKey.atr(atrPeriod), FeatureKey.volumeRatio(volumePeriod)));

        int bucketMinutes = 15;
        BigDecimal priceStep = new BigDecimal("10");
        Instant profileStart = windowStart.minusSeconds((long) lookbackBars * bucketMinutes * 60 * 2);
        List<VolumeProfileBin> bins = new PostgresVolumeProfileBinRepository(database)
                .findRange(symbol, bucketMinutes, priceStep, profileStart, windowEnd);

        VariableBaseDetector.Config detectorConfig = new VariableBaseDetector.Config(
                12, lookbackBars, new BigDecimal("0.75"), new BigDecimal("2.50"),
                new BigDecimal("0.35"), new BigDecimal("0.025"), new BigDecimal("0.35"),
                new BigDecimal("0.10"), new BigDecimal("0.25"));
        int referenceBars = 48;
        FeatureKey atr = FeatureKey.atr(atrPeriod);

        VariableBaseDetectorV5 detector = new VariableBaseDetectorV5();
        int found = 0, nearMisses = 0;
        System.out.printf("Scanning %s [%s, %s) for candidates overlapping labelled window [%s, %s) "
                        + "near [%s, %s] +/- %s%n",
                symbol, windowStart, windowEnd, expectedStart, expectedEnd, expectedLow, expectedHigh, tolerance);
        for (int breakout = 0; breakout < technical.size(); breakout++) {
            List<VariableBaseDetectorV5.Base> candidates = detector.detectCandidates(
                    technical, breakout, atr, detectorConfig, referenceBars);
            for (VariableBaseDetectorV5.Base base : candidates) {
                Instant baseStart = technical.get(base.startIndex()).candleOpenTime();
                Instant baseEnd = technical.get(base.endExclusive() - 1).candleOpenTime();
                boolean overlapsDate = !baseEnd.isBefore(expectedStart) && !baseStart.isAfter(expectedEnd);
                if (!overlapsDate) continue;
                boolean lowNear = base.low().subtract(expectedLow).abs().compareTo(tolerance) <= 0;
                boolean highNear = base.high().subtract(expectedHigh).abs().compareTo(tolerance) <= 0;
                if (lowNear || highNear) {
                    found++;
                    System.out.printf("MATCH  bars=%d window=[%s, %s] low=%s high=%s highTouches=%d lowTouches=%d%n",
                            base.bars(), baseStart, baseEnd, base.low(), base.high(),
                            base.highTouches(), base.lowTouches());
                    if (dumpBars != null && base.bars() == dumpBars) {
                        dumpTouchRuns(technical, base, atr, detectorConfig, lowNear);
                    }
                } else {
                    nearMisses++;
                }
            }
        }
        System.out.printf("Done. Candidates overlapping the labelled date window: %d matched the price band, "
                        + "%d overlapped the date but not the price band.%n", found, nearMisses);
        if (found == 0) {
            System.out.println("RECALL MISS: no candidate near the labelled boundary was ever generated "
                    + "for this window, regardless of downstream breakout/touch/profile filtering.");
        }
    }

    /** Prints each contiguous touch run's bar range and extreme price, replicating VariableBaseDetectorV5's
     * countTouches algorithm exactly, so we can see whether a reported touch count is real repeated
     * testing of the level or a counting artifact from noise inside a wide multi-day window. */
    private static void dumpTouchRuns(List<FeatureSnapshot> h, VariableBaseDetectorV5.Base base,
                                       FeatureKey atr, VariableBaseDetector.Config c, boolean lowSide) {
        BigDecimal a = h.get(base.endExclusive()).require(atr);
        BigDecimal tolerance = a.multiply(c.boundaryPenetrationAtr());
        BigDecimal boundary = lowSide ? base.low() : base.high();
        System.out.printf("---- touch-run detail: %s boundary=%s atrAtBreakout=%s tolerance=%s ----%n",
                lowSide ? "LOW" : "HIGH", boundary, a, tolerance);
        boolean inTouch = false;
        Instant runStart = null;
        BigDecimal runExtreme = null;
        int runCount = 0;
        for (int i = base.startIndex(); i < base.endExclusive(); i++) {
            FeatureSnapshot s = h.get(i);
            // Mirrors VariableBaseDetectorV5.countTouches exactly: one-sided, so a wick anywhere at
            // or beyond the boundary (including penetrations) counts, not just a symmetric band.
            BigDecimal wick = lowSide ? s.require(FeatureKey.low()) : s.require(FeatureKey.high());
            boolean near = lowSide ? wick.compareTo(boundary.add(tolerance)) <= 0
                    : wick.compareTo(boundary.subtract(tolerance)) >= 0;
            if (near) {
                if (!inTouch) { runStart = s.candleOpenTime(); runExtreme = wick; inTouch = true; }
                else runExtreme = lowSide ? runExtreme.min(wick) : runExtreme.max(wick);
            } else if (inTouch) {
                runCount++;
                System.out.printf("  touch #%d: %s -> %s extreme=%s%n", runCount, runStart,
                        h.get(i - 1).candleOpenTime(), runExtreme);
                inTouch = false;
            }
        }
        if (inTouch) {
            runCount++;
            System.out.printf("  touch #%d: %s -> %s (window end) extreme=%s%n", runCount, runStart,
                    h.get(base.endExclusive() - 1).candleOpenTime(), runExtreme);
        }
        System.out.printf("---- total touch runs: %d ----%n", runCount);
    }
}
