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
