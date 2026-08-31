package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One second of top-of-book queue shape for one symbol.
 *
 * <p>{@code minBidQty1} and {@code minAskQty1} matter more than the means: a passive order is
 * reached when the queue in front of it is consumed, so the smallest the touch queue got during a
 * second is closer to the fill question than its average size.
 */
public record BookDepthSecond(
        String symbol,
        Instant secondTime,
        int snapshots,
        BigDecimal meanBidQty1,
        BigDecimal meanAskQty1,
        BigDecimal minBidQty1,
        BigDecimal minAskQty1,
        BigDecimal meanBidNotional,
        BigDecimal meanAskNotional,
        BigDecimal meanBidSpanBps,
        BigDecimal meanAskSpanBps
) {
}
