package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.MultiTimeframeFeatureAssembler;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

public final class MultiTimeframeFlatLongStrategy implements Strategy {

    public record Config(BigDecimal maximumRegimeMovePercent,
                         BigDecimal entryRsi, BigDecimal exitRsi,
                         BigDecimal maximumAtrExpansionRatio,
                         BigDecimal stopAtrMultiplier, BigDecimal rewardRiskRatio,
                         int setupLifetime5mBars, int maxHolding5mBars) {
        public Config {
            if (maximumRegimeMovePercent.signum() <= 0 || entryRsi.signum() < 0
                    || entryRsi.compareTo(exitRsi) >= 0
                    || maximumAtrExpansionRatio.signum() <= 0
                    || stopAtrMultiplier.signum() <= 0 || rewardRiskRatio.signum() <= 0
                    || setupLifetime5mBars <= 0 || maxHolding5mBars <= 0) {
                throw new IllegalArgumentException("Invalid multi-timeframe strategy configuration");
            }
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;

    public MultiTimeframeFlatLongStrategy(Config config) {
        this.config = config;
    }

    @Override
    public String name() {
        return "multi-timeframe-flat-long";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(FeatureKey.close(), MultiTimeframeFeatureAssembler.CLOSE_15M,
                MultiTimeframeFeatureAssembler.EMA_15M, MultiTimeframeFeatureAssembler.RSI_15M,
                MultiTimeframeFeatureAssembler.PREVIOUS_RSI_15M,
                MultiTimeframeFeatureAssembler.ATR_15M,
                MultiTimeframeFeatureAssembler.ATR_EXPANSION_15M,
                MultiTimeframeFeatureAssembler.AGE_5M_BARS,
                MultiTimeframeFeatureAssembler.MOVE_24H_PERCENT);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                     PositionView position) {
        FeatureSnapshot current = history.get(index);
        if (position.isOpen()) {
            if (position.barsHeld() >= config.maxHolding5mBars()) {
                return new StrategyDecision.Exit("maximum holding period");
            }
            if (current.require(MultiTimeframeFeatureAssembler.RSI_15M)
                    .compareTo(config.exitRsi()) >= 0
                    || current.require(MultiTimeframeFeatureAssembler.CLOSE_15M)
                    .compareTo(current.require(MultiTimeframeFeatureAssembler.EMA_15M)) < 0) {
                return new StrategyDecision.Exit("15m mean reversion or trend failure");
            }
            return StrategyDecision.hold();
        }
        if (index == 0
                || current.require(MultiTimeframeFeatureAssembler.MOVE_24H_PERCENT).abs()
                .compareTo(config.maximumRegimeMovePercent()) > 0
                || current.require(MultiTimeframeFeatureAssembler.CLOSE_15M)
                .compareTo(current.require(MultiTimeframeFeatureAssembler.EMA_15M)) <= 0
                || current.require(MultiTimeframeFeatureAssembler.PREVIOUS_RSI_15M)
                .compareTo(config.entryRsi()) <= 0
                || current.require(MultiTimeframeFeatureAssembler.RSI_15M)
                .compareTo(config.entryRsi()) > 0
                || current.require(MultiTimeframeFeatureAssembler.ATR_EXPANSION_15M)
                .compareTo(config.maximumAtrExpansionRatio()) > 0
                || current.require(MultiTimeframeFeatureAssembler.AGE_5M_BARS)
                .compareTo(BigDecimal.valueOf(config.setupLifetime5mBars() - 1L)) > 0
                || current.require(FeatureKey.close())
                .compareTo(history.get(index - 1).require(FeatureKey.close())) <= 0) {
            return StrategyDecision.hold();
        }
        BigDecimal stop = current.require(MultiTimeframeFeatureAssembler.ATR_15M)
                .multiply(config.stopAtrMultiplier(), MC);
        return new StrategyDecision.Enter(Side.LONG, stop,
                stop.multiply(config.rewardRiskRatio(), MC));
    }
}
