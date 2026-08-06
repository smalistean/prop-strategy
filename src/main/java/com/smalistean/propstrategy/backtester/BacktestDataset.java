package com.smalistean.propstrategy.backtester;

import java.time.Instant;

public record BacktestDataset(Type type, Instant startInclusive, Instant endExclusive) {

    public enum Type {
        TRAINING,
        VALIDATION,
        FINAL_TEST
    }

    public BacktestDataset {
        if (type == null || startInclusive == null || endExclusive == null
                || !startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("Invalid backtest dataset period");
        }
    }
}
