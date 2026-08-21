package com.smalistean.propstrategy.strategy.apollo;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyFactory;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;

/** Binds the frozen v3 configuration to its ordered-sequence strategy. */
public final class ApolloOrderedLiquiditySequenceStrategyFactory implements StrategyFactory {
    @Override public String type() { return "apollo-ordered-liquidity-sequence-v3"; }
    @Override public Strategy create(StrategyParameters p) {
        return new ApolloOrderedLiquiditySequenceStrategy(new ApolloOrderedLiquiditySequenceStrategy.Config(
                p.requiredInt("atrPeriod"), p.requiredInt("volumePeriod"),
                p.requiredInt("freshnessBars"), p.requiredInt("reclaimWindowBars"),
                p.requiredInt("minimumAcceptanceBars"), p.requiredInt("localBreakBars"),
                p.requiredInt("sweepSearchBars"),
                p.requiredDecimal("sweepAtr"), p.requiredDecimal("levelBufferAtr"),
                p.requiredDecimal("minimumBodyAtr"), p.requiredDecimal("minimumConfirmationVolumeRatio"),
                p.requiredDecimal("stopBufferAtr"), p.requiredDecimal("minimumRewardRisk"),
                p.requiredInt("maximumHoldingBars"), p.booleanOrDefault("higherTimeframeAlignment", false)));
    }
}
