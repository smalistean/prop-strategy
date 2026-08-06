package com.smalistean.propstrategy.feature;

import java.math.BigDecimal;
import java.time.Instant;

public record FeatureRow(
        Instant candleOpenTime,
        Instant availableAt,
        Instant earliestExecutionTime,
        BigDecimal close,
        BigDecimal returnPercent,
        BigDecimal ema20,
        BigDecimal ema50,
        BigDecimal rsi14,
        BigDecimal atr14,
        BigDecimal rollingVolatility20,
        BigDecimal volumeRatio20,
        BigDecimal bodyPercent,
        BigDecimal upperWickPercent,
        BigDecimal lowerWickPercent,
        BigDecimal openInterestChangePercent,
        BigDecimal fundingRate,
        BigDecimal globalAccountRatio,
        BigDecimal topAccountRatio,
        BigDecimal topPositionRatio
) {
}
