package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * G2 - Gerchik bounce off a level (отбой), the course's core БСУ/БПУ model.
 *
 * <p>Written from the course text; declared constants in {@code GERCHIK_V2_PREREGISTRATION.md}.
 *
 * <h2>The model</h2>
 * <ul>
 *   <li><b>БСУ</b> forms the level - supplied by {@code GerchikLevelMapAssembler} from the higher
 *       timeframe.</li>
 *   <li><b>БПУ-1</b> <i>"должен бить в БСУ копейка в копейку"</i> - a working-timeframe bar reaching
 *       the level.</li>
 *   <li><b>БПУ-2</b> <i>"может не добивать на люфт, но пробивать цену не может"</i> - may fall short
 *       by люфт but must not break the level.</li>
 *   <li><i>"БПУ-1 и БПУ-2 должны идти друг за другом"</i> and must sit on the same side of the
 *       level. See {@code maximumBpuGapBars} for the ambiguity in "друг за другом".</li>
 *   <li><i>"За 30 секунд до закрытия БПУ-2 ... с учётом люфта, выставляется лимитный ордер"</i> -
 *       a limit at {@code level -/+ люфт}, which is why this needs {@code EnterAtLimit}.</li>
 * </ul>
 *
 * <p>Direction is a rejection of the level: a level above price is resistance and gives a short.
 *
 * <h2>The adjacency ambiguity, carried openly</h2>
 * The конспект's <i>"друг за другом"</i> reads as strict bar adjacency, but the Block 4 practice
 * video annotates БПУ1 and БПУ2 with several bars between them. The two readings differ in setup
 * frequency by an order of magnitude, so {@code maximumBpuGapBars} exposes both: 1 is the strict
 * reading, larger values the loose one. Neither is tuned - both are reported.
 */
public final class GerchikBounceStrategy implements Strategy {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    /**
     * @param entryOffsetFraction люфт as a fraction of price; also the БПУ touch tolerance, since
     *                            the source defines "не добивает" in units of люфт
     * @param maximumBpuGapBars   1 = БПУ-1 and БПУ-2 strictly adjacent; larger = the loose reading
     * @param stopBufferAtr       buffer beyond the level for the protective stop
     * @param requireMirror       1 = only зеркальные уровни
     */
    public record Config(int atrPeriod, BigDecimal entryOffsetFraction, int maximumBpuGapBars,
                         BigDecimal stopBufferAtr, BigDecimal minimumRewardRisk, int requireMirror,
                         int minimumTouches, int orderLifetimeBars, int maximumHoldingBars) {
    }

    private final Config config;
    private final FeatureKey atr;

    public GerchikBounceStrategy(Config config) {
        this.config = config;
        this.atr = FeatureKey.atr(config.atrPeriod());
    }

    @Override
    public String name() {
        return "Gerchik bounce";
    }

    @Override
    public Set<FeatureKey> requiredFeatures() {
        return Set.of(FeatureKey.open(), FeatureKey.high(), FeatureKey.low(), FeatureKey.close(), atr);
    }

    @Override
    public StrategyDecision evaluate(List<FeatureSnapshot> history, int index, PositionView position) {
        if (position.isOpen()) {
            return position.barsHeld() >= config.maximumHoldingBars()
                    ? new StrategyDecision.Exit("Gerchik bounce holding period expired")
                    : StrategyDecision.hold();
        }
        FeatureSnapshot bar = history.get(index);
        if (!bar.values().containsKey(atr) || index < config.maximumBpuGapBars() + 2) {
            return StrategyDecision.hold();
        }
        BigDecimal atrValue = bar.require(atr);
        if (atrValue.signum() <= 0) {
            return StrategyDecision.hold();
        }
        StrategyDecision shortSide = evaluateSide(history, index, bar, atrValue, true);
        if (!(shortSide instanceof StrategyDecision.Hold)) {
            return shortSide;
        }
        return evaluateSide(history, index, bar, atrValue, false);
    }

    private StrategyDecision evaluateSide(List<FeatureSnapshot> history, int index,
                                          FeatureSnapshot bar, BigDecimal atrValue, boolean resistance) {
        FeatureKey levelKey = resistance ? FeatureKey.gerchikLevelAbove() : FeatureKey.gerchikLevelBelow();
        FeatureKey touchesKey = resistance
                ? FeatureKey.gerchikLevelAboveTouches() : FeatureKey.gerchikLevelBelowTouches();
        FeatureKey mirrorKey = resistance
                ? FeatureKey.gerchikLevelAboveMirror() : FeatureKey.gerchikLevelBelowMirror();
        FeatureKey targetKey = resistance ? FeatureKey.gerchikLevelBelow() : FeatureKey.gerchikLevelAbove();
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
        BigDecimal luft = close.multiply(config.entryOffsetFraction(), MC);

        // БПУ-2 is the bar just completed: it must reach into the люфт band without breaking the
        // level. "Может не добивать на люфт, но пробивать цену не может."
        if (!isBpu(bar, level, luft, resistance)) {
            return StrategyDecision.hold();
        }
        // БПУ-1 must precede it, on the same side, within the declared gap.
        boolean foundBpu1 = false;
        for (int gap = 1; gap <= config.maximumBpuGapBars() && index - gap >= 0; gap++) {
            if (isBpu(history.get(index - gap), level, luft, resistance)) {
                foundBpu1 = true;
                break;
            }
        }
        if (!foundBpu1) {
            return StrategyDecision.hold();
        }

        Side side = resistance ? Side.SHORT : Side.LONG;
        // "При шорте для определения ТВХ от уровня отнимается люфт" - the limit sits a люфт short of
        // the level, so the fill happens as price runs into it rather than at the level itself.
        BigDecimal entry = resistance ? level.subtract(luft, MC) : level.add(luft, MC);
        BigDecimal buffer = atrValue.multiply(config.stopBufferAtr(), MC);
        BigDecimal protective = resistance ? level.add(buffer, MC) : level.subtract(buffer, MC);
        BigDecimal target = bar.require(targetKey);

        BigDecimal risk = resistance ? protective.subtract(entry, MC) : entry.subtract(protective, MC);
        BigDecimal reward = resistance ? entry.subtract(target, MC) : target.subtract(entry, MC);
        if (risk.signum() <= 0 || reward.signum() <= 0
                || reward.divide(risk, MC).compareTo(config.minimumRewardRisk()) < 0) {
            return StrategyDecision.hold();
        }
        return new StrategyDecision.EnterAtLimit(side, entry, protective, target,
                config.orderLifetimeBars());
    }

    /** A bar reaching the level's люфт band from the given side without breaking through it. */
    private static boolean isBpu(FeatureSnapshot bar, BigDecimal level, BigDecimal luft, boolean resistance) {
        if (resistance) {
            BigDecimal high = bar.require(FeatureKey.high());
            return high.compareTo(level.subtract(luft)) >= 0 && high.compareTo(level) <= 0;
        }
        BigDecimal low = bar.require(FeatureKey.low());
        return low.compareTo(level.add(luft)) <= 0 && low.compareTo(level) >= 0;
    }
}
