package com.smalistean.propstrategy.feature;

public record FeatureKey(String name, int period, int lookback) {

    public FeatureKey(String name, int period) {
        this(name, period, 0);
    }

    public FeatureKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Feature name is required");
        }
        if (period < 0) {
            throw new IllegalArgumentException("Feature period cannot be negative");
        }
        if (lookback < 0) {
            throw new IllegalArgumentException("Feature lookback cannot be negative");
        }
    }

    public static FeatureKey close() {
        return new FeatureKey("close", 0);
    }

    public static FeatureKey open() {
        return new FeatureKey("open", 0);
    }

    public static FeatureKey high() {
        return new FeatureKey("high", 0);
    }

    public static FeatureKey low() {
        return new FeatureKey("low", 0);
    }

    public static FeatureKey ema(int period) {
        return new FeatureKey("ema", period);
    }

    public static FeatureKey rsi(int period) {
        return new FeatureKey("rsi", period);
    }

    public static FeatureKey atr(int period) {
        return new FeatureKey("atr", period);
    }

    public static FeatureKey rollingHigh(int period) {
        return new FeatureKey("rollingHigh", period);
    }

    public static FeatureKey rollingLow(int period) {
        return new FeatureKey("rollingLow", period);
    }

    public static FeatureKey volumeRatio(int period) {
        return new FeatureKey("volumeRatio", period);
    }

    public static FeatureKey atrExpansion(int period) {
        return new FeatureKey("atrExpansion", period);
    }

    public static FeatureKey priorBollingerBandwidthPercentile(int period, int lookback) {
        return new FeatureKey("priorBollingerBandwidthPercentile", period, lookback);
    }

    public static FeatureKey orderFlowImbalance(int period) {
        return new FeatureKey("orderFlowImbalance", period);
    }

    public static FeatureKey rollingQuoteDelta(int period) {
        return new FeatureKey("rollingQuoteDelta", period);
    }

    public static FeatureKey largeTradeImbalance(int period) {
        return new FeatureKey("large100kImbalance", period);
    }

    public static FeatureKey orderFlowCoverage(int period) {
        return new FeatureKey("orderFlowCoverage", period);
    }

    public static FeatureKey orderFlowQuality(int period) {
        return new FeatureKey("orderFlowQuality", period);
    }

    public static FeatureKey priceReturn(int period) {
        return new FeatureKey("priceReturn", period);
    }

    public static FeatureKey deltaAcceleration(int fastPeriod, int slowPeriod) {
        return new FeatureKey("deltaAcceleration", fastPeriod, slowPeriod);
    }

    public static FeatureKey sellAbsorption(int period) {
        return new FeatureKey("sellAbsorption", period);
    }

    public static FeatureKey sellExhaustion(int fastPeriod, int slowPeriod) {
        return new FeatureKey("sellExhaustion", fastPeriod, slowPeriod);
    }

    public static FeatureKey priceFlowDivergence(int period) {
        return new FeatureKey("priceFlowDivergence", period);
    }

    public static FeatureKey volumeProfilePoc(int lookbackBuckets) {
        return new FeatureKey("volumeProfilePoc", lookbackBuckets);
    }

    public static FeatureKey volumeProfileZoneLow(int lookbackBuckets) {
        return new FeatureKey("volumeProfileZoneLow", lookbackBuckets);
    }

    public static FeatureKey volumeProfileZoneHigh(int lookbackBuckets) {
        return new FeatureKey("volumeProfileZoneHigh", lookbackBuckets);
    }

    public static FeatureKey volumeProfileZoneShare(int lookbackBuckets) {
        return new FeatureKey("volumeProfileZoneShare", lookbackBuckets);
    }

    public static FeatureKey volumeProfileZoneDelta(int lookbackBuckets) {
        return new FeatureKey("volumeProfileZoneDelta", lookbackBuckets);
    }

    public static FeatureKey volumeProfilePocStability(int lookbackBuckets) {
        return new FeatureKey("volumeProfilePocStability", lookbackBuckets);
    }

    public static FeatureKey exactBasePoc(int baseBars) {
        return new FeatureKey("exactBasePoc", baseBars);
    }

    public static FeatureKey exactBaseZoneLow(int baseBars) {
        return new FeatureKey("exactBaseZoneLow", baseBars);
    }

    public static FeatureKey exactBaseZoneHigh(int baseBars) {
        return new FeatureKey("exactBaseZoneHigh", baseBars);
    }

    public static FeatureKey exactBaseZoneShare(int baseBars) {
        return new FeatureKey("exactBaseZoneShare", baseBars);
    }

    public static FeatureKey selectedBaseBars() { return new FeatureKey("selectedBaseBars", 0); }
    public static FeatureKey selectedBaseLow() { return new FeatureKey("selectedBaseLow", 0); }
    public static FeatureKey selectedBaseHigh() { return new FeatureKey("selectedBaseHigh", 0); }
    public static FeatureKey selectedBaseZoneLow() { return new FeatureKey("selectedBaseZoneLow", 0); }
    public static FeatureKey selectedBaseZoneHigh() { return new FeatureKey("selectedBaseZoneHigh", 0); }
    public static FeatureKey selectedBaseZoneShare() { return new FeatureKey("selectedBaseZoneShare", 0); }
    public static FeatureKey selectedBasePocShare() { return new FeatureKey("selectedBasePocShare", 0); }
    public static FeatureKey selectedBaseTotalQuote() { return new FeatureKey("selectedBaseTotalQuote", 0); }
    public static FeatureKey selectedBaseVolumeRatio() { return new FeatureKey("selectedBaseVolumeRatio", 0); }
    /** Identity and lifecycle fields for a causal, persistent Apollo base map. */
    public static FeatureKey selectedBaseId() { return new FeatureKey("selectedBaseId", 0); }
    public static FeatureKey selectedBaseBreakoutSide() { return new FeatureKey("selectedBaseBreakoutSide", 0); }
    public static FeatureKey selectedBaseBreakoutVolumeRatio() { return new FeatureKey("selectedBaseBreakoutVolumeRatio", 0); }
    public static FeatureKey selectedBaseFirstRevisit() { return new FeatureKey("selectedBaseFirstRevisit", 0); }
    public static FeatureKey selectedBaseTarget() { return new FeatureKey("selectedBaseTarget", 0); }
    public static FeatureKey completedHourClose() { return new FeatureKey("completedHourClose", 0); }
    public static FeatureKey completedHourEma(int period) { return new FeatureKey("completedHourEma", period); }
    public static FeatureKey crossSectionRank() { return new FeatureKey("crossSectionRank", 0); }
    public static FeatureKey btcMarketHealthy() { return new FeatureKey("btcMarketHealthy", 0); }
    public static FeatureKey higherTimeframeSupport() { return new FeatureKey("higherTimeframeSupport", 0); }
    public static FeatureKey higherTimeframeResistance() { return new FeatureKey("higherTimeframeResistance", 0); }
    public static FeatureKey completedFourHourClose() { return new FeatureKey("completedFourHourClose", 0); }
    public static FeatureKey completedFourHourEma(int period) { return new FeatureKey("completedFourHourEma", period); }
}
