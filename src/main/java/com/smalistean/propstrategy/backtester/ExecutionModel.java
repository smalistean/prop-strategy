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
    private final BigDecimal takerSlippageBps;
    private final BigDecimal takerFeeBps;
    private final BigDecimal makerFeeBps;

    public ExecutionModel(BigDecimal takerSlippageBps, BigDecimal takerFeeBps,
                          BigDecimal makerFeeBps) {
        if (takerSlippageBps.signum() < 0 || takerFeeBps.signum() < 0
                || makerFeeBps.signum() < 0) {
            throw new IllegalArgumentException("Fees and slippage cannot be negative");
        }
        this.takerSlippageBps = takerSlippageBps;
        this.takerFeeBps = takerFeeBps;
        this.makerFeeBps = makerFeeBps;
    }

    public Fill takerFill(BigDecimal referencePrice, boolean buy, BigDecimal quantity) {
        BigDecimal slippage = referencePrice.multiply(takerSlippageBps, MC).divide(BPS, MC);
        BigDecimal fillPrice = buy
                ? referencePrice.add(slippage, MC)
                : referencePrice.subtract(slippage, MC);
        BigDecimal fee = fillPrice.multiply(quantity, MC)
                .multiply(takerFeeBps, MC).divide(BPS, MC);
        BigDecimal slippageCost = slippage.multiply(quantity, MC);
        return new Fill(referencePrice, fillPrice, fee, slippageCost);
    }

    public Fill makerFill(BigDecimal limitPrice, BigDecimal quantity) {
        BigDecimal fee = limitPrice.multiply(quantity, MC)
                .multiply(makerFeeBps, MC).divide(BPS, MC);
        return new Fill(limitPrice, limitPrice, fee, BigDecimal.ZERO);
    }
}
