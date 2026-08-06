package com.smalistean.propstrategy.feature;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

public record FeatureSnapshot(
        Instant candleOpenTime,
        Instant availableAt,
        Instant earliestExecutionTime,
        Map<FeatureKey, BigDecimal> values
) {
    public FeatureSnapshot {
        values = Map.copyOf(values);
    }

    public BigDecimal require(FeatureKey key) {
        BigDecimal value = values.get(key);
        if (value == null) {
            throw new IllegalStateException("Required feature is unavailable: " + key);
        }
        return value;
    }
}
