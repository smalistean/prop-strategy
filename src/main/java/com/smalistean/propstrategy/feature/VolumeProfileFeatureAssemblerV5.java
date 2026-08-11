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
    /** Below this, a fixed profile is not meaningful. Declared in APOLLO_V7_PREREGISTRATION.md. */
    private static final int MINIMUM_PROFILE_CANDLES = 3;

    /**
     * The course's fixed-profile selection rule, which no Apollo version had implemented.
     *
     * <p><i>"Determine the greatest body high and body low of the visually selected horizontal candle
     * cluster, then include only candles fully inside those body bounds. The entrance and exit
     * candles are excluded"</i> (pp. 25, 33).
     *
     * <p>V5 instead stretched the profile over every bucket from base start through the current bar,
     * which included the entrance candle, every candle whose wick left the body bounds, and the
     * breakout candle itself - the exit candle the source names explicitly. Those candles are
     * systematically the volatile ones at the base edges and the breakout impulse, and they carry
     * disproportionate volume, so including them biases the POC toward the edges and toward the
     * breakout rather than toward where price actually spent time.
     *
     * <p>{@code base.low()}/{@code base.high()} are already body bounds, computed by
     * {@code VariableBaseDetectorV5.exactBounds()} from open/close.
     */
    private static java.util.NavigableMap<Instant, List<VolumeProfileBin>> bodyBoundedWindow(
            List<FeatureSnapshot> technical, VariableBaseDetectorV5.Base base,
            java.util.NavigableMap<Instant, List<VolumeProfileBin>> byTime) {
        java.util.TreeMap<Instant, List<VolumeProfileBin>> selected = new java.util.TreeMap<>();
        // Starts one past the entrance candle; ends before the breakout, which is the exit candle.
        for (int i = base.startIndex() + 1; i < base.endExclusive(); i++) {
            FeatureSnapshot candle = technical.get(i);
            if (candle.require(FeatureKey.high()).compareTo(base.high()) > 0) continue;
            if (candle.require(FeatureKey.low()).compareTo(base.low()) < 0) continue;
            List<VolumeProfileBin> atCandle = byTime.get(candle.candleOpenTime());
            if (atCandle != null) selected.put(candle.candleOpenTime(), atCandle);
        }
        return selected;
    }

    /**
     * Acceptance beyond a broken base must be made of impulse candles, not wicks.
     *
     * <p>Source: <i>"a breakout/retest entry requires real acceptance beyond the base: several
     * full-bodied candles, not one or two wick-like candles"</i> (p. 53), and <i>"several
     * full-bodied candles there, or a pair of impulse candles"</i> (p. 3). Until now the two
     * acceptance candles were tested only for where they closed, so a pair of long-wicked dojis
     * qualified exactly as a pair of impulse candles - the case p. 53 explicitly rejects.
     *
     * <p>A candle counts when its body is at least {@code minimumBodyFraction} of its high-low range
     * <em>and</em> closes in the direction of the break, which is what "impulse" implies. The book
     * does not specify the number or body size (see {@code APOLLO_COURSE_SOURCE_NOTES.md}); both are
     * declared in {@code APOLLO_V7_ACCEPTANCE_PREREGISTRATION.md} rather than searched for.
     *
     * <p>A zero {@code minimumBodyFraction} disables the test and reproduces the frozen V5 baseline.
     */
    private static boolean acceptanceIsFullBodied(FeatureSnapshot breakout, FeatureSnapshot acceptance,
                                                  int side, BigDecimal minimumBodyFraction,
                                                  int minimumCandles) {
        if (minimumBodyFraction.signum() <= 0) return true;
        int impulses = 0;
        for (FeatureSnapshot candle : List.of(breakout, acceptance)) {
            BigDecimal open = candle.require(FeatureKey.open());
            BigDecimal close = candle.require(FeatureKey.close());
            BigDecimal range = candle.require(FeatureKey.high()).subtract(candle.require(FeatureKey.low()));
            if (range.signum() <= 0) continue;
            if (close.subtract(open).signum() != side) continue;
            BigDecimal bodyFraction = close.subtract(open).abs()
                    .divide(range, new java.math.MathContext(20, java.math.RoundingMode.HALF_UP));
            if (bodyFraction.compareTo(minimumBodyFraction) >= 0) impulses++;
        }
        return impulses >= minimumCandles;
    }

    public List<FeatureSnapshot> mergePersistentBases(List<FeatureSnapshot> technical,
                                                       List<VolumeProfileBin> bins,
                                                       FeatureKey atr, FeatureKey volume,
                                                       VariableBaseDetector.Config detectorConfig,
                                                       BigDecimal neighborMinimumPocFraction,
                                                       BigDecimal breakoutAtr,
                                                       int confirmationWindowBars,
                                                       int referenceBars,
                                                       int maximumBoundaryTouches,
                                                       BigDecimal pocBinAtrFraction,
                                                       BigDecimal internalWaveMinimumShare,
                                                       boolean consumedBasesRemainTargets,
                                                       BigDecimal acceptanceMinimumBodyFraction,
                                                       int acceptanceMinimumBodyCandles,
                                                       boolean profileBodyBoundedSelection) {
        if (bins.isEmpty()) return List.of();
        java.util.NavigableMap<Instant, List<VolumeProfileBin>> byTime = new java.util.TreeMap<>();
        bins.forEach(bin -> byTime.computeIfAbsent(bin.bucketTime(), ignored -> new ArrayList<>()).add(bin));
        record MappedBase(int id, int breakout, int side, VariableBaseDetectorV5.Base base,
                          BigDecimal zoneLow, BigDecimal zoneHigh, BigDecimal zoneShare,
                          BigDecimal pocShare, BigDecimal totalQuote, BigDecimal volumeRatio,
                          BigDecimal breakoutVolume, BigDecimal zoneDelta) { }
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
                    if (!acceptanceIsFullBodied(breakoutSnapshot, snapshot, side,
                            acceptanceMinimumBodyFraction, acceptanceMinimumBodyCandles)) continue;
                    int touches = side > 0 ? base.highTouches() : base.lowTouches();
                    if (touches > maximumBoundaryTouches) continue;
                    Instant from = technical.get(base.startIndex()).candleOpenTime();
                    BigDecimal rawStep = bins.getFirst().priceStep();
                    BigDecimal analysisStep = aggregationStep(rawStep, breakoutSnapshot.require(atr), pocBinAtrFraction);
                    java.util.NavigableMap<Instant, List<VolumeProfileBin>> window =
                            profileBodyBoundedSelection
                                    ? bodyBoundedWindow(technical, base, byTime)
                                    : byTime.subMap(from, true, snapshot.candleOpenTime(), false);
                    if (profileBodyBoundedSelection && window.size() < MINIMUM_PROFILE_CANDLES) continue;
                    BigDecimal[] p = profile(window, analysisStep, rawStep, neighborMinimumPocFraction);
                    if (p == null) continue;
                    boolean longScale = base.bars() > referenceBars;
                    BigDecimal currentBest = longScale ? bestLongPocShare : bestShortPocShare;
                    if (currentBest != null && p[3].compareTo(currentBest) <= 0) continue;
                    Instant priorFrom = from.minus(Duration.ofMinutes((long) base.bars() * bins.getFirst().bucketMinutes()));
                    BigDecimal prior = quoteTotal(byTime.subMap(priorFrom, true, from, false));
                    // The volume ratio asks whether the base was built on more volume than the
                    // period before it - a property of the base, not of profile geometry. It must
                    // therefore always use the full base window. Feeding it the body-bounded total
                    // would divide a reduced numerator by an unreduced denominator and deflate every
                    // ratio, silently failing the minimumBaseVolumeRatio gate for reasons that have
                    // nothing to do with the rule under test.
                    BigDecimal totalForRatio = profileBodyBoundedSelection
                            ? quoteTotal(byTime.subMap(from, true, snapshot.candleOpenTime(), false))
                            : p[4];
                    MappedBase candidate = new MappedBase(nextId, breakout, side, base, p[0], p[1], p[2], p[3], p[4],
                            prior.signum() == 0 ? BigDecimal.ZERO : totalForRatio.divide(prior,
                                    new java.math.MathContext(20, java.math.RoundingMode.HALF_UP)),
                            breakoutSnapshot.require(volume), p[5]);
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
                values.put(FeatureKey.selectedBaseDelta(), base.zoneDelta());
                values.put(FeatureKey.selectedBaseBreakoutSide(), BigDecimal.valueOf(base.side()));
                values.put(FeatureKey.selectedBaseBreakoutVolumeRatio(), base.breakoutVolume());
                values.put(FeatureKey.selectedBaseFirstRevisit(), activeBase.revisit() == index
                        ? BigDecimal.ONE : BigDecimal.ZERO);
                BigDecimal target = null;
                for (MappedBase other : mapped) {
                    if (other.id() == base.id()) continue;
                    // A consumed base can no longer generate an ENTRY (the course's "first revisit
                    // consumes the setup"), but the source treats the price itself as a durable map
                    // reference: APOLLO_LABELLED_EXAMPLES.md records 2,440.87 marked on both
                    // 2026-03-04 and 2026-05-12, ten weeks apart and long after first contact.
                    // When enabled, consumption therefore stops entries but not target eligibility.
                    if (!consumedBasesRemainTargets && consumed.contains(other.id())) continue;
                    BigDecimal candidate = other.side() > 0 ? other.zoneLow() : other.zoneHigh();
                    boolean ahead = base.side() > 0 ? candidate.compareTo(snapshot.require(FeatureKey.close())) > 0
                            : candidate.compareTo(snapshot.require(FeatureKey.close())) < 0;
                    if (ahead && (target == null || (base.side() > 0 ? candidate.compareTo(target) < 0 : candidate.compareTo(target) > 0))) target = candidate;
                }
                // Test A (roadmap step 2): the course states an internal volume wave can be a valid,
                // earlier target than the principal level (Книга 2.0 p.32). When enabled, prefer the
                // strongest volume concentration lying strictly between price and the mapped zone.
                if (target != null && internalWaveMinimumShare.signum() > 0) {
                    BigDecimal analysisStep = aggregationStep(bins.getFirst().priceStep(),
                            snapshot.require(atr), pocBinAtrFraction);
                    Instant waveFrom = technical.get(Math.max(0, index - base.base().bars())).candleOpenTime();
                    BigDecimal wave = strongestWaveBetween(
                            byTime.subMap(waveFrom, true, snapshot.candleOpenTime(), false),
                            snapshot.require(FeatureKey.close()), target, analysisStep,
                            internalWaveMinimumShare);
                    if (wave != null) target = wave;
                }
                if (target != null) values.put(FeatureKey.selectedBaseTarget(), target);
            }
            result.add(new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(),
                    snapshot.earliestExecutionTime(), Map.copyOf(values)));
        }
        return List.copyOf(result);
    }

    /**
     * The raw stored bin width is deliberately finer than any single analysis needs; this rounds it
     * up to a whole multiple sized off the current ATR (never finer than the raw data, never a
     * fraction of a raw bin), so a wide multi-day base and a tight intraday one each get a POC/zone
     * resolution matched to their own volatility scale instead of one fixed dollar width picked once
     * at import time - which can't track a symbol's own price appreciating 5-10x over its history,
     * let alone differ sensibly between a $60,000 and a $0.10 coin.
     */
    static BigDecimal aggregationStep(BigDecimal rawStep, BigDecimal atrValue, BigDecimal atrFraction) {
        BigDecimal target = atrValue.multiply(atrFraction);
        BigDecimal multiple = target.divide(rawStep, 0, java.math.RoundingMode.HALF_UP);
        if (multiple.compareTo(BigDecimal.ONE) < 0) multiple = BigDecimal.ONE;
        return rawStep.multiply(multiple);
    }

    private static BigDecimal floorToStep(BigDecimal price, BigDecimal step) {
        return price.divideToIntegralValue(step).multiply(step);
    }

    private static final java.math.MathContext MC = new java.math.MathContext(20, java.math.RoundingMode.HALF_UP);

    private static BigDecimal[] profile(Map<Instant, List<VolumeProfileBin>> selected,
                                        BigDecimal step, BigDecimal rawStep, BigDecimal neighborFraction) {
        java.util.NavigableMap<BigDecimal, BigDecimal> raw = new java.util.TreeMap<>();
        BigDecimal deltaSum = BigDecimal.ZERO;
        for (List<VolumeProfileBin> bucket : selected.values()) {
            for (VolumeProfileBin bin : bucket) {
                raw.merge(bin.priceFrom(), bin.quoteNotional(), BigDecimal::add);
                deltaSum = deltaSum.add(bin.deltaQuote());
            }
        }
        if (raw.isEmpty()) return null;
        java.util.NavigableMap<BigDecimal, BigDecimal> totals = new java.util.TreeMap<>();
        raw.forEach((price, quote) -> totals.merge(floorToStep(price, step), quote, BigDecimal::add));
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
        // A coarser analysis bin mechanically concentrates more volume into fewer, fatter bins even
        // with zero real structure (merging N raw bins into one multiplies its expected share by
        // roughly N under a uniform baseline). Dividing by that same multiple keeps minimumPocShare/
        // minimumZoneShare measuring genuine concentration rather than getting easier to clear simply
        // because ATR (and therefore the analysis step) was elevated - found 2026-08-10 investigating
        // why the ATR-scaled step regressed BTCUSDT Family B: it let more, lower-quality candidates
        // through specifically during high-volatility stretches, exactly when selectivity matters most.
        BigDecimal multiple = step.divide(rawStep, MC);
        BigDecimal zoneShare = zone.divide(total, MC).divide(multiple, MC);
        BigDecimal pocShare = totals.get(poc).divide(total, MC).divide(multiple, MC);
        // Slot 5: net aggressor delta over the base, normalised by its own traded volume, so it is
        // comparable across symbols and periods. Positive = buyers aggressed into the zone;
        // negative = sellers aggressed. The classic absorption reading is that a zone which HELD
        // while one side aggressed was absorbed by passive flow on the other side.
        BigDecimal normalizedDelta = total.signum() == 0 ? BigDecimal.ZERO : deltaSum.divide(total, MC);
        return new BigDecimal[]{low, high, zoneShare, pocShare, total, normalizedDelta};
    }

    /**
     * Strongest volume concentration strictly between {@code from} and {@code to}, or null if none
     * holds at least {@code minimumShare} of the volume traded in that band. Direction-agnostic:
     * the caller supplies current price and the mapped target in either order.
     */
    private static BigDecimal strongestWaveBetween(Map<Instant, List<VolumeProfileBin>> window,
                                                    BigDecimal from, BigDecimal to, BigDecimal step,
                                                    BigDecimal minimumShare) {
        BigDecimal low = from.min(to), high = from.max(to);
        java.util.NavigableMap<BigDecimal, BigDecimal> band = new java.util.TreeMap<>();
        for (List<VolumeProfileBin> bucket : window.values()) {
            for (VolumeProfileBin bin : bucket) {
                BigDecimal price = bin.priceFrom();
                if (price.compareTo(low) <= 0 || price.compareTo(high) >= 0) continue;
                band.merge(floorToStep(price, step), bin.quoteNotional(), BigDecimal::add);
            }
        }
        if (band.isEmpty()) return null;
        Map.Entry<BigDecimal, BigDecimal> peak = band.entrySet().stream()
                .max(Map.Entry.comparingByValue()).orElseThrow();
        BigDecimal total = band.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() == 0) return null;
        return peak.getValue().divide(total, MC).compareTo(minimumShare) >= 0 ? peak.getKey() : null;
    }

    private static BigDecimal quoteTotal(Map<Instant, List<VolumeProfileBin>> selected) {
        return selected.values().stream().flatMap(List::stream).map(VolumeProfileBin::quoteNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
