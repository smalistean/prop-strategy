package com.smalistean.propstrategy.statistics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

public class DrawdownCalculator {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    public record DrawdownStats(
            BigDecimal maxDrawdown,
            BigDecimal maxDrawdownPct
    ) {
    }

    public DrawdownStats calculate(List<BigDecimal> equityCurve) {
        if (equityCurve.isEmpty()) {
            return new DrawdownStats(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal peak = equityCurve.getFirst();
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        BigDecimal maxDrawdownPct = BigDecimal.ZERO;

        for (BigDecimal equity : equityCurve) {
            peak = equity.max(peak);
            BigDecimal drawdown = peak.subtract(equity, MC);
            maxDrawdown = drawdown.max(maxDrawdown);
            if (peak.signum() > 0) {
                BigDecimal pct = drawdown.divide(peak, MC).multiply(BigDecimal.valueOf(100), MC);
                maxDrawdownPct = pct.max(maxDrawdownPct);
            }
        }
        return new DrawdownStats(maxDrawdown, maxDrawdownPct);
    }
}
