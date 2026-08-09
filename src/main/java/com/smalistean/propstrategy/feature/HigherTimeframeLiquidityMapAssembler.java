package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.Kline;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/** Builds a causal 4h pivot-cluster map from stored 1h candles and attaches it to 15m bars. */
public final class HigherTimeframeLiquidityMapAssembler {
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final FeatureKey ATR = FeatureKey.atr(14);

    public List<FeatureSnapshot> attach(List<FeatureSnapshot> fifteen, List<Kline> hourly,
                                        int lookbackBars, int pivotStrength, int minimumTouches,
                                        BigDecimal toleranceAtr) {
        List<Kline> fourHour = aggregate(hourly);
        List<FeatureSnapshot> features = new ParameterizedFeatureGenerator().generate(fourHour,
                java.util.Set.of(FeatureKey.close(), FeatureKey.high(), FeatureKey.low(), ATR, FeatureKey.ema(50)));
        NavigableMap<Instant, Levels> map = new TreeMap<>();
        for (int i = 0; i < features.size(); i++) {
            if (i < lookbackBars + pivotStrength) continue;
            FeatureSnapshot current = features.get(i);
            BigDecimal atr = current.require(ATR);
            if (atr.signum() <= 0) continue;
            map.put(current.availableAt(), levels(features, i, lookbackBars, pivotStrength,
                    minimumTouches, atr.multiply(toleranceAtr, MC)));
        }
        return fifteen.stream().map(item -> {
            var entry = map.floorEntry(item.availableAt());
            Map<FeatureKey, BigDecimal> values = new HashMap<>(item.values());
            Levels levels = entry == null ? null : entry.getValue();
            values.put(FeatureKey.higherTimeframeSupport(), levels == null || levels.support == null
                    ? BigDecimal.ZERO : levels.support);
            values.put(FeatureKey.higherTimeframeResistance(), levels == null || levels.resistance == null
                    ? BigDecimal.ZERO : levels.resistance);
            FeatureSnapshot trend = entry == null ? null : features.stream()
                    .filter(feature -> feature.availableAt().equals(entry.getKey())).findFirst().orElse(null);
            values.put(FeatureKey.completedFourHourClose(), trend == null ? BigDecimal.ZERO : trend.require(FeatureKey.close()));
            values.put(FeatureKey.completedFourHourEma(50), trend == null ? BigDecimal.ZERO : trend.require(FeatureKey.ema(50)));
            return new FeatureSnapshot(item.candleOpenTime(), item.availableAt(), item.earliestExecutionTime(), values);
        }).toList();
    }

    private Levels levels(List<FeatureSnapshot> h, int index, int lookback, int strength, int minTouches,
                          BigDecimal tolerance) {
        BigDecimal close = h.get(index).require(FeatureKey.close());
        BigDecimal support = null, resistance = null;
        int from = index - lookback;
        for (boolean high : List.of(true, false)) {
            for (int i = from + strength; i <= index - strength; i++) {
                if (!pivot(h, i, strength, high)) continue;
                BigDecimal candidate = h.get(i).require(high ? FeatureKey.high() : FeatureKey.low());
                int touches = 0;
                for (int j = from; j <= index - strength; j++) if (h.get(j)
                        .require(high ? FeatureKey.high() : FeatureKey.low()).subtract(candidate, MC).abs()
                        .compareTo(tolerance) <= 0) touches++;
                if (touches < minTouches) continue;
                if (high && candidate.compareTo(close) > 0 && (resistance == null || candidate.compareTo(resistance) < 0)) resistance = candidate;
                if (!high && candidate.compareTo(close) < 0 && (support == null || candidate.compareTo(support) > 0)) support = candidate;
            }
        }
        return new Levels(support, resistance);
    }

    private boolean pivot(List<FeatureSnapshot> h, int i, int strength, boolean high) {
        BigDecimal value = h.get(i).require(high ? FeatureKey.high() : FeatureKey.low());
        for (int d = 1; d <= strength; d++) {
            BigDecimal left = h.get(i - d).require(high ? FeatureKey.high() : FeatureKey.low());
            BigDecimal right = h.get(i + d).require(high ? FeatureKey.high() : FeatureKey.low());
            if (high ? value.compareTo(left) < 0 || value.compareTo(right) < 0
                    : value.compareTo(left) > 0 || value.compareTo(right) > 0) return false;
        }
        return true;
    }

    private List<Kline> aggregate(List<Kline> hourly) {
        List<Kline> result = new ArrayList<>();
        for (int i = 0; i + 3 < hourly.size(); i += 4) {
            List<Kline> group = hourly.subList(i, i + 4);
            if (Duration.between(group.getFirst().openTime(), group.getLast().openTime()).toHours() != 3) continue;
            Kline first = group.getFirst(); Kline last = group.getLast();
            BigDecimal high = group.stream().map(Kline::high).max(BigDecimal::compareTo).orElseThrow();
            BigDecimal low = group.stream().map(Kline::low).min(BigDecimal::compareTo).orElseThrow();
            BigDecimal volume = group.stream().map(Kline::volume).reduce(BigDecimal.ZERO, (a,b) -> a.add(b, MC));
            result.add(new Kline(first.openTime(), first.open(), high, low, last.close(), volume, last.closeTime(),
                    null, 0, null, null));
        }
        return result;
    }

    private record Levels(BigDecimal support, BigDecimal resistance) { }
}
