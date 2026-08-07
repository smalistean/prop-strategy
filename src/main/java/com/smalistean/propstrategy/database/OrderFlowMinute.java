package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderFlowMinute(
        Instant minuteTime,
        BigDecimal quoteNotional,
        BigDecimal quoteDelta,
        BigDecimal large100kBuyQuote,
        BigDecimal large100kSellQuote,
        boolean reconciledExactly) {
}
