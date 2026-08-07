package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public final class RsiAtrMeanReversionStrategy implements Strategy {

    public record Config(
            boolean allowLong,
            boolean allowShort,
            Set<MarketRegime> longRegimes,
            Set<MarketRegime> shortRegimes,
            int regimeLookbackBars,
            BigDecimal regimeDirectionalMovePercent,
            int trendEmaPeriod,
            int rsiPeriod,
            BigDecimal longEntryRsi,
            BigDecimal shortEntryRsi,
            BigDecimal longExitRsi,
            BigDecimal shortExitRsi,
            int atrPeriod,
            BigDecimal maximumAtrExpansionRatio,
            BigDecimal stopAtrMultiplier,
            BigDecimal rewardRiskRatio,
            int maxHoldingBars
    ) {
        public Config {
            longRegimes = Set.copyOf(longRegimes);
            shortRegimes = Set.copyOf(shortRegimes);
            if ((!allowLong && !allowShort) || (allowLong && longRegimes.isEmpty())
                    || (allowShort && shortRegimes.isEmpty())
                    || regimeLookbackBars <= 0 || regimeDirectionalMovePercent.signum() <= 0
                    || trendEmaPeriod <= 1 || rsiPeriod <= 1 || atrPeriod <= 1
                    || longEntryRsi.signum() < 0 || longEntryRsi.compareTo(longExitRsi) >= 0
                    || longExitRsi.compareTo(BigDecimal.valueOf(100)) > 0
                    || shortExitRsi.signum() < 0 || shortExitRsi.compareTo(shortEntryRsi) >= 0
                    || shortEntryRsi.compareTo(BigDecimal.valueOf(100)) > 0
                    || maximumAtrExpansionRatio.signum() <= 0
                    || stopAtrMultiplier.signum() <= 0 || rewardRiskRatio.signum() <= 0
                    || maxHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid RSI/ATR mean-reversion configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey trendEma;
    private final FeatureKey rsi;
    private final FeatureKey atr;
    private final FeatureKey atrExpansion;
    private final MarketRegimeClassifier regimeClassifier;

    public RsiAtrMeanReversionStrategy(Config config) {
        this.config = config;
        this.trendEma = FeatureKey.ema(config.trendEmaPeriod());
        this.rsi = FeatureKey.rsi(config.rsiPeriod());
        this.atr = FeatureKey.atr(config.atrPeriod());
        this.atrExpansion = FeatureKey.atrExpansion(config.atrPeriod());
        this.regimeClassifier = new MarketRegimeClassifier(
                config.regimeLookbackBars(), config.regimeDirectionalMovePercent());
    }

    @Override
    public String name() {
        return "rsi-atr-mean-reversion";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, trendEma, rsi, atr, atrExpansion);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                     PositionView position) {
        FeatureSnapshot current = history.get(index);
        BigDecimal currentClose = current.require(close);
        BigDecimal currentRsi = current.require(rsi);

        if (position.isOpen()) {
            if (position.barsHeld() >= config.maxHoldingBars()) {
                return new StrategyDecision.Exit("maximum holding period");
            }
            if (position.side() == Side.LONG
                    && (currentRsi.compareTo(config.longExitRsi()) >= 0
                    || currentClose.compareTo(current.require(trendEma)) < 0)) {
                return new StrategyDecision.Exit("long mean reversion or trend failure");
            }
            if (position.side() == Side.SHORT
                    && (currentRsi.compareTo(config.shortExitRsi()) <= 0
                    || currentClose.compareTo(current.require(trendEma)) > 0)) {
                return new StrategyDecision.Exit("short mean reversion or trend failure");
            }
            return StrategyDecision.hold();
        }

        if (index < config.regimeLookbackBars() || current.require(atrExpansion)
                .compareTo(config.maximumAtrExpansionRatio()) > 0) {
            return StrategyDecision.hold();
        }
        MarketRegime regime = regimeClassifier.classify(history, index);
        BigDecimal previousRsi = history.get(index - 1).require(rsi);
        BigDecimal stopDistance = current.require(atr).multiply(config.stopAtrMultiplier(), MC);
        BigDecimal targetDistance = stopDistance.multiply(config.rewardRiskRatio(), MC);

        boolean longSetup = config.allowLong()
                && config.longRegimes().contains(regime)
                && currentClose.compareTo(current.require(trendEma)) > 0
                && previousRsi.compareTo(config.longEntryRsi()) > 0
                && currentRsi.compareTo(config.longEntryRsi()) <= 0;
        if (longSetup) {
            return new StrategyDecision.Enter(Side.LONG, stopDistance, targetDistance);
        }
        boolean shortSetup = config.allowShort()
                && config.shortRegimes().contains(regime)
                && currentClose.compareTo(current.require(trendEma)) < 0
                && previousRsi.compareTo(config.shortEntryRsi()) < 0
                && currentRsi.compareTo(config.shortEntryRsi()) >= 0;
        return shortSetup
                ? new StrategyDecision.Enter(Side.SHORT, stopDistance, targetDistance)
                : StrategyDecision.hold();
    }
}
