package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One second of best bid/ask for one symbol.
 *
 * <p>{@code minBid} and {@code maxAsk} carry the information a passive fill depends on: an order
 * resting at a price is only reachable if the touch actually travelled to it, and a second's
 * closing quote alone cannot show that.
 */
public record BookTickerSecond(
        String symbol,
        Instant secondTime,
        int updates,
        BigDecimal openBid,
        BigDecimal openAsk,
        BigDecimal closeBid,
        BigDecimal closeAsk,
        BigDecimal minBid,
        BigDecimal maxBid,
        BigDecimal minAsk,
        BigDecimal maxAsk,
        BigDecimal closeBidQty,
        BigDecimal closeAskQty,
        BigDecimal meanSpreadBps,
        BigDecimal minSpreadBps,
        BigDecimal maxSpreadBps
) {
}
