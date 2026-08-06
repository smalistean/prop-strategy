package com.smalistean.propstrategy.strategy;

public final class IntradayFlatMeanReversionStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return "intraday-flat-mean-reversion";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new IntradayFlatMeanReversionStrategy(
                new IntradayFlatMeanReversionStrategy.Config(
                        parameters.requiredInt("meanEmaPeriod"),
                        parameters.requiredInt("slopeLookbackBars"),
                        parameters.requiredDecimal("maximumEmaSlopeBps"),
                        parameters.requiredInt("rsiPeriod"),
                        parameters.requiredDecimal("longEntryRsi"),
                        parameters.requiredDecimal("shortEntryRsi"),
                        parameters.requiredInt("atrPeriod"),
                        parameters.requiredDecimal("minimumDeviationAtr"),
                        parameters.requiredDecimal("maximumAtrExpansionRatio"),
                        parameters.requiredDecimal("stopAtrMultiplier"),
                        parameters.requiredDecimal("minimumTargetAtr"),
                        parameters.requiredInt("maxHoldingBars")));
    }
}
