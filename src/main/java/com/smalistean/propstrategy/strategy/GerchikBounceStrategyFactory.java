package com.smalistean.propstrategy.strategy;

public final class GerchikBounceStrategyFactory implements StrategyFactory {
    @Override public String type() { return "gerchik-bounce"; }
    @Override public Strategy create(StrategyParameters p) {
        return new GerchikBounceStrategy(new GerchikBounceStrategy.Config(
                p.requiredInt("atrPeriod"), p.requiredDecimal("entryOffsetFraction"),
                p.requiredInt("maximumBpuGapBars"), p.requiredDecimal("stopBufferAtr"),
                p.requiredDecimal("minimumRewardRisk"), p.requiredInt("requireMirror"),
                p.requiredInt("minimumTouches"), p.requiredInt("orderLifetimeBars"),
                p.requiredInt("maximumHoldingBars")));
    }
}
