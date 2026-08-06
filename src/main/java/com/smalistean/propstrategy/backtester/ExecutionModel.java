package com.smalistean.propstrategy.backtester;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class ExecutionModel {

    public record Fill(BigDecimal referencePrice, BigDecimal fillPrice,
                       BigDecimal fee, BigDecimal slippageCost) {
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private final BigDecimal slippageBps;
    private final BigDecimal takerFeeBps;

    public ExecutionModel(BigDecimal slippageBps, BigDecimal takerFeeBps) {
        if (slippageBps.signum() < 0 || takerFeeBps.signum() < 0) {
            throw new IllegalArgumentException("Fees and slippage cannot be negative");
        }
        this.slippageBps = slippageBps;
        this.takerFeeBps = takerFeeBps;
    }

    public Fill fill(BigDecimal referencePrice, boolean buy, BigDecimal quantity) {
        BigDecimal slippage = referencePrice.multiply(slippageBps, MC).divide(BPS, MC);
        BigDecimal fillPrice = buy
                ? referencePrice.add(slippage, MC)
                : referencePrice.subtract(slippage, MC);
        BigDecimal fee = fillPrice.multiply(quantity, MC)
                .multiply(takerFeeBps, MC).divide(BPS, MC);
        BigDecimal slippageCost = slippage.multiply(quantity, MC);
        return new Fill(referencePrice, fillPrice, fee, slippageCost);
    }
}
