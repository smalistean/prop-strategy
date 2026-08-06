package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.strategy.Side;

import java.math.BigDecimal;
import java.time.Instant;

public record Trade(
        Instant entryTime,
        Instant exitTime,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        BigDecimal quantity,
        Side side,
        BigDecimal grossPnl,
        BigDecimal entryFee,
        BigDecimal exitFee,
        BigDecimal fundingPnl,
        BigDecimal entrySlippageCost,
        BigDecimal exitSlippageCost,
        BigDecimal netPnl,
        String exitReason
) {
}
