package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

public record Kline(
        Instant openTime,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        BigDecimal volume,
        Instant closeTime,
        BigDecimal quoteAssetVolume,
        int tradeCount,
        BigDecimal takerBuyBaseVolume,
        BigDecimal takerBuyQuoteVolume
) {
    public Kline(Instant openTime, BigDecimal open, BigDecimal high, BigDecimal low,
                 BigDecimal close, BigDecimal volume) {
        this(openTime, open, high, low, close, volume, null, null, 0, null, null);
    }
}
