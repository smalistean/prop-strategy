package com.smalistean.propstrategy.strategy;

import java.math.BigDecimal;

public sealed interface StrategyDecision {

    record Enter(Side side, BigDecimal stopDistance, BigDecimal targetDistance)
            implements StrategyDecision {
        public Enter {
            if (side == null || stopDistance == null || stopDistance.signum() <= 0
                    || targetDistance == null || targetDistance.signum() <= 0) {
                throw new IllegalArgumentException("Entry requires side and positive stop/target distances");
            }
        }
    }

    record EnterAtLevels(Side side, BigDecimal stopPrice, BigDecimal targetPrice)
            implements StrategyDecision {
        public EnterAtLevels {
            if (side == null || stopPrice == null || stopPrice.signum() <= 0
                    || targetPrice == null || targetPrice.signum() <= 0
                    || stopPrice.compareTo(targetPrice) == 0) {
                throw new IllegalArgumentException(
                        "Structural entry requires side and distinct positive stop/target prices");
            }
        }
    }

    record EnterAtLevelsWithScratch(Side side, BigDecimal stopPrice, BigDecimal targetPrice,
                                    BigDecimal scratchTriggerPrice) implements StrategyDecision {
        public EnterAtLevelsWithScratch {
            if (side == null || stopPrice == null || targetPrice == null || scratchTriggerPrice == null
                    || stopPrice.signum() <= 0 || targetPrice.signum() <= 0
                    || scratchTriggerPrice.signum() <= 0 || stopPrice.compareTo(targetPrice) == 0) {
                throw new IllegalArgumentException("Scratch entry requires positive structural prices");
            }
        }
    }

    /**
     * A limit order resting at a chosen price until filled or expired.
     *
     * <p>Every other entry decision here is executed at the next bar, so the strategy can only ever
     * take the price the market happens to offer. The course's Family B is the opposite: <i>"place a
     * limit order slightly before the principal volume"</i> (pp. 24, 26) - rest an order in front of
     * the POC and let price come to it. That is why the paired stop, <i>"behind the entire liquidity
     * zone"</i> plus a quarter of the zone height, is small: the entry sits just outside the zone.
     *
     * <p>{@code lifetimeBars} is counted in strategy bars, not the minute-scale
     * {@code makerOrderLifetimeMinutes} used for at-market maker entries, because waiting for a
     * pullback is the point of the order rather than a fill optimisation.
     */
    record EnterAtLimit(Side side, BigDecimal entryPrice, BigDecimal stopPrice,
                        BigDecimal targetPrice, int lifetimeBars) implements StrategyDecision {
        public EnterAtLimit {
            if (side == null || entryPrice == null || stopPrice == null || targetPrice == null
                    || entryPrice.signum() <= 0 || stopPrice.signum() <= 0 || targetPrice.signum() <= 0
                    || lifetimeBars <= 0) {
                throw new IllegalArgumentException(
                        "Limit entry requires side, positive entry/stop/target prices and a positive lifetime");
            }
            if (side == Side.LONG && (stopPrice.compareTo(entryPrice) >= 0
                    || targetPrice.compareTo(entryPrice) <= 0)
                    || side == Side.SHORT && (stopPrice.compareTo(entryPrice) <= 0
                    || targetPrice.compareTo(entryPrice) >= 0)) {
                throw new IllegalArgumentException("Limit entry stop/target must straddle the entry price");
            }
        }
    }

    /**
     * A stop order resting until price trades through {@code triggerPrice} in the entry direction.
     *
     * <p>Distinct from {@link EnterAtLimit}: a limit rests on the far side of the market and fills
     * as price comes to it, whereas a stop rests in the direction of travel and fills as price runs
     * through it. Gerchik uses both, and which one applies is part of the model rather than an
     * execution detail - the false-breakout entry is <i>"в противоположной плоскости выставляем
     * стоп-ордер"</i>, a stop order placed in the opposite plane as the failed breakout bar closes,
     * while the bounce entry is a limit at the level plus люфт.
     */
    record EnterAtStop(Side side, BigDecimal triggerPrice, BigDecimal stopPrice,
                       BigDecimal targetPrice, int lifetimeBars) implements StrategyDecision {
        public EnterAtStop {
            if (side == null || triggerPrice == null || stopPrice == null || targetPrice == null
                    || triggerPrice.signum() <= 0 || stopPrice.signum() <= 0
                    || targetPrice.signum() <= 0 || lifetimeBars <= 0) {
                throw new IllegalArgumentException(
                        "Stop entry requires side, positive trigger/stop/target prices and a positive lifetime");
            }
            if (side == Side.LONG && (stopPrice.compareTo(triggerPrice) >= 0
                    || targetPrice.compareTo(triggerPrice) <= 0)
                    || side == Side.SHORT && (stopPrice.compareTo(triggerPrice) <= 0
                    || targetPrice.compareTo(triggerPrice) >= 0)) {
                throw new IllegalArgumentException("Stop entry stop/target must straddle the trigger price");
            }
        }
    }

    record Exit(String reason) implements StrategyDecision {
        public Exit {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Exit reason is required");
            }
        }
    }

    record Hold() implements StrategyDecision {
    }

    static Hold hold() {
        return new Hold();
    }
}
