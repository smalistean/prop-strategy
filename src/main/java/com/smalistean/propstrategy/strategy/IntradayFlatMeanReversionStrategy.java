package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public final class IntradayFlatMeanReversionStrategy implements Strategy {

    public record Config(
            int meanEmaPeriod,
            int slopeLookbackBars,
            BigDecimal maximumEmaSlopeBps,
            int rsiPeriod,
            BigDecimal longEntryRsi,
            BigDecimal shortEntryRsi,
            int atrPeriod,
            BigDecimal minimumDeviationAtr,
            BigDecimal maximumAtrExpansionRatio,
            BigDecimal stopAtrMultiplier,
            BigDecimal minimumTargetAtr,
            int maxHoldingBars
    ) {
        public Config {
            if (meanEmaPeriod <= 1 || slopeLookbackBars <= 0 || maximumEmaSlopeBps.signum() < 0
                    || rsiPeriod <= 1 || longEntryRsi.signum() < 0
                    || shortEntryRsi.compareTo(BigDecimal.valueOf(100)) > 0
                    || longEntryRsi.compareTo(shortEntryRsi) >= 0 || atrPeriod <= 1
                    || minimumDeviationAtr.signum() < 0 || maximumAtrExpansionRatio.signum() <= 0
                    || stopAtrMultiplier.signum() <= 0 || minimumTargetAtr.signum() <= 0
                    || maxHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid intraday flat mean-reversion configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey meanEma;
    private final FeatureKey rsi;
    private final FeatureKey atr;
    private final FeatureKey atrExpansion;

    public IntradayFlatMeanReversionStrategy(Config config) {
        this.config = config;
        this.meanEma = FeatureKey.ema(config.meanEmaPeriod());
        this.rsi = FeatureKey.rsi(config.rsiPeriod());
        this.atr = FeatureKey.atr(config.atrPeriod());
        this.atrExpansion = FeatureKey.atrExpansion(config.atrPeriod());
    }

    @Override
    public String name() {
        return "intraday-flat-mean-reversion";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, meanEma, rsi, atr, atrExpansion);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                     PositionView position) {
        FeatureSnapshot current = history.get(index);
        BigDecimal currentClose = current.require(close);
        BigDecimal currentMean = current.require(meanEma);
        BigDecimal currentRsi = current.require(rsi);

        if (position.isOpen()) {
            if (position.barsHeld() >= config.maxHoldingBars()) {
                return new StrategyDecision.Exit("maximum holding period");
            }
            if (position.side() == Side.LONG && currentClose.compareTo(currentMean) >= 0) {
                return new StrategyDecision.Exit("long returned to EMA mean");
            }
            if (position.side() == Side.SHORT && currentClose.compareTo(currentMean) <= 0) {
                return new StrategyDecision.Exit("short returned to EMA mean");
            }
            return StrategyDecision.hold();
        }

        if (index < config.slopeLookbackBars() || current.require(atrExpansion)
                .compareTo(config.maximumAtrExpansionRatio()) > 0) {
            return StrategyDecision.hold();
        }
        BigDecimal pastMean = history.get(index - config.slopeLookbackBars()).require(meanEma);
        BigDecimal slopeBps = currentMean.subtract(pastMean, MC).abs()
                .multiply(BPS, MC).divide(pastMean, MC);
        if (slopeBps.compareTo(config.maximumEmaSlopeBps()) > 0) {
            return StrategyDecision.hold();
        }

        BigDecimal currentAtr = current.require(atr);
        BigDecimal minimumDeviation = currentAtr.multiply(config.minimumDeviationAtr(), MC);
        BigDecimal deviation = currentClose.subtract(currentMean, MC);
        BigDecimal stopDistance = currentAtr.multiply(config.stopAtrMultiplier(), MC);
        BigDecimal targetDistance = deviation.abs().max(
                currentAtr.multiply(config.minimumTargetAtr(), MC));

        if (deviation.compareTo(minimumDeviation.negate()) <= 0
                && currentRsi.compareTo(config.longEntryRsi()) <= 0) {
            return new StrategyDecision.Enter(Side.LONG, stopDistance, targetDistance);
        }
        if (deviation.compareTo(minimumDeviation) >= 0
                && currentRsi.compareTo(config.shortEntryRsi()) >= 0) {
            return new StrategyDecision.Enter(Side.SHORT, stopDistance, targetDistance);
        }
        return StrategyDecision.hold();
    }
}
