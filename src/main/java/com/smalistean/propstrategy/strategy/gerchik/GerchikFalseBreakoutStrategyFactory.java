package com.smalistean.propstrategy.strategy.gerchik;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

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
