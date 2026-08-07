package com.smalistean.propstrategy.feature;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/** Causal detector for horizontal candle-body bases ending immediately before a breakout bar. */
public final class VariableBaseDetector {
    public record Config(int minimumBars, int maximumBars, BigDecimal minimumWidthAtr,
                         BigDecimal maximumWidthAtr, BigDecimal maximumCenterDriftAtr,
                         BigDecimal maximumCloseSlopeAtrPerBar,
                         BigDecimal maximumBoundaryPenetrationFraction,
                         BigDecimal boundaryPenetrationAtr, BigDecimal entranceDistanceAtr) {}
    public record Base(int startIndex, int endExclusive, int bars,
                       BigDecimal low, BigDecimal high) {}
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    public Optional<Base> detect(List<FeatureSnapshot> h, int breakout, FeatureKey atr, Config c) {
        if (breakout <= c.maximumBars() + 3) return Optional.empty();
        BigDecimal a = h.get(breakout).require(atr);
        for (int bars = c.maximumBars(); bars >= c.minimumBars(); bars--) {
            int start = breakout - bars;
            BigDecimal low = null, high = null;
            for (int i = start; i < breakout; i++) {
                BigDecimal o = h.get(i).require(FeatureKey.open());
                BigDecimal close = h.get(i).require(FeatureKey.close());
                low = low == null ? o.min(close) : low.min(o.min(close));
                high = high == null ? o.max(close) : high.max(o.max(close));
            }
            BigDecimal widthAtr = high.subtract(low, MC).divide(a, MC);
            if (widthAtr.compareTo(c.minimumWidthAtr()) < 0
                    || widthAtr.compareTo(c.maximumWidthAtr()) > 0) continue;
            int half = bars / 2;
            BigDecimal first = averageClose(h, start, start + half);
            BigDecimal second = averageClose(h, start + half, breakout);
            if (second.subtract(first, MC).abs().divide(a, MC)
                    .compareTo(c.maximumCenterDriftAtr()) > 0) continue;
            BigDecimal slope = h.get(breakout - 1).require(FeatureKey.close())
                    .subtract(h.get(start).require(FeatureKey.close()), MC).abs()
                    .divide(a.multiply(BigDecimal.valueOf(bars - 1L), MC), MC);
            if (slope.compareTo(c.maximumCloseSlopeAtrPerBar()) > 0) continue;
            int penetrations = 0;
            BigDecimal penetration = a.multiply(c.boundaryPenetrationAtr(), MC);
            for (int i = start; i < breakout; i++) {
                if (h.get(i).require(FeatureKey.low()).compareTo(low.subtract(penetration)) < 0
                        || h.get(i).require(FeatureKey.high()).compareTo(high.add(penetration)) > 0)
                    penetrations++;
            }
            if (BigDecimal.valueOf(penetrations).divide(BigDecimal.valueOf(bars), MC)
                    .compareTo(c.maximumBoundaryPenetrationFraction()) > 0) continue;
            BigDecimal entranceMove = h.get(start).require(FeatureKey.close())
                    .subtract(h.get(start - 3).require(FeatureKey.close()), MC).abs();
            if (entranceMove.compareTo(a.multiply(c.entranceDistanceAtr(), MC)) < 0) continue;
            return Optional.of(new Base(start, breakout, bars, low, high));
        }
        return Optional.empty();
    }

    private static BigDecimal averageClose(List<FeatureSnapshot> h, int from, int to) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = from; i < to; i++) total = total.add(h.get(i).require(FeatureKey.close()), MC);
        return total.divide(BigDecimal.valueOf(to - from), MC);
    }
}
