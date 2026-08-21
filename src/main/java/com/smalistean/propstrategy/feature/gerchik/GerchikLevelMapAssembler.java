package com.smalistean.propstrategy.feature.gerchik;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import com.smalistean.propstrategy.database.Kline;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a Gerchik level map from higher-timeframe candles and attaches, for each lower-timeframe
 * snapshot, the nearest mapped level above and below.
 *
 * <p>Written from the course material directly (see {@code GERCHIK_COURSE_REVIEW_2026-08-11.md} and
 * {@code GERCHIK_LABELLED_EXAMPLES.md}), not from the earlier {@code gerchik-level} implementation.
 *
 * <h2>What the source specifies</h2>
 * <ul>
 *   <li><b>БСУ</b> - the bar forming the level, a swing extreme on a higher timeframe. Levels are
 *       drawn on the daily "за год" and the hourly over 10-15 days.</li>
 *   <li><b>БПУ-1</b> - a later bar returning to the same price "копейка в копейку". Any number of
 *       bars may sit between them, and the level may be broken in between. A БСУ with no
 *       confirmation is not yet a level, so at least two touches are required.</li>
 *   <li><b>Зеркальный уровень</b> - support becoming resistance or vice versa; 5 of 8 levels in the
 *       labelled examples. Recorded once price has closed on both sides of the level.</li>
 *   <li><b>Persistence</b> - levels live for months; the 29.10.2017 scenario trades one drawn the
 *       previous January. Levels are never retired on a timer. Price passing through a level only
 *       changes which side it acts from, which is what makes it a mirror.</li>
 * </ul>
 *
 * <h2>Tolerance, declared in GERCHIK_V2_PREREGISTRATION.md</h2>
 * "Копейка в копейку" presumes a tick grid. On SBRF futures one point on 20003 is 0.005% of price,
 * roughly 0.05x the short-timeframe ATR, and {@code toleranceAtr} carries that ratio across.
 *
 * <p>The tolerance is fixed <em>per level</em> from the higher-timeframe true range around its own
 * forming bar, not from the evaluating bar's ATR. A level is a property of the chart, so whether a
 * historical bar touched it must not change later because current volatility moved.
 *
 * <h2>Causality</h2>
 * A pivot is emitted only once {@code strength} higher-timeframe bars have closed on both sides of
 * it. Touch counts and the mirror flag accumulate strictly forward as higher-timeframe bars close,
 * and each snapshot sees only the state reached by bars that closed at or before its own candle
 * open, so no level is ever stronger in the past than it was at the time.
 */
public final class GerchikLevelMapAssembler {

    /** Mutable accumulation of the evidence for one level. */
    private static final class Level {
        final BigDecimal price;
        final BigDecimal tolerance;
        final Instant knownAt;
        int touches;
        boolean closedAbove;
        boolean closedBelow;

        Level(BigDecimal price, BigDecimal tolerance, Instant knownAt) {
            this.price = price;
            this.tolerance = tolerance;
            this.knownAt = knownAt;
        }

        boolean mirror() {
            return closedAbove && closedBelow;
        }
    }

    public List<FeatureSnapshot> attach(List<FeatureSnapshot> lower, List<Kline> higher,
                                        int strength, BigDecimal toleranceAtr) {
        if (higher.isEmpty() || lower.isEmpty()) {
            return lower;
        }
        BigDecimal[] trueRange = trueRanges(higher);

        // Pivots, each carrying the instant it became knowable and its own fixed tolerance.
        List<Level> levels = new ArrayList<>();
        List<Integer> levelBarIndex = new ArrayList<>();
        for (int i = strength; i < higher.size() - strength; i++) {
            BigDecimal high = higher.get(i).high();
            BigDecimal low = higher.get(i).low();
            boolean pivotHigh = true;
            boolean pivotLow = true;
            for (int j = i - strength; j <= i + strength && (pivotHigh || pivotLow); j++) {
                if (j == i) continue;
                if (higher.get(j).high().compareTo(high) >= 0) pivotHigh = false;
                if (higher.get(j).low().compareTo(low) <= 0) pivotLow = false;
            }
            if (!pivotHigh && !pivotLow) continue;
            BigDecimal localAtr = averageTrueRange(trueRange, i, strength * 2);
            BigDecimal tolerance = localAtr.multiply(toleranceAtr);
            Instant knownAt = higher.get(i + strength).closeTime();
            if (pivotHigh) {
                levels.add(new Level(high, tolerance, knownAt));
                levelBarIndex.add(i);
            }
            if (pivotLow) {
                levels.add(new Level(low, tolerance, knownAt));
                levelBarIndex.add(i);
            }
        }
        if (levels.isEmpty()) {
            return lower;
        }

        // Levels keyed by price so the nearest above/below is a tree lookup rather than a scan.
        TreeMap<BigDecimal, Level> active = new TreeMap<>();
        List<FeatureSnapshot> result = new ArrayList<>(lower.size());
        int barCursor = 0;

        for (FeatureSnapshot snapshot : lower) {
            Instant now = snapshot.candleOpenTime();
            // Fold in every higher-timeframe bar that has closed since the previous snapshot.
            while (barCursor < higher.size() && !higher.get(barCursor).closeTime().isAfter(now)) {
                Kline bar = higher.get(barCursor);
                for (int k = 0; k < levels.size(); k++) {
                    if (levelBarIndex.get(k) > barCursor) continue; // level's own bar not reached
                    Level level = levels.get(k);
                    if (bar.high().compareTo(level.price.subtract(level.tolerance)) >= 0
                            && bar.low().compareTo(level.price.add(level.tolerance)) <= 0) {
                        level.touches++;
                    }
                    if (bar.close().compareTo(level.price) > 0) level.closedAbove = true;
                    if (bar.close().compareTo(level.price) < 0) level.closedBelow = true;
                    // A level enters the map once confirmed by time and by a second touch.
                    if (level.touches >= 2 && !level.knownAt.isAfter(now)) {
                        active.putIfAbsent(level.price, level);
                    }
                }
                barCursor++;
            }

            if (!snapshot.values().containsKey(FeatureKey.close()) || active.isEmpty()) {
                result.add(snapshot);
                continue;
            }
            BigDecimal price = snapshot.require(FeatureKey.close());
            Map<FeatureKey, BigDecimal> values = new HashMap<>(snapshot.values());
            var above = active.higherEntry(price);
            var below = active.lowerEntry(price);
            if (above != null) {
                values.put(FeatureKey.gerchikLevelAbove(), above.getValue().price);
                values.put(FeatureKey.gerchikLevelAboveTouches(), BigDecimal.valueOf(above.getValue().touches));
                values.put(FeatureKey.gerchikLevelAboveMirror(),
                        above.getValue().mirror() ? BigDecimal.ONE : BigDecimal.ZERO);
            }
            if (below != null) {
                values.put(FeatureKey.gerchikLevelBelow(), below.getValue().price);
                values.put(FeatureKey.gerchikLevelBelowTouches(), BigDecimal.valueOf(below.getValue().touches));
                values.put(FeatureKey.gerchikLevelBelowMirror(),
                        below.getValue().mirror() ? BigDecimal.ONE : BigDecimal.ZERO);
            }
            result.add(new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(),
                    snapshot.earliestExecutionTime(), values));
        }
        return result;
    }

    private static BigDecimal[] trueRanges(List<Kline> bars) {
        BigDecimal[] ranges = new BigDecimal[bars.size()];
        for (int i = 0; i < bars.size(); i++) {
            BigDecimal range = bars.get(i).high().subtract(bars.get(i).low());
            if (i > 0) {
                BigDecimal previousClose = bars.get(i - 1).close();
                range = range.max(bars.get(i).high().subtract(previousClose).abs())
                        .max(bars.get(i).low().subtract(previousClose).abs());
            }
            ranges[i] = range;
        }
        return ranges;
    }

    /** Backward-looking average true range ending at {@code index}, so it uses no future bar. */
    private static BigDecimal averageTrueRange(BigDecimal[] ranges, int index, int period) {
        int from = Math.max(0, index - period + 1);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = from; i <= index; i++) {
            total = total.add(ranges[i]);
        }
        return total.divide(BigDecimal.valueOf(index - from + 1),
                new java.math.MathContext(20, java.math.RoundingMode.HALF_UP));
    }
}
