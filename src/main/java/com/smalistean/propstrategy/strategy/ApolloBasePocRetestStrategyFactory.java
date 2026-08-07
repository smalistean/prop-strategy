package com.smalistean.propstrategy.strategy;

public final class ApolloBasePocRetestStrategyFactory implements StrategyFactory {
    @Override public String type() { return "apollo-base-poc-retest"; }

    @Override public Strategy create(StrategyParameters p) {
        return new ApolloBasePocRetestStrategy(new ApolloBasePocRetestStrategy.Config(
                p.requiredInt("atrPeriod"), p.requiredInt("volumePeriod"), p.requiredInt("baseBars"),
                p.requiredInt("breakoutSearchBars"), p.requiredDecimal("minimumBaseRangeAtr"),
                p.requiredDecimal("maximumBaseRangeAtr"), p.requiredDecimal("breakoutAtr"),
                p.requiredDecimal("minimumBreakoutVolumeRatio"), p.requiredDecimal("minimumZoneShare"),
                p.requiredDecimal("retestTouchAtr"), p.requiredDecimal("stopZoneHeightFraction"),
                p.requiredDecimal("minimumRewardRisk"), p.requiredInt("maximumHoldingBars")));
    }
}
