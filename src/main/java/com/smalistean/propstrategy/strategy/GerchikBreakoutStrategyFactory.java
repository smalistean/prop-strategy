package com.smalistean.propstrategy.strategy;

public final class GerchikBreakoutStrategyFactory implements StrategyFactory {
    @Override public String type() { return "gerchik-breakout"; }
    @Override public Strategy create(StrategyParameters p) {
        return new GerchikBreakoutStrategy(new GerchikBreakoutStrategy.Config(
                p.requiredInt("atrPeriod"), p.requiredInt("compressionBars"),
                p.requiredDecimal("compressionAtrFraction"), p.requiredDecimal("approachDistanceAtr"),
                p.requiredDecimal("entryOffsetFraction"), p.requiredDecimal("stopBufferAtr"),
                p.requiredDecimal("minimumRewardRisk"), p.requiredInt("requireMirror"),
                p.requiredInt("minimumTouches"), p.requiredInt("orderLifetimeBars"),
                p.requiredInt("maximumHoldingBars")));
    }
}
