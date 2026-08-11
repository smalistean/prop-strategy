package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.PostgresMetricSnapshotRepository.Snapshot;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Attaches market regime and market-wide aggressor imbalance to each snapshot, from Binance
 * open-interest and taker-volume metrics.
 *
 * <p>Why: the zone aggressor delta tested in {@code APOLLO_V6_PHASE1A.md} was strongly positive
 * in-sample over a bull run (+$94.97 per trade) and inverted out-of-sample over the 2022 bear
 * (-$31.89). The hypothesis under test is that aggression predicts continuation when it runs with
 * prevailing flow and gets absorbed when it runs against it, which makes the delta's sign
 * regime-dependent rather than constant. Nothing in Apollo has ever had a regime term.
 *
 * <p>Regime is open interest against price, the standard reading of futures positioning, over a
 * fixed 30-day window declared before implementation:
 * <ul>
 *   <li>rising OI with rising price: new longs, {@code +1}</li>
 *   <li>rising OI with falling price: new shorts, {@code -1}</li>
 *   <li>falling OI: positions unwinding rather than building, {@code 0} - directionally
 *       uninformative for this purpose, and deliberately not split further</li>
 * </ul>
 *
 * <p>Causality: each snapshot receives the most recent metric row at or before its own candle open,
 * and the 30-day comparison looks only backwards. Metrics are published on a 5-minute grid, so a
 * 15-minute bar always has a strictly prior observation available; where it does not - the first
 * 30 days of a symbol's history, or a gap in Binance's archives - no feature is emitted at all
 * rather than a guessed neutral, so the strategy sees absence instead of a fabricated zero.
 */
public final class MarketRegimeAssembler {

    private static final java.math.MathContext MC =
            new java.math.MathContext(20, java.math.RoundingMode.HALF_UP);

    public List<FeatureSnapshot> attach(List<FeatureSnapshot> snapshots, List<Snapshot> metrics,
                                        int regimeDays) {
        if (metrics.isEmpty()) {
            return snapshots;
        }
        NavigableMap<Instant, Snapshot> byTime = new TreeMap<>();
        for (Snapshot metric : metrics) {
            if (metric.openInterest() != null) {
                byTime.put(metric.time(), metric);
            }
        }
        if (byTime.isEmpty()) {
            return snapshots;
        }
        NavigableMap<Instant, BigDecimal> takerByTime = new TreeMap<>();
        for (Snapshot metric : metrics) {
            if (metric.takerVolumeRatio() != null) {
                takerByTime.put(metric.time(), metric.takerVolumeRatio());
            }
        }
        long lookbackSeconds = (long) regimeDays * 24 * 60 * 60;

        List<FeatureSnapshot> result = new ArrayList<>(snapshots.size());
        for (FeatureSnapshot snapshot : snapshots) {
            Map<FeatureKey, BigDecimal> values = new HashMap<>(snapshot.values());
            Instant at = snapshot.candleOpenTime();

            var current = byTime.floorEntry(at);
            var prior = byTime.floorEntry(at.minusSeconds(lookbackSeconds));
            if (current != null && prior != null
                    && snapshot.values().containsKey(FeatureKey.close())) {
                BigDecimal openInterestChange =
                        current.getValue().openInterest().subtract(prior.getValue().openInterest(), MC);
                BigDecimal priorClose = priceAt(snapshots, prior.getKey());
                if (priorClose != null && priorClose.signum() > 0) {
                    BigDecimal priceChange = snapshot.require(FeatureKey.close()).subtract(priorClose, MC);
                    int regime = openInterestChange.signum() <= 0 ? 0
                            : priceChange.signum() > 0 ? 1
                            : priceChange.signum() < 0 ? -1 : 0;
                    values.put(FeatureKey.marketRegime(), BigDecimal.valueOf(regime));
                }
            }
            var taker = takerByTime.floorEntry(at);
            if (taker != null) {
                values.put(FeatureKey.marketTakerRatio(), taker.getValue());
            }
            result.add(new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(),
                    snapshot.earliestExecutionTime(), values));
        }
        return result;
    }

    /**
     * Close of the snapshot at or before the given instant. The price leg of the regime has to come
     * from the same series the strategy trades; taking it from the metrics file's own notional would
     * mix two different price sources and make the comparison unreliable at the margins.
     */
    private static BigDecimal priceAt(List<FeatureSnapshot> snapshots, Instant at) {
        int low = 0;
        int high = snapshots.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (snapshots.get(mid).candleOpenTime().isAfter(at)) {
                high = mid - 1;
            } else {
                found = mid;
                low = mid + 1;
            }
        }
        if (found < 0) {
            return null;
        }
        FeatureSnapshot at_ = snapshots.get(found);
        return at_.values().containsKey(FeatureKey.close()) ? at_.require(FeatureKey.close()) : null;
    }
}
