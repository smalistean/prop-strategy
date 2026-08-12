package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * G3 - Gerchik breakout (пробой). The control in the registered comparison.
 *
 * <p>Included <em>because</em> it is predicted to lose: all three breakout examples in
 * {@code GERCHIK_LABELLED_EXAMPLES.md} lost, against three wins for the false breakout. A set where
 * every variant is expected to win cannot falsify anything.
 *
 * <h2>The model</h2>
 * <i>"Самый сложный стиль"</i>. Its preconditions from the конспект:
 * <ul>
 *   <li><i>"подход к уровню на маленьких барах"</i> - the approach is on small bars;</li>
 *   <li><i>"присутствует поджатие"</i> - compression against the level;</li>
 *   <li><i>"долгое накопление (консолидация) - чем ближе к уровню, тем лучше"</i>;</li>
 *   <li><i>"Ещё до пробоя ... на противоположной стороне (за уровнем) на 1-2 пункта выставляется buy
 *       stop"</i> - the entry order rests just beyond the level <em>before</em> the break, which is
 *       why this uses {@code EnterAtStop} rather than reacting to a completed breakout;</li>
 *   <li><i>"защитный стоп стандартного размера, но обязательно за уровнем"</i> - the protective stop
 *       goes back behind the level.</li>
 * </ul>
 *
 * <h2>Declared parameters that are ours, not Gerchik's</h2>
 * The source describes the approach qualitatively ("small bars", "compression") without numbers.
 * {@code compressionBars}, {@code compressionAtrFraction} and {@code approachDistanceAtr} are
 * therefore our operationalisation, declared before running and not swept. They are the weakest part
 * of this implementation and are recorded as such.
 */
public final class GerchikBreakoutStrategy implements Strategy {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    /**
     * @param compressionBars        bars of approach examined for "поджатие"
     * @param compressionAtrFraction mean approach-bar range must be at most this fraction of ATR
     * @param approachDistanceAtr    price must sit within this many ATR of the level
     * @param entryOffsetFraction    the "1-2 пункта" beyond the level where the stop order rests
     */
    public record Config(int atrPeriod, int compressionBars, BigDecimal compressionAtrFraction,
                         BigDecimal approachDistanceAtr, BigDecimal entryOffsetFraction,
                         BigDecimal stopBufferAtr, BigDecimal minimumRewardRisk, int requireMirror,
                         int minimumTouches, int orderLifetimeBars, int maximumHoldingBars) {
    }

    private final Config config;
    private final FeatureKey atr;

    public GerchikBreakoutStrategy(Config config) {
        this.config = config;
        this.atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override
    public String name() {
        return "Gerchik breakout";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(FeatureKey.open(), FeatureKey.high(), FeatureKey.low(), FeatureKey.close(), atr);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index, PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= config.maximumHoldingBars()
                    ? new StrategyDecision.Exit("Gerchik breakout holding period expired")
                    : StrategyDecision.hold();
        }
        FeatureSnapshot bar = history.get(index);
        if (!bar.values().containsKey(atr) || index < config.compressionBars()) {
            return StrategyDecision.hold();
        }
        BigDecimal atrValue = bar.require(atr);
        if (atrValue.signum() <= 0) {
            return StrategyDecision.hold();
        }
        // Поджатие: the approach must be on small bars, not a wide swing into the level.
        BigDecimal totalRange = BigDecimal.ZERO;
        for (int i = index - config.compressionBars() + 1; i <= index; i++) {
            FeatureSnapshot approach = history.get(i);
            totalRange = totalRange.add(
                    approach.require(FeatureKey.high()).subtract(approach.require(FeatureKey.low())));
        }
        BigDecimal meanRange = totalRange.divide(BigDecimal.valueOf(config.compressionBars()), MC);
        if (meanRange.compareTo(atrValue.multiply(config.compressionAtrFraction(), MC)) > 0) {
            return StrategyDecision.hold();
        }

        StrategyDecision up = evaluateSide(bar, atrValue, true);
        if (!(up instanceof StrategyDecision.Hold)) {
            return up;
        }
        return evaluateSide(bar, atrValue, false);
    }

    private StrategyDecision evaluateSide(FeatureSnapshot bar, BigDecimal atrValue, boolean upside) {
        FeatureKey levelKey = upside ? FeatureKey.gerchikLevelAbove() : FeatureKey.gerchikLevelBelow();
        FeatureKey touchesKey = upside
                ? FeatureKey.gerchikLevelAboveTouches() : FeatureKey.gerchikLevelBelowTouches();
        FeatureKey mirrorKey = upside
                ? FeatureKey.gerchikLevelAboveMirror() : FeatureKey.gerchikLevelBelowMirror();
        // Breaking up, the room is measured to the next level above; the map's nearest-above IS the
        // level being broken, so the target must come from beyond it and is unavailable here. The
        // course sizes the breakout target by room to the next opposing structure, so without a
        // further level the trade is rejected rather than given an invented target.
        FeatureKey targetKey = upside ? FeatureKey.gerchikLevelBelow() : FeatureKey.gerchikLevelAbove();
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
        BigDecimal close = bar.require(FeatureKey.close());
        // "Чем ближе к уровню, тем лучше" - the order only rests when price is already against it.
        BigDecimal distance = upside ? level.subtract(close, MC) : close.subtract(level, MC);
        if (distance.signum() < 0
                || distance.compareTo(atrValue.multiply(config.approachDistanceAtr(), MC)) > 0) {
            return StrategyDecision.hold();
        }

        Side side = upside ? Side.LONG : Side.SHORT;
        BigDecimal offset = close.multiply(config.entryOffsetFraction(), MC);
        BigDecimal trigger = upside ? level.add(offset, MC) : level.subtract(offset, MC);
        BigDecimal buffer = atrValue.multiply(config.stopBufferAtr(), MC);
        // "Обязательно за уровнем" - back behind the level, not behind the entry.
        BigDecimal protective = upside ? level.subtract(buffer, MC) : level.add(buffer, MC);
        // Room is measured against the opposing mapped level, mirroring G1's structural target.
        BigDecimal opposing = bar.require(targetKey);
        BigDecimal risk = upside ? trigger.subtract(protective, MC) : protective.subtract(trigger, MC);
        BigDecimal reward = upside
                ? trigger.subtract(opposing, MC).abs()
                : opposing.subtract(trigger, MC).abs();
        BigDecimal target = upside ? trigger.add(reward, MC) : trigger.subtract(reward, MC);
        if (risk.signum() <= 0 || reward.signum() <= 0
                || reward.divide(risk, MC).compareTo(config.minimumRewardRisk()) < 0) {
            return StrategyDecision.hold();
        }
        return new StrategyDecision.EnterAtStop(side, trigger, protective, target,
                config.orderLifetimeBars());
    }
}
