package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * G1 - Gerchik false breakout (ложный пробой одним баром).
 *
 * <p>Written from the course text and the labelled examples, independently of the earlier
 * {@code gerchik-level} implementation. Rationale and declared constants:
 * {@code GERCHIK_V2_PREREGISTRATION.md}.
 *
 * <h2>The model</h2>
 * <i>"ЛП одним баром - это несостоявшийся пробой"</i>. A mapped level is broken, price fails to hold
 * beyond it, and returns. The course's conditions:
 * <ul>
 *   <li>the level must actually be broken;</li>
 *   <li><i>"предпочтительно, чтобы глубина пробоя умещалась в 1/3 ATR"</i> - the excursion beyond the
 *       level should fit inside a third of ATR;</li>
 *   <li><i>"за 30 секунд до закрытия пробойного бара, в противоположной плоскости выставляем
 *       стоп-ордер"</i> - a stop order in the opposite plane as the failed bar closes;</li>
 *   <li><i>"Как правило, ТС ставят за хвостом бара ЛП"</i> - the protective stop goes behind the
 *       false-breakout wick, not at a calculated percentage.</li>
 * </ul>
 *
 * <p>Direction is contrarian to the break: a failed break <em>above</em> resistance is a short.
 * The course notes such trades are best taken against the prevailing move, since the false breakout
 * is where the opposing side's stops were collected.
 *
 * <h2>Targets</h2>
 * The target is the <em>nearest opposing mapped level</em>, per the PCG example's <i>"первый выход
 * возле ближайшего уровня"</i>, and the trade is rejected when that leaves less than
 * {@code minimumRewardRisk}. This is deliberately not a fixed R multiple: in the labelled examples
 * the R multiple is an outcome of where the next level sat, not an input.
 *
 * <h2>What this cannot reproduce</h2>
 * The labelled examples show discretionary exits worth 30-60% of nominal risk, plus exits keyed to
 * "anomalous volume". This holds its stop and target mechanically and therefore forgoes a component
 * that measurably carries money in the source's own examples.
 */
public final class GerchikFalseBreakoutStrategy implements Strategy {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    /**
     * @param maximumBreakDepthAtr excursion beyond the level, in ATR - the course's 1/3
     * @param entryOffsetFraction  fraction of price placing the entry stop off the level (люфт)
     * @param stopBufferAtr        buffer beyond the false-breakout wick
     * @param minimumRewardRisk    reject below this ratio to the nearest opposing level
     * @param requireMirror        1 = only trade зеркальные уровни, the dominant type in the examples
     * @param minimumTouches       minimum БПУ confirmations on the level
     * @param orderLifetimeBars    bars the resting stop order survives before cancellation
     */
    public record Config(int atrPeriod, BigDecimal maximumBreakDepthAtr, BigDecimal entryOffsetFraction,
                         BigDecimal stopBufferAtr, BigDecimal minimumRewardRisk, int requireMirror,
                         int minimumTouches, int orderLifetimeBars, int maximumHoldingBars) {
    }

    private final Config config;
    private final FeatureKey atr;

    public GerchikFalseBreakoutStrategy(Config config) {
        this.config = config;
        this.atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override
    public String name() {
        return "Gerchik false breakout";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(FeatureKey.open(), FeatureKey.high(), FeatureKey.low(), FeatureKey.close(), atr);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index, PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= config.maximumHoldingBars()
                    ? new StrategyDecision.Exit("Gerchik false breakout holding period expired")
                    : StrategyDecision.hold();
        }
        FeatureSnapshot bar = history.get(index);
        if (!bar.values().containsKey(atr)) {
            return StrategyDecision.hold();
        }
        BigDecimal atrValue = bar.require(atr);
        if (atrValue.signum() <= 0) {
            return StrategyDecision.hold();
        }
        BigDecimal maximumDepth = atrValue.multiply(config.maximumBreakDepthAtr(), MC);
        BigDecimal buffer = atrValue.multiply(config.stopBufferAtr(), MC);
        BigDecimal close = bar.require(FeatureKey.close());

        // Failed break ABOVE a resistance level -> short back through it.
        StrategyDecision shortSide = evaluateSide(bar, close, maximumDepth, buffer, true);
        if (!(shortSide instanceof StrategyDecision.Hold)) {
            return shortSide;
        }
        // Failed break BELOW a support level -> long back through it.
        return evaluateSide(bar, close, maximumDepth, buffer, false);
    }

    private StrategyDecision evaluateSide(FeatureSnapshot bar, BigDecimal close,
                                          BigDecimal maximumDepth, BigDecimal buffer,
                                          boolean brokenAbove) {
        FeatureKey levelKey = brokenAbove ? FeatureKey.gerchikLevelAbove() : FeatureKey.gerchikLevelBelow();
        FeatureKey touchesKey = brokenAbove
                ? FeatureKey.gerchikLevelAboveTouches() : FeatureKey.gerchikLevelBelowTouches();
        FeatureKey mirrorKey = brokenAbove
                ? FeatureKey.gerchikLevelAboveMirror() : FeatureKey.gerchikLevelBelowMirror();
        FeatureKey targetKey = brokenAbove ? FeatureKey.gerchikLevelBelow() : FeatureKey.gerchikLevelAbove();
        if (!bar.values().containsKey(levelKey) || !bar.values().containsKey(targetKey)) {
            return StrategyDecision.hold();
        }
        BigDecimal level = bar.require(levelKey);
        if (bar.require(touchesKey).intValue() < config.minimumTouches()) {
            return StrategyDecision.hold();
        }
        if (config.requireMirror() != 0 && bar.require(mirrorKey).signum() == 0) {
            return StrategyDecision.hold();
        }

        // The break must have happened and failed within this bar: the wick crossed the level, the
        // close did not hold beyond it. This is the "одним баром" case the source describes.
        BigDecimal excursion = brokenAbove
                ? bar.require(FeatureKey.high()).subtract(level, MC)
                : level.subtract(bar.require(FeatureKey.low()), MC);
        if (excursion.signum() <= 0 || excursion.compareTo(maximumDepth) > 0) {
            return StrategyDecision.hold();
        }
        boolean heldBeyond = brokenAbove ? close.compareTo(level) > 0 : close.compareTo(level) < 0;
        if (heldBeyond) {
            return StrategyDecision.hold(); // acceptance, not a false breakout
        }

        Side side = brokenAbove ? Side.SHORT : Side.LONG;
        BigDecimal offset = close.multiply(config.entryOffsetFraction(), MC);
        // Stop order in the opposite plane, a little beyond the level.
        BigDecimal trigger = brokenAbove ? level.subtract(offset, MC) : level.add(offset, MC);
        // Protective stop behind the false-breakout wick.
        BigDecimal protective = brokenAbove
                ? bar.require(FeatureKey.high()).add(buffer, MC)
                : bar.require(FeatureKey.low()).subtract(buffer, MC);
        BigDecimal target = bar.require(targetKey);

        BigDecimal risk = brokenAbove ? protective.subtract(trigger, MC) : trigger.subtract(protective, MC);
        BigDecimal reward = brokenAbove ? trigger.subtract(target, MC) : target.subtract(trigger, MC);
        if (risk.signum() <= 0 || reward.signum() <= 0
                || reward.divide(risk, MC).compareTo(config.minimumRewardRisk()) < 0) {
            return StrategyDecision.hold();
        }
        return new StrategyDecision.EnterAtStop(side, trigger, protective, target,
                config.orderLifetimeBars());
    }
}
