package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

public record OpenInterestStatistic(
        String symbol,
        String period,
        Instant statisticTime,
        BigDecimal sumOpenInterest,
        BigDecimal sumOpenInterestValue,
        BigDecimal circulatingSupply
) {
}
