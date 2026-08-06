package com.smalistean.propstrategy.strategy;

public final class RsiAtrMeanReversionStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return "rsi-atr-mean-reversion";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new RsiAtrMeanReversionStrategy(new RsiAtrMeanReversionStrategy.Config(
                parameters.requiredInt("trendEmaPeriod"),
                parameters.requiredInt("rsiPeriod"),
                parameters.requiredDecimal("longEntryRsi"),
                parameters.requiredDecimal("shortEntryRsi"),
                parameters.requiredDecimal("longExitRsi"),
                parameters.requiredDecimal("shortExitRsi"),
                parameters.requiredInt("atrPeriod"),
                parameters.requiredDecimal("maximumAtrExpansionRatio"),
                parameters.requiredDecimal("stopAtrMultiplier"),
                parameters.requiredDecimal("rewardRiskRatio"),
                parameters.requiredInt("maxHoldingBars")));
    }
}
