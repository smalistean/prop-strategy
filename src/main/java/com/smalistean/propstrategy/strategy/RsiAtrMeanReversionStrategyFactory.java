package com.smalistean.propstrategy.strategy;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class RsiAtrMeanReversionStrategyFactory implements StrategyFactory {

    @Override
    public String type() {
        return "rsi-atr-mean-reversion";
    }

    @Override
    public Strategy create(StrategyParameters parameters) {
        return new RsiAtrMeanReversionStrategy(new RsiAtrMeanReversionStrategy.Config(
                parameters.requiredBoolean("allowLong"),
                parameters.requiredBoolean("allowShort"),
                regimes(parameters.requiredString("longRegimes")),
                regimes(parameters.requiredString("shortRegimes")),
                parameters.requiredInt("regimeLookbackBars"),
                parameters.requiredDecimal("regimeDirectionalMovePercent"),
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

    private static Set<MarketRegime> regimes(String value) {
        if (value.equalsIgnoreCase("ALL")) {
            return Set.of(MarketRegime.values());
        }
        if (value.equalsIgnoreCase("NONE")) {
            return Set.of();
        }
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .map(String::toUpperCase)
                    .map(MarketRegime::valueOf)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Regimes must contain BULL, FLAT, BEAR, ALL, or NONE", e);
        }
    }
}
