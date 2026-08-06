package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

public record Kline(
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume
) {
}
