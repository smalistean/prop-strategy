package com.smalistean.propstrategy.backtester;

import java.math.BigDecimal;
import java.time.Instant;

public record Trade(
        Instant entryTime,
        Instant exitTime,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal quantity,
        BigDecimal pnl,
        String side
) {
}
