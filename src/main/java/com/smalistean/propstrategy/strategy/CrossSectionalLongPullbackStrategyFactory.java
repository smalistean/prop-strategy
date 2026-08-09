package com.smalistean.propstrategy.strategy;

public final class CrossSectionalLongPullbackStrategyFactory implements StrategyFactory {
    @Override public String type() { return "cross-sectional-long-pullback"; }

    @Override public Strategy create(StrategyParameters parameters) {
        return new CrossSectionalLongPullbackStrategy(new CrossSectionalLongPullbackStrategy.Config(
                parameters.requiredInt("topRank"), parameters.requiredInt("fastEma"),
                parameters.requiredInt("slowEma"), parameters.requiredInt("rsiPeriod"),
                parameters.requiredInt("atrPeriod"), parameters.requiredInt("volumePeriod"),
                parameters.requiredDecimal("minimumRsi"), parameters.requiredDecimal("maximumRsi"),
                parameters.requiredDecimal("minimumVolumeRatio"), parameters.requiredDecimal("stopAtrMultiplier"),
                parameters.requiredDecimal("rewardRiskRatio"), parameters.requiredInt("maxHoldingBars")));
    }
}
