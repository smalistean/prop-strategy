package com.smalistean.propstrategy.strategy.apollo;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

public final class ApolloV4BasePocContinuationStrategyFactory implements StrategyFactory {
    @Override public String type() { return "apollo-v4-base-poc-continuation"; }
    @Override public Strategy create(StrategyParameters p) { return new ApolloV4BasePocContinuationStrategy(new ApolloV4BasePocContinuationStrategy.Config(
            p.requiredInt("atrPeriod"),p.requiredInt("volumePeriod"),p.requiredInt("minimumBaseBars"),p.requiredInt("maximumBaseBars"),p.requiredDecimal("minimumBaseRangeAtr"),p.requiredDecimal("maximumBaseRangeAtr"),p.requiredDecimal("maximumCenterDriftAtr"),p.requiredDecimal("maximumSlopeAtrPerBar"),p.requiredDecimal("maximumPenetrationFraction"),p.requiredDecimal("boundaryPenetrationAtr"),p.requiredDecimal("entranceDistanceAtr"),p.requiredInt("breakoutSearchBars"),p.requiredInt("reclaimWindowBars"),p.requiredInt("swingPivotStrength"),p.requiredDecimal("minimumSwingSizeAtr"),p.requiredDecimal("breakoutAtr"),p.requiredDecimal("minimumBreakoutVolumeRatio"),p.requiredDecimal("minimumZoneShare"),p.requiredDecimal("minimumPocShare"),p.requiredDecimal("minimumBaseVolumeRatio"),p.requiredDecimal("pocTouchAtr"),p.requiredDecimal("stopBaseHeightFraction"),p.requiredDecimal("minimumRewardRisk"),p.requiredInt("maximumHoldingBars"))); }
}
