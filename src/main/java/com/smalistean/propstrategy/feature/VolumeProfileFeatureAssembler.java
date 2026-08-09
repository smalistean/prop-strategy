package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.VolumeProfileBin;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class VolumeProfileFeatureAssembler {
    public List<FeatureSnapshot> mergeCompletedHourlyTrend(List<FeatureSnapshot> technical,
                                                           List<com.smalistean.propstrategy.database.Kline> hourly,
                                                           int emaPeriod) {
        FeatureKey ema = FeatureKey.ema(emaPeriod);
        List<FeatureSnapshot> hourFeatures = new ParameterizedFeatureGenerator().generate(hourly,
                java.util.Set.of(FeatureKey.close(), ema));
        List<FeatureSnapshot> result = new ArrayList<>();
        int hourIndex = -1;
        for (FeatureSnapshot snapshot : technical) {
            while (hourIndex + 1 < hourFeatures.size()
                    && !hourFeatures.get(hourIndex + 1).availableAt().isAfter(snapshot.availableAt()))
                hourIndex++;
            if (hourIndex < 0) continue;
            FeatureSnapshot hour = hourFeatures.get(hourIndex);
            Map<FeatureKey, BigDecimal> values = new HashMap<>(snapshot.values());
            values.put(FeatureKey.completedHourClose(), hour.require(FeatureKey.close()));
            values.put(FeatureKey.completedHourEma(emaPeriod), hour.require(ema));
            result.add(new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(),
                    snapshot.earliestExecutionTime(), Map.copyOf(values)));
        }
        return List.copyOf(result);
    }

    public List<FeatureSnapshot> mergeSelectedBases(List<FeatureSnapshot> technical,
                                                    List<VolumeProfileBin> bins,
                                                    FeatureKey atr,
                                                    VariableBaseDetector.Config detectorConfig,
                                                    BigDecimal neighborMinimumPocFraction) {
        if (bins.isEmpty()) return List.of();
        java.util.NavigableMap<Instant, List<VolumeProfileBin>> byTime = new java.util.TreeMap<>();
        bins.forEach(bin -> byTime.computeIfAbsent(bin.bucketTime(), ignored -> new ArrayList<>()).add(bin));
        VariableBaseDetector detector = new VariableBaseDetector();
        List<FeatureSnapshot> result = new ArrayList<>();
        for (int index = 0; index < technical.size(); index++) {
            FeatureSnapshot snapshot = technical.get(index);
            Map<FeatureKey, BigDecimal> values = new HashMap<>(snapshot.values());
            var base = detector.detect(technical, index, atr, detectorConfig);
            if (base.isPresent()) {
                VariableBaseDetector.Base selected = base.orElseThrow();
                Instant from = technical.get(selected.startIndex()).candleOpenTime();
                var profile = profile(byTime.subMap(from, true, snapshot.candleOpenTime(), false),
                        bins.getFirst().priceStep(), neighborMinimumPocFraction);
                if (profile != null) {
                    values.put(FeatureKey.selectedBaseBars(), BigDecimal.valueOf(selected.bars()));
                    values.put(FeatureKey.selectedBaseLow(), selected.low());
                    values.put(FeatureKey.selectedBaseHigh(), selected.high());
                    values.put(FeatureKey.selectedBaseZoneLow(), profile[0]);
                    values.put(FeatureKey.selectedBaseZoneHigh(), profile[1]);
                    values.put(FeatureKey.selectedBaseZoneShare(), profile[2]);
                    values.put(FeatureKey.selectedBasePocShare(), profile[3]);
                    values.put(FeatureKey.selectedBaseTotalQuote(), profile[4]);
                }
            }
            result.add(new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(),
                    snapshot.earliestExecutionTime(), Map.copyOf(values)));
        }
        return List.copyOf(result);
    }

    private static BigDecimal[] profile(Map<Instant, List<VolumeProfileBin>> selected,
                                        BigDecimal step, BigDecimal neighborFraction) {
        java.util.NavigableMap<BigDecimal, BigDecimal> totals = new java.util.TreeMap<>();
        selected.values().forEach(bucket -> bucket.forEach(bin ->
                totals.merge(bin.priceFrom(), bin.quoteNotional(), BigDecimal::add)));
        if (totals.isEmpty()) return null;
        BigDecimal poc = totals.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        BigDecimal threshold = totals.get(poc).multiply(neighborFraction);
        BigDecimal low = poc, high = poc.add(step);
        while (totals.getOrDefault(low.subtract(step), BigDecimal.ZERO).compareTo(threshold) >= 0)
            low = low.subtract(step);
        while (totals.getOrDefault(high, BigDecimal.ZERO).compareTo(threshold) >= 0)
            high = high.add(step);
        BigDecimal total = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal zone = totals.subMap(low, true, high, false).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BigDecimal[]{low, high, zone.divide(total,
                new java.math.MathContext(20, java.math.RoundingMode.HALF_UP)),
                totals.get(poc).divide(total, new java.math.MathContext(20, java.math.RoundingMode.HALF_UP)), total};
    }

    public List<FeatureSnapshot> merge(List<FeatureSnapshot> technical,
                                       List<VolumeProfileBin> bins,
                                       int lookbackBuckets,
                                       BigDecimal neighborMinimumPocFraction) {
        List<RollingVolumeProfileGenerator.Profile> profiles =
                new RollingVolumeProfileGenerator().generate(bins, List.of(lookbackBuckets),
                        neighborMinimumPocFraction).getOrDefault(lookbackBuckets, List.of());
        Map<Instant, RollingVolumeProfileGenerator.Profile> byTime = new HashMap<>();
        profiles.forEach(profile -> byTime.put(profile.asOf(), profile));
        List<FeatureSnapshot> result = new ArrayList<>();
        for (FeatureSnapshot snapshot : technical) {
            RollingVolumeProfileGenerator.Profile profile = byTime.get(snapshot.candleOpenTime());
            if (profile == null) continue;
            Map<FeatureKey, BigDecimal> values = new HashMap<>(snapshot.values());
            values.put(FeatureKey.volumeProfilePoc(lookbackBuckets), profile.pocFrom());
            values.put(FeatureKey.volumeProfileZoneLow(lookbackBuckets), profile.zoneFrom());
            values.put(FeatureKey.volumeProfileZoneHigh(lookbackBuckets), profile.zoneTo());
            values.put(FeatureKey.volumeProfileZoneShare(lookbackBuckets), profile.zoneShare());
            values.put(FeatureKey.volumeProfileZoneDelta(lookbackBuckets), profile.zoneDeltaQuote());
            values.put(FeatureKey.volumeProfilePocStability(lookbackBuckets),
                    BigDecimal.valueOf(profile.pocStabilityBuckets()));
            result.add(new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(),
                    snapshot.earliestExecutionTime(), values));
        }
        return List.copyOf(result);
    }

    public List<FeatureSnapshot> mergeExactBase(List<FeatureSnapshot> technical,
                                                List<VolumeProfileBin> bins,
                                                int baseBars,
                                                BigDecimal neighborMinimumPocFraction) {
        java.util.NavigableMap<Instant, List<VolumeProfileBin>> byTime = new java.util.TreeMap<>();
        for (VolumeProfileBin bin : bins) {
            byTime.computeIfAbsent(bin.bucketTime(), ignored -> new ArrayList<>()).add(bin);
        }
        List<FeatureSnapshot> result = new ArrayList<>();
        Duration window = Duration.ofMinutes((long) baseBars * bins.getFirst().bucketMinutes());
        for (FeatureSnapshot snapshot : technical) {
            var selected = byTime.subMap(snapshot.candleOpenTime().minus(window), true,
                    snapshot.candleOpenTime(), false);
            if (selected.size() < baseBars) continue;
            java.util.NavigableMap<BigDecimal, BigDecimal> totals = new java.util.TreeMap<>();
            for (List<VolumeProfileBin> bucket : selected.values()) {
                for (VolumeProfileBin bin : bucket) {
                    totals.merge(bin.priceFrom(), bin.quoteNotional(), BigDecimal::add);
                }
            }
            if (totals.isEmpty()) continue;
            BigDecimal poc = totals.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            BigDecimal step = bins.getFirst().priceStep();
            BigDecimal threshold = totals.get(poc).multiply(neighborMinimumPocFraction);
            BigDecimal low = poc, high = poc.add(step);
            while (totals.getOrDefault(low.subtract(step), BigDecimal.ZERO)
                    .compareTo(threshold) >= 0) low = low.subtract(step);
            while (totals.getOrDefault(high, BigDecimal.ZERO).compareTo(threshold) >= 0)
                high = high.add(step);
            BigDecimal total = totals.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal zone = totals.subMap(low, true, high, false).values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<FeatureKey, BigDecimal> values = new HashMap<>(snapshot.values());
            values.put(FeatureKey.exactBasePoc(baseBars), poc);
            values.put(FeatureKey.exactBaseZoneLow(baseBars), low);
            values.put(FeatureKey.exactBaseZoneHigh(baseBars), high);
            values.put(FeatureKey.exactBaseZoneShare(baseBars), zone.divide(total,
                    new java.math.MathContext(20, java.math.RoundingMode.HALF_UP)));
            result.add(new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(),
                    snapshot.earliestExecutionTime(), values));
        }
        return List.copyOf(result);
    }
}
