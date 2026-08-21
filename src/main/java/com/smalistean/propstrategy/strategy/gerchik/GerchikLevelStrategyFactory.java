package com.smalistean.propstrategy.strategy.gerchik;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

public final class GerchikLevelStrategyFactory implements StrategyFactory {
    @Override public String type() { return "gerchik-level"; }

    @Override
    public Strategy create(StrategyParameters p) {
        return new GerchikLevelStrategy(new GerchikLevelStrategy.Config(
                GerchikLevelStrategy.Reaction.valueOf(p.requiredString("reaction").toUpperCase().replace('-', '_')),
                p.requiredInt("levelLookback"), p.requiredInt("atrPeriod"),
                p.requiredInt("pivotStrength"), p.requiredInt("minimumConfirmations"),
                p.requiredDecimal("levelToleranceAtr"), p.requiredDecimal("approachAtr"),
                p.requiredInt("approachBars"), p.requiredDecimal("maximumApproachOverlap"),
                p.requiredDecimal("breakoutAtr"), p.requiredDecimal("stopBufferAtr"),
                p.requiredDecimal("minimumRewardRisk"), p.requiredDecimal("targetAtr"),
                p.requiredInt("maximumHoldingBars")));
    }
}
