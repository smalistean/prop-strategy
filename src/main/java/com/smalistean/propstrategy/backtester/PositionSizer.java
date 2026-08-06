package com.smalistean.propstrategy.backtester;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public class PositionSizer {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final BigDecimal riskFraction;

    public PositionSizer(BigDecimal riskFraction) {
        if (riskFraction.compareTo(BigDecimal.ZERO) <= 0 || riskFraction.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("riskFraction must be in (0, 1]");
        }
        this.riskFraction = riskFraction;
    }

    public BigDecimal size(BigDecimal accountBalance, BigDecimal entryPrice, BigDecimal stopLossPrice) {
        BigDecimal riskAmount = accountBalance.multiply(riskFraction, MC);
        BigDecimal riskPerUnit = entryPrice.subtract(stopLossPrice, MC).abs();
        if (riskPerUnit.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return riskAmount.divide(riskPerUnit, MC);
    }
}
