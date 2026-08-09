package com.smalistean.propstrategy.strategy;
public final class HigherTimeframeLiquiditySweepStrategyFactory implements StrategyFactory {
 @Override public String type(){return "apollo-higher-timeframe-liquidity-sweep";}
 @Override public Strategy create(StrategyParameters p){return new HigherTimeframeLiquiditySweepStrategy(new HigherTimeframeLiquiditySweepStrategy.Config(p.requiredInt("atrPeriod"),p.requiredInt("volumePeriod"),p.requiredInt("localBreakBars"),p.requiredDecimal("sweepAtr"),p.requiredDecimal("minimumConfirmationVolumeRatio"),p.requiredDecimal("stopBufferAtr"),p.requiredDecimal("minimumRewardRisk"),p.requiredInt("maximumHoldingBars")));}
}
