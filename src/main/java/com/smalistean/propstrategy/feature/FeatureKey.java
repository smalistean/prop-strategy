package com.smalistean.propstrategy.feature;

public record FeatureKey(String name, int period) {

    public FeatureKey {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Feature name is required");
        }
        if (period < 0) {
            throw new IllegalArgumentException("Feature period cannot be negative");
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
}
