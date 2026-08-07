package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

public record VolumeProfileBin(String symbol, Instant bucketTime, int bucketMinutes,
                               BigDecimal priceStep, BigDecimal priceFrom,
                               long aggregateTradeCount, BigDecimal baseVolume,
                               BigDecimal quoteNotional, BigDecimal aggressiveBuyQuote,
                               BigDecimal aggressiveSellQuote) {
    public VolumeProfileBin {
        if (symbol == null || symbol.isBlank() || bucketTime == null) {
            throw new IllegalArgumentException("Symbol and bucketTime are required");
        }
        if (bucketMinutes <= 0 || priceStep.signum() <= 0 || aggregateTradeCount < 0) {
            throw new IllegalArgumentException("Invalid volume-profile bin dimensions");
        }
    }

    public BigDecimal priceTo() {
        return priceFrom.add(priceStep);
    }

    public BigDecimal deltaQuote() {
        return aggressiveBuyQuote.subtract(aggressiveSellQuote);
    }
}
