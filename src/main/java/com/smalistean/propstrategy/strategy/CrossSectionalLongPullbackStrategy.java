package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/** Frozen v1: buy a confirmed 15m pullback only in a leading asset and healthy BTC regime. */
public final class CrossSectionalLongPullbackStrategy implements Strategy {

    public record Config(int topRank, int fastEma, int slowEma, int rsiPeriod, int atrPeriod,
                         int volumePeriod, BigDecimal minimumRsi, BigDecimal maximumRsi,
                         BigDecimal minimumVolumeRatio, BigDecimal stopAtrMultiplier,
                         BigDecimal rewardRiskRatio, int maxHoldingBars) {
        public Config {
            if (topRank <= 0 || fastEma <= 1 || slowEma <= fastEma || rsiPeriod <= 1
                    || atrPeriod <= 1 || volumePeriod <= 1 || minimumRsi.signum() < 0
                    || maximumRsi.compareTo(BigDecimal.valueOf(100)) > 0
                    || minimumRsi.compareTo(maximumRsi) > 0 || minimumVolumeRatio.signum() <= 0
                    || stopAtrMultiplier.signum() <= 0 || rewardRiskRatio.signum() <= 0
                    || maxHoldingBars <= 0) {
                throw new IllegalArgumentException("Invalid cross-sectional pullback configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;
    private final FeatureKey close = FeatureKey.close();
    private final FeatureKey fast;
    private final FeatureKey slow;
    private final FeatureKey rsi;
    private final FeatureKey atr;
    private final FeatureKey volumeRatio;

    public CrossSectionalLongPullbackStrategy(Config config) {
        this.config = config;
        fast = FeatureKey.ema(config.fastEma());
        slow = FeatureKey.ema(config.slowEma());
        rsi = FeatureKey.rsi(config.rsiPeriod());
        atr = FeatureKey.atr(config.atrPeriod());
        volumeRatio = FeatureKey.volumeRatio(config.volumePeriod());
    }

    @Override public String name() { return "cross-sectional-long-pullback"; }

    @Override public Set<FeatureKey> requiredFeatures() {
        return Set.of(close, fast, slow, rsi, atr, volumeRatio,
                FeatureKey.crossSectionRank(), FeatureKey.btcMarketHealthy());
    }

    @Override public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                               PositionView position) {
        FeatureSnapshot current = history.get(index);
        if (position.isOpen()) {
            if (position.barsHeld() >= config.maxHoldingBars()
                    || current.require(close).compareTo(current.require(fast)) < 0
                    || current.require(FeatureKey.crossSectionRank())
                    .compareTo(BigDecimal.valueOf(config.topRank())) > 0
                    || current.require(FeatureKey.btcMarketHealthy()).signum() == 0) {
                return new StrategyDecision.Exit("rank, BTC regime, 15m trend, or holding-period exit");
            }
            return StrategyDecision.hold();
        }
        if (index == 0 || current.require(FeatureKey.crossSectionRank())
                .compareTo(BigDecimal.valueOf(config.topRank())) > 0
                || current.require(FeatureKey.btcMarketHealthy()).signum() == 0
                || current.require(fast).compareTo(current.require(slow)) <= 0
                || history.get(index - 1).require(close).compareTo(history.get(index - 1).require(fast)) > 0
                || current.require(close).compareTo(current.require(fast)) <= 0
                || current.require(rsi).compareTo(config.minimumRsi()) < 0
                || current.require(rsi).compareTo(config.maximumRsi()) > 0
                || current.require(volumeRatio).compareTo(config.minimumVolumeRatio()) < 0) {
            return StrategyDecision.hold();
        }
        BigDecimal stop = current.require(atr).multiply(config.stopAtrMultiplier(), MC);
        return new StrategyDecision.Enter(Side.LONG, stop,
                stop.multiply(config.rewardRiskRatio(), MC));
    }
}
