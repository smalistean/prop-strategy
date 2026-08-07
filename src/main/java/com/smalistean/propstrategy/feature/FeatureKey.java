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
}
