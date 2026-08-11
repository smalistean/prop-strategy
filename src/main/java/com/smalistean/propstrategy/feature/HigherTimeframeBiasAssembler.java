package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.Kline;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Attaches a higher-timeframe structural bias to each lower-timeframe snapshot, derived from
 * confirmed 1-hour swing pivots.
 * <p>
 * Source basis (`APOLLO_LABELLED_EXAMPLES.md`, verified 2026-08-11): the `slom_trenda.mp4` clip
 * states two rules outright — breaks <em>must alternate</em> ("ДОЛЖНЫ ЧЕРЕДОВАТЬСЯ"), and one must
 * not trade "your 15-minute against the higher timeframe" ("ВАША ПЯТНАДЦАТИМИНУТКА … ПРОТИВ
 * СТАРШЕГО"). Until now the strategy reasoned only about 15m pivots with a single
 * {@code swingPivotStrength}, with no notion of whether a local entry fights intact higher-timeframe
 * structure.
 * <p>
 * Bias is defined by the last two confirmed swing highs and lows: a higher high <em>and</em> a
 * higher low is an uptrend (+1); a lower high <em>and</em> a lower low is a downtrend (-1); any
 * mixed or incomplete structure is undecided (0) and never blocks a trade. Requiring both to agree
 * is the mechanical reading of "breaks alternate" — a single extreme is not a trend.
 * <p>
 * Causality: a pivot is only emitted once {@code strength} bars have closed on <em>both</em> sides
 * of it, and each 15m snapshot receives the most recent bias whose source 1h bar closed at or
 * before that snapshot's own availability time. No future 1h bar can influence an earlier decision.
 */
public final class HigherTimeframeBiasAssembler {

    public List<FeatureSnapshot> attach(List<FeatureSnapshot> lower, List<Kline> hourly, int strength) {
        java.util.NavigableMap<Instant, BigDecimal> biasAt = new java.util.TreeMap<>();
        List<BigDecimal> highs = new ArrayList<>(), lows = new ArrayList<>();
        for (int i = strength; i < hourly.size() - strength; i++) {
            boolean pivotHigh = true, pivotLow = true;
            BigDecimal h = hourly.get(i).high(), l = hourly.get(i).low();
            for (int j = i - strength; j <= i + strength && (pivotHigh || pivotLow); j++) {
                if (j == i) continue;
                if (hourly.get(j).high().compareTo(h) >= 0) pivotHigh = false;
                if (hourly.get(j).low().compareTo(l) <= 0) pivotLow = false;
            }
            if (!pivotHigh && !pivotLow) continue;
            if (pivotHigh) highs.add(h);
            if (pivotLow) lows.add(l);
            int bias = 0;
            if (highs.size() >= 2 && lows.size() >= 2) {
                boolean higherHigh = highs.get(highs.size() - 1).compareTo(highs.get(highs.size() - 2)) > 0;
                boolean higherLow = lows.get(lows.size() - 1).compareTo(lows.get(lows.size() - 2)) > 0;
                boolean lowerHigh = highs.get(highs.size() - 1).compareTo(highs.get(highs.size() - 2)) < 0;
                boolean lowerLow = lows.get(lows.size() - 1).compareTo(lows.get(lows.size() - 2)) < 0;
                bias = higherHigh && higherLow ? 1 : lowerHigh && lowerLow ? -1 : 0;
            }
            // Known only after the confirming bar on the right of the pivot has closed.
            biasAt.put(hourly.get(i + strength).closeTime(), BigDecimal.valueOf(bias));
        }

        List<FeatureSnapshot> result = new ArrayList<>(lower.size());
        for (FeatureSnapshot snapshot : lower) {
            var entry = biasAt.floorEntry(snapshot.availableAt());
            Map<FeatureKey, BigDecimal> values = new HashMap<>(snapshot.values());
            values.put(FeatureKey.higherTimeframeBias(), entry == null ? BigDecimal.ZERO : entry.getValue());
            result.add(new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(),
                    snapshot.earliestExecutionTime(), Map.copyOf(values)));
        }
        return List.copyOf(result);
    }
}
