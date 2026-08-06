package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

public record FundingRate(
        String symbol,
        Instant fundingTime,
        String rateType,
        BigDecimal fundingRate,
        BigDecimal markPrice
) {
}
