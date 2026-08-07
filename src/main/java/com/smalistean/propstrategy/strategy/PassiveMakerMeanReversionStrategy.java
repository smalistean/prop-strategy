package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/** A frequent passive-entry hypothesis for zero-maker-fee markets. */
public final class PassiveMakerMeanReversionStrategy implements Strategy {

    public record Config(int meanEmaPeriod, int slopeLookbackBars,
                         BigDecimal maximumEmaSlopeBps, int rsiPeriod,
                         BigDecimal longEntryRsi, BigDecimal shortEntryRsi,
                         int atrPeriod, BigDecimal minimumDeviationAtr,
                         BigDecimal stopAtrMultiplier, BigDecimal targetAtrMultiplier,
                         int maximumHoldingBars) {
        public Config {
            if (meanEmaPeriod <= 1 || slopeLookbackBars <= 0
                    || maximumEmaSlopeBps.signum() < 0 || rsiPeriod <= 1
                    || longEntryRsi.signum() < 0 || shortEntryRsi.compareTo(BigDecimal.valueOf(100)) > 0
                    || longEntryRsi.compareTo(shortEntryRsi) >= 0 || atrPeriod <= 1
                    || minimumDeviationAtr.signum() < 0 || stopAtrMultiplier.signum() <= 0
                    || targetAtrMultiplier.signum() <= 0 || maximumHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid passive maker mean-reversion configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey ema;
    private final FeatureKey rsi;
    private final FeatureKey atr;

    public PassiveMakerMeanReversionStrategy(Config config) {
        this.config = config;
        ema = FeatureKey.ema(config.meanEmaPeriod());
        rsi = FeatureKey.rsi(config.rsiPeriod());
        atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override
    public String name() {
        return "passive-maker-mean-reversion";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, ema, rsi, atr);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                     PositionView position) {
        FeatureSnapshot current = history.get(index);
        BigDecimal price = current.require(close);
        BigDecimal mean = current.require(ema);

        if (position.isOpen()) {
            if (position.barsHeld() >= config.maximumHoldingBars()) {
                return new StrategyDecision.Exit("passive quote timed out");
            }
            if (position.side() == Side.LONG && price.compareTo(mean) >= 0) {
                return new StrategyDecision.Exit("long reverted to mean");
            }
            if (position.side() == Side.SHORT && price.compareTo(mean) <= 0) {
                return new StrategyDecision.Exit("short reverted to mean");
            }
            return StrategyDecision.hold();
        }

        if (index < config.slopeLookbackBars()) {
            return StrategyDecision.hold();
        }
        BigDecimal pastMean = history.get(index - config.slopeLookbackBars()).require(ema);
        BigDecimal slopeBps = mean.subtract(pastMean, MC).abs()
                .multiply(BPS, MC).divide(pastMean, MC);
        if (slopeBps.compareTo(config.maximumEmaSlopeBps()) > 0) {
            return StrategyDecision.hold();
        }

        BigDecimal currentAtr = current.require(atr);
        BigDecimal deviation = price.subtract(mean, MC);
        BigDecimal minimumDeviation = currentAtr.multiply(config.minimumDeviationAtr(), MC);
        BigDecimal stop = currentAtr.multiply(config.stopAtrMultiplier(), MC);
        BigDecimal target = currentAtr.multiply(config.targetAtrMultiplier(), MC);
        BigDecimal currentRsi = current.require(rsi);

        if (deviation.compareTo(minimumDeviation.negate()) <= 0
                && currentRsi.compareTo(config.longEntryRsi()) <= 0) {
            return new StrategyDecision.Enter(Side.LONG, stop, target);
        }
        if (deviation.compareTo(minimumDeviation) >= 0
                && currentRsi.compareTo(config.shortEntryRsi()) >= 0) {
            return new StrategyDecision.Enter(Side.SHORT, stop, target);
        }
        return StrategyDecision.hold();
    }
}
