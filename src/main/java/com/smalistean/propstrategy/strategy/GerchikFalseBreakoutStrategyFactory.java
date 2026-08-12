package com.smalistean.propstrategy.strategy;

public final class GerchikFalseBreakoutStrategyFactory implements StrategyFactory {
    @Override public String type() { return "gerchik-false-breakout"; }
    @Override public Strategy create(StrategyParameters p) {
        return new GerchikFalseBreakoutStrategy(new GerchikFalseBreakoutStrategy.Config(
                p.requiredInt("atrPeriod"),
                p.requiredDecimal("maximumBreakDepthAtr"),
                p.requiredDecimal("entryOffsetFraction"),
                p.requiredDecimal("stopBufferAtr"),
                p.requiredDecimal("minimumRewardRisk"),
                p.requiredInt("requireMirror"),
                p.requiredInt("minimumTouches"),
                p.requiredInt("orderLifetimeBars"),
                p.requiredInt("maximumHoldingBars")));
    }
}
