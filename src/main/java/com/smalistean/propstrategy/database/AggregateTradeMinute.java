package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

public record AggregateTradeMinute(
        String symbol, Instant minuteTime, Instant firstEventTime, Instant lastEventTime,
        long firstAggregateTradeId, long lastAggregateTradeId, int aggregateTradeCount,
        long underlyingTradeCount, BigDecimal baseVolume, BigDecimal quoteNotional,
        BigDecimal aggressiveBuyBase, BigDecimal aggressiveSellBase,
        BigDecimal aggressiveBuyQuote, BigDecimal aggressiveSellQuote,
        BigDecimal baseDelta, BigDecimal quoteDelta, BigDecimal firstPrice,
        BigDecimal lastPrice, BigDecimal minimumPrice, BigDecimal maximumPrice,
        BigDecimal buyVwap, BigDecimal sellVwap, BigDecimal maximumAggregateQuote,
        int large10kCount, BigDecimal large10kBuyQuote, BigDecimal large10kSellQuote,
        int large100kCount, BigDecimal large100kBuyQuote, BigDecimal large100kSellQuote,
        int large1mCount, BigDecimal large1mBuyQuote, BigDecimal large1mSellQuote,
        long aggregateTradeIdGapCount, int duplicateCount) {
}
