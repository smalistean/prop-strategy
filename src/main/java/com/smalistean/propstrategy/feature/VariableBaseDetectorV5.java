package com.smalistean.propstrategy.feature;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Multi-day base search used by Apollo V5. Distinct from {@link VariableBaseDetector} (V4 and
 * earlier), which only ever anchors a window immediately before its own breakout bar and is
 * calibrated at the 12-48 candle scale. This class shares {@link VariableBaseDetector.Config}'s
 * shape/drift/slope/penetration/entrance thresholds but sweeps them over a much larger window
 * ceiling, scales the width/drift ATR bounds by sqrt(bars/referenceBars) beyond referenceBars so a
 * longer window is judged against a volatility envelope instead of a fixed short-term ATR
 * multiple, and returns every geometrically valid candidate (not just the largest) together with a
 * touch count for each boundary, so a caller can rank candidates by volume concentration and
 * discount an already-tested edge. Precomputed prefix sums and a sparse table make the wider scan
 * tractable; they are cached per input list identity since the same technical-feature list is
 * reused across an entire backtest run.
 */
public final class VariableBaseDetectorV5 {
    public record Base(int startIndex, int endExclusive, int bars,
                       BigDecimal low, BigDecimal high, int highTouches, int lowTouches) {}
    private static final int LONG_WINDOW_STRIDE = 4;

    private List<FeatureSnapshot> cachedList;
    private double[] bodyLow, bodyHigh, wickLow, wickHigh, closeArr, closePrefixSum;
    private double[][] sparseMax, sparseMin;
    private int[] log2Table;

    /** All geometrically valid candidate windows for this breakout, largest first. */
    public List<Base> detectCandidates(List<FeatureSnapshot> h, int breakout, FeatureKey atr,
                                        VariableBaseDetector.Config c, int referenceBars) {
        List<Base> found = new ArrayList<>();
        if (breakout <= c.maximumBars() + 3) return found;
        ensureCache(h);
        double a = h.get(breakout).require(atr).doubleValue();
        double minWidth = c.minimumWidthAtr().doubleValue(), maxWidth = c.maximumWidthAtr().doubleValue();
        double maxDrift = c.maximumCenterDriftAtr().doubleValue();
        double maxSlope = c.maximumCloseSlopeAtrPerBar().doubleValue();
        double penetrationAtr = c.boundaryPenetrationAtr().doubleValue();
        double maxPenetrationFraction = c.maximumBoundaryPenetrationFraction().doubleValue();
        double entranceAtr = c.entranceDistanceAtr().doubleValue();
        int bars = c.maximumBars();
        while (bars >= c.minimumBars()) {
            int start = breakout - bars;
            double scale = bars <= referenceBars ? 1.0 : Math.sqrt((double) bars / referenceBars);
            double high = rangeMax(sparseMax, bodyHigh, start, breakout);
            double low = rangeMin(sparseMin, bodyLow, start, breakout);
            double widthAtr = (high - low) / (a * scale);
            if (widthAtr >= minWidth && widthAtr <= maxWidth) {
                int half = bars / 2;
                double firstAvg = rangeAvgClose(start, start + half);
                double secondAvg = rangeAvgClose(start + half, breakout);
                double drift = Math.abs(secondAvg - firstAvg) / (a * scale);
                if (drift <= maxDrift) {
                    double slope = Math.abs(closeArr[breakout - 1] - closeArr[start]) / (a * (bars - 1));
                    if (slope <= maxSlope) {
                        double tolerance = a * penetrationAtr;
                        int penetrations = countPenetrations(start, breakout, low, high, tolerance);
                        if ((double) penetrations / bars <= maxPenetrationFraction) {
                            double entranceMove = Math.abs(closeArr[start] - closeArr[start - 3]);
                            if (entranceMove >= a * entranceAtr) {
                                BigDecimal[] exact = exactBounds(h, start, breakout);
                                int highTouches = countTouches(wickHigh, start, breakout, high, tolerance, true);
                                int lowTouches = countTouches(wickLow, start, breakout, low, tolerance, false);
                                found.add(new Base(start, breakout, bars, exact[0], exact[1],
                                        highTouches, lowTouches));
                            }
                        }
                    }
                }
            }
            bars -= bars > referenceBars ? LONG_WINDOW_STRIDE : 1;
        }
        return found;
    }

    private void ensureCache(List<FeatureSnapshot> h) {
        if (h == cachedList) return;
        int n = h.size();
        bodyLow = new double[n]; bodyHigh = new double[n];
        wickLow = new double[n]; wickHigh = new double[n];
        closeArr = new double[n]; closePrefixSum = new double[n + 1];
        for (int i = 0; i < n; i++) {
            FeatureSnapshot s = h.get(i);
            double open = s.require(FeatureKey.open()).doubleValue();
            double close = s.require(FeatureKey.close()).doubleValue();
            bodyLow[i] = Math.min(open, close);
            bodyHigh[i] = Math.max(open, close);
            wickLow[i] = s.require(FeatureKey.low()).doubleValue();
            wickHigh[i] = s.require(FeatureKey.high()).doubleValue();
            closeArr[i] = close;
            closePrefixSum[i + 1] = closePrefixSum[i] + close;
        }
        log2Table = new int[n + 1];
        for (int i = 2; i <= n; i++) log2Table[i] = log2Table[i / 2] + 1;
        int levels = n == 0 ? 1 : log2Table[n] + 1;
        sparseMax = new double[levels][n];
        sparseMin = new double[levels][n];
        if (n > 0) {
            System.arraycopy(bodyHigh, 0, sparseMax[0], 0, n);
            System.arraycopy(bodyLow, 0, sparseMin[0], 0, n);
        }
        for (int k = 1; k < levels; k++) {
            int half = 1 << (k - 1);
            for (int i = 0; i + (1 << k) <= n; i++) {
                sparseMax[k][i] = Math.max(sparseMax[k - 1][i], sparseMax[k - 1][i + half]);
                sparseMin[k][i] = Math.min(sparseMin[k - 1][i], sparseMin[k - 1][i + half]);
            }
        }
        cachedList = h;
    }

    private double rangeMax(double[][] sparse, double[] flat, int fromInclusive, int toExclusive) {
        int len = toExclusive - fromInclusive;
        if (len == 1) return flat[fromInclusive];
        int k = log2Table[len];
        return Math.max(sparse[k][fromInclusive], sparse[k][toExclusive - (1 << k)]);
    }

    private double rangeMin(double[][] sparse, double[] flat, int fromInclusive, int toExclusive) {
        int len = toExclusive - fromInclusive;
        if (len == 1) return flat[fromInclusive];
        int k = log2Table[len];
        return Math.min(sparse[k][fromInclusive], sparse[k][toExclusive - (1 << k)]);
    }

    private double rangeAvgClose(int fromInclusive, int toExclusive) {
        return (closePrefixSum[toExclusive] - closePrefixSum[fromInclusive]) / (toExclusive - fromInclusive);
    }

    private int countPenetrations(int start, int endExclusive, double low, double high, double tolerance) {
        int count = 0;
        for (int i = start; i < endExclusive; i++) {
            if (wickLow[i] < low - tolerance || wickHigh[i] > high + tolerance) count++;
        }
        return count;
    }

    /** Counts distinct approaches to a boundary (a contiguous run within tolerance counts once). */
    private static int countTouches(double[] wick, int start, int endExclusive, double boundary,
                                     double tolerance, boolean highBoundary) {
        int count = 0;
        boolean inTouch = false;
        for (int i = start; i < endExclusive; i++) {
            boolean near = highBoundary ? wick[i] >= boundary - tolerance : wick[i] <= boundary + tolerance;
            if (near) {
                if (!inTouch) count++;
                inTouch = true;
            } else {
                inTouch = false;
            }
        }
        return count;
    }

    private static BigDecimal[] exactBounds(List<FeatureSnapshot> h, int start, int endExclusive) {
        BigDecimal low = null, high = null;
        for (int i = start; i < endExclusive; i++) {
            BigDecimal o = h.get(i).require(FeatureKey.open());
            BigDecimal close = h.get(i).require(FeatureKey.close());
            low = low == null ? o.min(close) : low.min(o.min(close));
            high = high == null ? o.max(close) : high.max(o.max(close));
        }
        return new BigDecimal[]{low, high};
    }
}
