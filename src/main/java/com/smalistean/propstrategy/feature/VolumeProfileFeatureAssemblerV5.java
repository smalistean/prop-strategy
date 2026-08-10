package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.VolumeProfileBin;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apollo V5 persistent-base map. Distinct from {@link VolumeProfileFeatureAssembler#mergePersistentBases}
 * (V4 and earlier): uses {@link VariableBaseDetectorV5} to search a multi-day window instead of only
 * the 12-48 candle window ending immediately before the breakout, and among every geometrically valid
 * candidate at a breakout prefers the one with the strongest volume concentration (highest POC share)
 * rather than the largest flat shape. A wider, multi-day candidate is preferred over the short window
 * whenever one qualifies - that short window is only a candidate source, not the entire map. POC share
 * only ranks candidates within the same scale: a longer window spreads volume over more price levels
 * and would never win a raw POC-share comparison against a short one, which would silently make the
 * wider search a no-op. A boundary already tested repeatedly before the breakout is discounted rather
 * than trusted.
 */
public final class VolumeProfileFeatureAssemblerV5 {
    public List<FeatureSnapshot> mergePersistentBases(List<FeatureSnapshot> technical,
                                                       List<VolumeProfileBin> bins,
                                                       FeatureKey atr, FeatureKey volume,
                                                       VariableBaseDetector.Config detectorConfig,
                                                       BigDecimal neighborMinimumPocFraction,
                                                       BigDecimal breakoutAtr,
                                                       int confirmationWindowBars,
                                                       int referenceBars,
                                                       int maximumBoundaryTouches) {
        if (bins.isEmpty()) return List.of();
        java.util.NavigableMap<Instant, List<VolumeProfileBin>> byTime = new java.util.TreeMap<>();
        bins.forEach(bin -> byTime.computeIfAbsent(bin.bucketTime(), ignored -> new ArrayList<>()).add(bin));
        record MappedBase(int id, int breakout, int side, VariableBaseDetectorV5.Base base,
                          BigDecimal zoneLow, BigDecimal zoneHigh, BigDecimal zoneShare,
                          BigDecimal pocShare, BigDecimal totalQuote, BigDecimal volumeRatio,
                          BigDecimal breakoutVolume) { }
        record Active(MappedBase base, int revisit) { }
        VariableBaseDetectorV5 detector = new VariableBaseDetectorV5();
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
                FeatureSnapshot breakoutSnapshot = technical.get(breakout);
                MappedBase bestLong = null, bestShort = null;
                BigDecimal bestLongPocShare = null, bestShortPocShare = null;
                for (VariableBaseDetectorV5.Base base : detector.detectCandidates(technical, breakout, atr,
                        detectorConfig, referenceBars)) {
                    BigDecimal threshold = breakoutSnapshot.require(atr).multiply(breakoutAtr);
                    int side = breakoutSnapshot.require(FeatureKey.close()).compareTo(base.high().add(threshold)) > 0
                            && snapshot.require(FeatureKey.close()).compareTo(base.high()) > 0 ? 1
                            : breakoutSnapshot.require(FeatureKey.close()).compareTo(base.low().subtract(threshold)) < 0
                            && snapshot.require(FeatureKey.close()).compareTo(base.low()) < 0 ? -1 : 0;
                    if (side == 0) continue;
                    int touches = side > 0 ? base.highTouches() : base.lowTouches();
                    if (touches > maximumBoundaryTouches) continue;
                    Instant from = technical.get(base.startIndex()).candleOpenTime();
                    BigDecimal[] p = profile(byTime.subMap(from, true, snapshot.candleOpenTime(), false),
                            bins.getFirst().priceStep(), neighborMinimumPocFraction);
                    if (p == null) continue;
                    boolean longScale = base.bars() > referenceBars;
                    BigDecimal currentBest = longScale ? bestLongPocShare : bestShortPocShare;
                    if (currentBest != null && p[3].compareTo(currentBest) <= 0) continue;
                    Instant priorFrom = from.minus(Duration.ofMinutes((long) base.bars() * bins.getFirst().bucketMinutes()));
                    BigDecimal prior = quoteTotal(byTime.subMap(priorFrom, true, from, false));
                    MappedBase candidate = new MappedBase(nextId, breakout, side, base, p[0], p[1], p[2], p[3], p[4],
                            prior.signum() == 0 ? BigDecimal.ZERO : p[4].divide(prior,
                                    new java.math.MathContext(20, java.math.RoundingMode.HALF_UP)),
                            breakoutSnapshot.require(volume));
                    if (longScale) { bestLong = candidate; bestLongPocShare = p[3]; }
                    else { bestShort = candidate; bestShortPocShare = p[3]; }
                }
                MappedBase best = bestLong != null ? bestLong : bestShort;
                if (best != null) { mapped.add(best); nextId++; }
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
}
