package com.smalistean.propstrategy.strategy;

public final class ApolloVariableBasePocStrategyFactory implements StrategyFactory {
    @Override public String type() { return "apollo-variable-base-poc"; }
    @Override public Strategy create(StrategyParameters p) {
        return new ApolloVariableBasePocStrategy(new ApolloVariableBasePocStrategy.Config(
                p.requiredInt("atrPeriod"), p.requiredInt("volumePeriod"), p.requiredInt("minimumBaseBars"),
                p.requiredInt("maximumBaseBars"), p.requiredDecimal("minimumBaseRangeAtr"),
                p.requiredDecimal("maximumBaseRangeAtr"), p.requiredDecimal("maximumCenterDriftAtr"),
                p.requiredDecimal("maximumSlopeAtrPerBar"), p.requiredDecimal("maximumPenetrationFraction"),
                p.requiredDecimal("boundaryPenetrationAtr"), p.requiredDecimal("entranceDistanceAtr"),
                p.requiredInt("breakoutSearchBars"),
                p.requiredDecimal("breakoutAtr"), p.requiredDecimal("minimumBreakoutVolumeRatio"),
                p.requiredDecimal("minimumZoneShare"), p.requiredDecimal("retestTouchAtr"),
                p.requiredDecimal("stopZoneHeightFraction"), p.requiredDecimal("minimumRewardRisk"),
                p.requiredInt("maximumHoldingBars"), p.requiredBoolean("longOnly"),
                p.requiredBoolean("hourlyAlignment"), p.requiredInt("hourlyEmaPeriod")));
    }
}
