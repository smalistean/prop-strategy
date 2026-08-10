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
                    Instant priorFrom = from.minus(Duration.ofMinutes((long) selected.bars()
                            * bins.getFirst().bucketMinutes()));
                    BigDecimal priorTotal = quoteTotal(byTime.subMap(priorFrom, true, from, false));
                    values.put(FeatureKey.selectedBaseBars(), BigDecimal.valueOf(selected.bars()));
                    values.put(FeatureKey.selectedBaseLow(), selected.low());
                    values.put(FeatureKey.selectedBaseHigh(), selected.high());
                    values.put(FeatureKey.selectedBaseZoneLow(), profile[0]);
                    values.put(FeatureKey.selectedBaseZoneHigh(), profile[1]);
                    values.put(FeatureKey.selectedBaseZoneShare(), profile[2]);
                    values.put(FeatureKey.selectedBasePocShare(), profile[3]);
                    values.put(FeatureKey.selectedBaseTotalQuote(), profile[4]);
                    values.put(FeatureKey.selectedBaseVolumeRatio(), priorTotal.signum() == 0
                            ? BigDecimal.ZERO : profile[4].divide(priorTotal,
                            new java.math.MathContext(20, java.math.RoundingMode.HALF_UP)));
                }
            }
            result.add(new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(),
                    snapshot.earliestExecutionTime(), Map.copyOf(values)));
        }
        return List.copyOf(result);
    }

    /**
     * Builds a causal map of bases which survive the breakout and are consumed on
     * their first later POC-zone visit.  Only the first visit is exposed to the
     * strategy, followed by a short confirmation window; no future candle is
     * consulted when creating or consuming a map item.
     */
    public List<FeatureSnapshot> mergePersistentBases(List<FeatureSnapshot> technical,
                                                       List<VolumeProfileBin> bins,
                                                       FeatureKey atr, FeatureKey volume,
                                                       VariableBaseDetector.Config detectorConfig,
                                                       BigDecimal neighborMinimumPocFraction,
                                                       BigDecimal breakoutAtr,
                                                       int confirmationWindowBars) {
        if (bins.isEmpty()) return List.of();
        java.util.NavigableMap<Instant, List<VolumeProfileBin>> byTime = new java.util.TreeMap<>();
        bins.forEach(bin -> byTime.computeIfAbsent(bin.bucketTime(), ignored -> new ArrayList<>()).add(bin));
        record MappedBase(int id, int breakout, int side, VariableBaseDetector.Base base,
                          BigDecimal zoneLow, BigDecimal zoneHigh, BigDecimal zoneShare,
                          BigDecimal pocShare, BigDecimal totalQuote, BigDecimal volumeRatio,
                          BigDecimal breakoutVolume) { }
        record Active(MappedBase base, int revisit) { }
        VariableBaseDetector detector = new VariableBaseDetector();
        List<MappedBase> mapped = new ArrayList<>();
        java.util.Set<Integer> consumed = new java.util.HashSet<>();
        List<Active> active = new ArrayList<>();
        List<FeatureSnapshot> result = new ArrayList<>();
        int nextId = 1;
        for (int index = 0; index < technical.size(); index++) {
            FeatureSnapshot snapshot = technical.get(index);
            // A map item is known only after the breakout close and one acceptance close.
            if (index >= 2) {
                int breakout = index - 1;
                var detected = detector.detect(technical, breakout, atr, detectorConfig);
                if (detected.isPresent()) {
                    VariableBaseDetector.Base base = detected.orElseThrow();
                    FeatureSnapshot breakoutSnapshot = technical.get(breakout);
                    BigDecimal threshold = breakoutSnapshot.require(atr).multiply(breakoutAtr);
                    int side = breakoutSnapshot.require(FeatureKey.close()).compareTo(base.high().add(threshold)) > 0
                            && snapshot.require(FeatureKey.close()).compareTo(base.high()) > 0 ? 1
                            : breakoutSnapshot.require(FeatureKey.close()).compareTo(base.low().subtract(threshold)) < 0
                            && snapshot.require(FeatureKey.close()).compareTo(base.low()) < 0 ? -1 : 0;
                    if (side != 0) {
                        Instant from = technical.get(base.startIndex()).candleOpenTime();
                        BigDecimal[] p = profile(byTime.subMap(from, true, snapshot.candleOpenTime(), false),
                                bins.getFirst().priceStep(), neighborMinimumPocFraction);
                        if (p != null) {
                            Instant priorFrom = from.minus(Duration.ofMinutes((long) base.bars() * bins.getFirst().bucketMinutes()));
                            BigDecimal prior = quoteTotal(byTime.subMap(priorFrom, true, from, false));
                            mapped.add(new MappedBase(nextId++, breakout, side, base, p[0], p[1], p[2], p[3], p[4],
                                    prior.signum() == 0 ? BigDecimal.ZERO : p[4].divide(prior,
                                            new java.math.MathContext(20, java.math.RoundingMode.HALF_UP)),
                                    breakoutSnapshot.require(volume)));
                        }
                    }
                }
            }
            // Find and consume first visits.  A revisit cannot occur on the breakout/acceptance bars.
            for (MappedBase base : mapped) {
                if (consumed.contains(base.id()) || index <= base.breakout() + 1) continue;
                boolean touched = base.side() > 0
                        ? snapshot.require(FeatureKey.low()).compareTo(base.zoneHigh()) <= 0
                        : snapshot.require(FeatureKey.high()).compareTo(base.zoneLow()) >= 0;
                if (touched) { consumed.add(base.id()); active.add(new Active(base, index)); }
            }
            int currentIndex = index;
            active.removeIf(base -> currentIndex - base.revisit() >= confirmationWindowBars);
            Map<FeatureKey, BigDecimal> values = new HashMap<>(snapshot.values());
            if (!active.isEmpty()) {
                // Prefer the newest revisited base.  It is the one the trader can act on now.
                Active activeBase = active.getLast();
                MappedBase base = activeBase.base();
                values.put(FeatureKey.selectedBaseId(), BigDecimal.valueOf(base.id()));
                values.put(FeatureKey.selectedBaseBars(), BigDecimal.valueOf(base.base().bars()));
                values.put(FeatureKey.selectedBaseLow(), base.base().low());
                values.put(FeatureKey.selectedBaseHigh(), base.base().high());
                values.put(FeatureKey.selectedBaseZoneLow(), base.zoneLow());
                values.put(FeatureKey.selectedBaseZoneHigh(), base.zoneHigh());
                values.put(FeatureKey.selectedBaseZoneShare(), base.zoneShare());
                values.put(FeatureKey.selectedBasePocShare(), base.pocShare());
                values.put(FeatureKey.selectedBaseTotalQuote(), base.totalQuote());
                values.put(FeatureKey.selectedBaseVolumeRatio(), base.volumeRatio());
                values.put(FeatureKey.selectedBaseBreakoutSide(), BigDecimal.valueOf(base.side()));
                values.put(FeatureKey.selectedBaseBreakoutVolumeRatio(), base.breakoutVolume());
                values.put(FeatureKey.selectedBaseFirstRevisit(), activeBase.revisit() == index
                        ? BigDecimal.ONE : BigDecimal.ZERO);
                BigDecimal target = null;
                for (MappedBase other : mapped) {
                    if (other.id() == base.id() || consumed.contains(other.id())) continue;
                    BigDecimal candidate = other.side() > 0 ? other.zoneLow() : other.zoneHigh();
                    boolean ahead = base.side() > 0 ? candidate.compareTo(snapshot.require(FeatureKey.close())) > 0
                            : candidate.compareTo(snapshot.require(FeatureKey.close())) < 0;
                    if (ahead && (target == null || (base.side() > 0 ? candidate.compareTo(target) < 0 : candidate.compareTo(target) > 0))) target = candidate;
                }
                if (target != null) values.put(FeatureKey.selectedBaseTarget(), target);
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

    private static BigDecimal quoteTotal(Map<Instant, List<VolumeProfileBin>> selected) {
        return selected.values().stream().flatMap(List::stream).map(VolumeProfileBin::quoteNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
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
