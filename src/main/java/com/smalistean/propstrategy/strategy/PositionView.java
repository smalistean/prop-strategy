package com.smalistean.propstrategy.strategy;

import java.math.BigDecimal;
import java.time.Instant;

public record PositionView(
        Side side,
        Instant entryTime,
        BigDecimal entryPrice,
        BigDecimal quantity,
        int barsHeld
) {
    public static PositionView flat() {
        return new PositionView(null, null, null, BigDecimal.ZERO, 0);
    }

    public boolean isOpen() {
        return side != null;
    }
}
