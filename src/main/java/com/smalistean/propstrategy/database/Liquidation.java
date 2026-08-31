package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One forced liquidation.
 *
 * <p>{@code side} is the liquidating order's direction, which is the opposite of the position
 * closed: SELL means a long was liquidated, BUY means a short was.
 */
public record Liquidation(
        String symbol,
        Instant eventTime,
        Instant tradeTime,
        String side,
        String orderType,
        String timeInForce,
        String orderStatus,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal averagePrice,
        BigDecimal lastFilledQty,
        BigDecimal filledAccumQty,
        BigDecimal notional
) {
}
