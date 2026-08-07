package com.smalistean.propstrategy.strategy;

public final class ThreeLevelRangeStrategyFactory implements StrategyFactory {
    @Override public String type() { return "three-level-range"; }

    @Override public Strategy create(StrategyParameters p) {
        return new ThreeLevelRangeStrategy(new ThreeLevelRangeStrategy.Config(
                ThreeLevelRangeStrategy.EntryMode.valueOf(
                        p.requiredString("entryMode").toUpperCase().replace('-', '_')),
                p.requiredInt("levelLookback"), p.requiredInt("pivotStrength"),
                p.requiredInt("minimumConfirmations"), p.requiredInt("atrPeriod"),
                p.requiredDecimal("levelToleranceAtr"), p.requiredDecimal("entryToleranceAtr"),
                p.requiredDecimal("minimumChannelWidthAtr"),
                p.requiredDecimal("targetChannelFraction"),
                p.requiredDecimal("adverseChannelFraction"), p.requiredDecimal("stopBufferAtr"),
                p.requiredDecimal("maximumRiskToReward"), p.requiredInt("maximumHoldingBars")));
    }
}
