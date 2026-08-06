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
}
