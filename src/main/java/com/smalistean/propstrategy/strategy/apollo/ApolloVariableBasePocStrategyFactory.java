package com.smalistean.propstrategy.strategy.apollo;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

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
