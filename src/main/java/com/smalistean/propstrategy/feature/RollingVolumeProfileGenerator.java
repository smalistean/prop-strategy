package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.VolumeProfileBin;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class RollingVolumeProfileGenerator {
    public record Profile(Instant asOf, int lookbackBuckets, BigDecimal pocFrom,
                          BigDecimal pocTo, BigDecimal zoneFrom, BigDecimal zoneTo,
                          BigDecimal totalQuoteNotional, BigDecimal zoneQuoteNotional,
                          BigDecimal zoneShare, BigDecimal zoneDeltaQuote,
                          int pocStabilityBuckets) {}

    private record Amount(BigDecimal quote, BigDecimal delta) {
        private Amount add(Amount other) {
            return new Amount(quote.add(other.quote), delta.add(other.delta));
        }
        private Amount subtract(Amount other) {
            return new Amount(quote.subtract(other.quote), delta.subtract(other.delta));
        }
    }
    private record Bucket(Instant time, Map<BigDecimal, Amount> levels) {}
    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_UP);

    public Map<Integer, List<Profile>> generate(List<VolumeProfileBin> bins,
                                                List<Integer> lookbackBuckets,
                                                BigDecimal neighborMinimumPocFraction) {
        if (bins.isEmpty()) return Map.of();
        if (neighborMinimumPocFraction.signum() < 0
                || neighborMinimumPocFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Neighbor fraction must be between zero and one");
        }
        List<Bucket> buckets = group(bins);
        int bucketMinutes = bins.getFirst().bucketMinutes();
        BigDecimal priceStep = bins.getFirst().priceStep();
        Map<Integer, List<Profile>> result = new LinkedHashMap<>();
        for (int lookback : lookbackBuckets) {
            if (lookback <= 0) throw new IllegalArgumentException("Lookbacks must be positive");
            result.put(lookback, generateWindow(buckets, lookback, bucketMinutes, priceStep,
                    neighborMinimumPocFraction));
        }
        return Map.copyOf(result);
    }

    private List<Profile> generateWindow(List<Bucket> buckets, int lookback, int bucketMinutes,
                                         BigDecimal step, BigDecimal neighborFraction) {
        ArrayDeque<Bucket> window = new ArrayDeque<>();
        TreeMap<BigDecimal, Amount> totals = new TreeMap<>();
        List<Profile> profiles = new ArrayList<>();
        BigDecimal previousPoc = null;
        int stability = 0;
        Duration duration = Duration.ofMinutes((long) lookback * bucketMinutes);
        for (Bucket current : buckets) {
            Instant boundary = current.time().minus(duration);
            while (!window.isEmpty() && window.getFirst().time().isBefore(boundary)) {
                remove(totals, window.removeFirst().levels());
            }
            if (!totals.isEmpty()) {
                BigDecimal poc = totals.entrySet().stream()
                        .max(Map.Entry.<BigDecimal, Amount>comparingByValue(
                                Comparator.comparing(Amount::quote))
                                .thenComparing(Map.Entry::getKey))
                        .orElseThrow().getKey();
                stability = poc.equals(previousPoc) ? stability + 1 : 1;
                previousPoc = poc;
                profiles.add(profile(current.time(), lookback, totals, poc, step,
                        neighborFraction, stability));
            }
            add(totals, current.levels());
            window.addLast(current);
        }
        return List.copyOf(profiles);
    }

    private Profile profile(Instant time, int lookback, TreeMap<BigDecimal, Amount> totals,
                            BigDecimal poc, BigDecimal step, BigDecimal neighborFraction,
                            int stability) {
        BigDecimal threshold = totals.get(poc).quote().multiply(neighborFraction, MC);
        BigDecimal from = poc, to = poc.add(step);
        while (eligible(totals, from.subtract(step), threshold)) from = from.subtract(step);
        while (eligible(totals, to, threshold)) to = to.add(step);
        BigDecimal totalQuote = totals.values().stream().map(Amount::quote)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal zoneQuote = BigDecimal.ZERO, zoneDelta = BigDecimal.ZERO;
        for (var entry : totals.subMap(from, true, to, false).entrySet()) {
            zoneQuote = zoneQuote.add(entry.getValue().quote());
            zoneDelta = zoneDelta.add(entry.getValue().delta());
        }
        BigDecimal share = totalQuote.signum() == 0 ? BigDecimal.ZERO
                : zoneQuote.divide(totalQuote, MC);
        return new Profile(time, lookback, poc, poc.add(step), from, to, totalQuote,
                zoneQuote, share, zoneDelta, stability);
    }

    private static boolean eligible(Map<BigDecimal, Amount> totals, BigDecimal price,
                                    BigDecimal threshold) {
        Amount amount = totals.get(price);
        return amount != null && amount.quote().compareTo(threshold) >= 0;
    }

    private static List<Bucket> group(List<VolumeProfileBin> bins) {
        Map<Instant, Map<BigDecimal, Amount>> grouped = new TreeMap<>();
        for (VolumeProfileBin bin : bins) {
            grouped.computeIfAbsent(bin.bucketTime(), ignored -> new HashMap<>())
                    .put(bin.priceFrom(), new Amount(bin.quoteNotional(), bin.deltaQuote()));
        }
        return grouped.entrySet().stream().map(entry -> new Bucket(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static void add(Map<BigDecimal, Amount> totals, Map<BigDecimal, Amount> addition) {
        addition.forEach((price, amount) -> totals.merge(price, amount, Amount::add));
    }

    private static void remove(Map<BigDecimal, Amount> totals, Map<BigDecimal, Amount> subtraction) {
        subtraction.forEach((price, amount) -> {
            Amount remaining = totals.get(price).subtract(amount);
            if (remaining.quote().signum() == 0) totals.remove(price); else totals.put(price, remaining);
        });
    }
}
