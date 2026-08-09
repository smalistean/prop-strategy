package com.smalistean.propstrategy.strategy;

public final class LiquiditySweepReversalStrategyFactory implements StrategyFactory {
    @Override public String type() { return "liquidity-sweep-reversal"; }
    @Override public Strategy create(StrategyParameters p) {
        return new LiquiditySweepReversalStrategy(new LiquiditySweepReversalStrategy.Config(
                p.requiredInt("levelLookback"), p.requiredInt("pivotStrength"), p.requiredInt("minimumTouches"),
                p.requiredInt("atrPeriod"), p.requiredInt("volumePeriod"), p.requiredInt("localBreakBars"),
                p.requiredDecimal("levelToleranceAtr"), p.requiredDecimal("minimumSweepAtr"),
                p.requiredDecimal("minimumConfirmationVolumeRatio"), p.requiredDecimal("stopBufferAtr"),
                p.requiredDecimal("minimumRewardRisk"), p.requiredInt("maximumHoldingBars")));
    }
}
