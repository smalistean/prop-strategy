package com.smalistean.propstrategy.strategy;

public final class PassiveMakerMeanReversionStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return "passive-maker-mean-reversion";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new PassiveMakerMeanReversionStrategy(
                new PassiveMakerMeanReversionStrategy.Config(
                        parameters.requiredInt("meanEmaPeriod"),
                        parameters.requiredInt("slopeLookbackBars"),
                        parameters.requiredDecimal("maximumEmaSlopeBps"),
                        parameters.requiredInt("rsiPeriod"),
                        parameters.requiredDecimal("longEntryRsi"),
                        parameters.requiredDecimal("shortEntryRsi"),
                        parameters.requiredInt("atrPeriod"),
                        parameters.requiredDecimal("minimumDeviationAtr"),
                        parameters.requiredDecimal("stopAtrMultiplier"),
                        parameters.requiredDecimal("targetAtrMultiplier"),
                        parameters.requiredInt("maximumHoldingBars")));
    }
}
