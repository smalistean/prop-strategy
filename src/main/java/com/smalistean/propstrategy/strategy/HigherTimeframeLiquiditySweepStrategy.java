package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/** 4h mapped liquidity area; 15m sweep/reclaim/local-break trigger. */
public final class HigherTimeframeLiquiditySweepStrategy implements Strategy {
    public record Config(int atrPeriod, int volumePeriod, int localBreakBars, BigDecimal sweepAtr,
                         BigDecimal minVolumeRatio, BigDecimal stopBufferAtr, BigDecimal minRewardRisk,
                         int maxHoldingBars) { }
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config c; private final FeatureKey atr; private final FeatureKey volume;
    public HigherTimeframeLiquiditySweepStrategy(Config c) { this.c = c; atr = FeatureKey.atr(c.atrPeriod()); volume = FeatureKey.volumeRatio(c.volumePeriod()); }
    @Override public String name() { return "apollo-higher-timeframe-liquidity-sweep"; }
    @Override public Set<FeatureKey> requiredFeatures() { return Set.of(FeatureKey.open(), FeatureKey.close(), FeatureKey.high(), FeatureKey.low(), atr, volume, FeatureKey.higherTimeframeSupport(), FeatureKey.higherTimeframeResistance()); }
    @Override public StrategyDecision evaluate(List<FeatureSnapshot> h, int i, PositionView p) {
        if (p.isOpen()) return p.barsHeld() >= c.maxHoldingBars() ? new StrategyDecision.Exit("maximum holding period") : StrategyDecision.hold();
        if (i < c.localBreakBars() + 2) return StrategyDecision.hold();
        FeatureSnapshot x = h.get(i), s = h.get(i - 1); BigDecimal a = x.require(atr);
        if (a.signum() <= 0 || x.require(volume).compareTo(c.minVolumeRatio()) < 0) return StrategyDecision.hold();
        BigDecimal support = x.require(FeatureKey.higherTimeframeSupport()), resistance = x.require(FeatureKey.higherTimeframeResistance());
        BigDecimal threshold = a.multiply(c.sweepAtr(), MC);
        if (support.signum() > 0 && resistance.signum() > 0 && s.require(FeatureKey.low()).compareTo(support.subtract(threshold, MC)) < 0
                && x.require(FeatureKey.close()).compareTo(support) > 0 && x.require(FeatureKey.close()).compareTo(x.require(FeatureKey.open())) > 0
                && x.require(FeatureKey.close()).compareTo(priorHigh(h, i)) > 0) return entry(Side.LONG, x.require(FeatureKey.close()), s.require(FeatureKey.low()).subtract(a.multiply(c.stopBufferAtr(), MC), MC), resistance);
        if (support.signum() > 0 && resistance.signum() > 0 && s.require(FeatureKey.high()).compareTo(resistance.add(threshold, MC)) > 0
                && x.require(FeatureKey.close()).compareTo(resistance) < 0 && x.require(FeatureKey.close()).compareTo(x.require(FeatureKey.open())) < 0
                && x.require(FeatureKey.close()).compareTo(priorLow(h, i)) < 0) return entry(Side.SHORT, x.require(FeatureKey.close()), s.require(FeatureKey.high()).add(a.multiply(c.stopBufferAtr(), MC), MC), support);
        return StrategyDecision.hold();
    }
    private StrategyDecision entry(Side side, BigDecimal price, BigDecimal stop, BigDecimal target) { BigDecimal risk = side == Side.LONG ? price.subtract(stop, MC) : stop.subtract(price, MC); BigDecimal reward = side == Side.LONG ? target.subtract(price, MC) : price.subtract(target, MC); return risk.signum() > 0 && reward.compareTo(risk.multiply(c.minRewardRisk(), MC)) >= 0 ? new StrategyDecision.EnterAtLevels(side, stop, target) : StrategyDecision.hold(); }
    private BigDecimal priorHigh(List<FeatureSnapshot> h, int i) { BigDecimal r = h.get(i - c.localBreakBars()).require(FeatureKey.high()); for (int j=i-c.localBreakBars()+1;j<i;j++) r=r.max(h.get(j).require(FeatureKey.high())); return r; }
    private BigDecimal priorLow(List<FeatureSnapshot> h, int i) { BigDecimal r = h.get(i - c.localBreakBars()).require(FeatureKey.low()); for (int j=i-c.localBreakBars()+1;j<i;j++) r=r.min(h.get(j).require(FeatureKey.low())); return r; }
}
