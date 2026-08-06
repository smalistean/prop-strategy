package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.time.Instant;

public record TraderRatio(
        String symbol,
        String period,
        RatioType ratioType,
        Instant statisticTime,
        BigDecimal longShortRatio,
        BigDecimal longShare,
        BigDecimal shortShare
) {
    public enum RatioType {
        GLOBAL_ACCOUNT,
        TOP_ACCOUNT,
        TOP_POSITION
    }
}
