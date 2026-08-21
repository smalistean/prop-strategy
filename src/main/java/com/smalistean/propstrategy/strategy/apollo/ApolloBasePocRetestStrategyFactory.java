package com.smalistean.propstrategy.strategy.apollo;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

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
