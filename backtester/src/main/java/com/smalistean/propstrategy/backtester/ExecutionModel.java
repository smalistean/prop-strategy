package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.database.Kline;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public class ExecutionModel {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final BigDecimal slippageBps;
    private final BigDecimal feeBps;

    public ExecutionModel(BigDecimal slippageBps, BigDecimal feeBps) {
        this.slippageBps = slippageBps;
        this.feeBps = feeBps;
    }

    public BigDecimal fillPrice(Kline bar, boolean isBuy) {
        BigDecimal slippage = bar.close().multiply(slippageBps, MC)
                .divide(BigDecimal.valueOf(10_000), MC);
        return isBuy ? bar.close().add(slippage, MC) : bar.close().subtract(slippage, MC);
    }

    public BigDecimal fee(BigDecimal notional) {
        return notional.multiply(feeBps, MC).divide(BigDecimal.valueOf(10_000), MC);
    }
}
