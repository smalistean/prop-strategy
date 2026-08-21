package com.smalistean.propstrategy.strategy.gerchik;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

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
