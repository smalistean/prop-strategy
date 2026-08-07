package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

public final class MarketRegimeClassifier {

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final int lookbackBars;
    private final BigDecimal directionalMovePercent;

    public MarketRegimeClassifier(int lookbackBars, BigDecimal directionalMovePercent) {
        if (lookbackBars <= 0 || directionalMovePercent.signum() <= 0) {
            throw new IllegalArgumentException("Invalid market-regime configuration");
        }
        this.lookbackBars = lookbackBars;
        this.directionalMovePercent = directionalMovePercent;
    }

    public MarketRegime classify(List<FeatureSnapshot> history, int index) {
        if (index < lookbackBars) {
            throw new IllegalArgumentException("Insufficient history for market regime");
        }
        BigDecimal current = history.get(index).require(FeatureKey.close());
        BigDecimal previous = history.get(index - lookbackBars).require(FeatureKey.close());
        BigDecimal movePercent = current.subtract(previous, MC)
                .multiply(HUNDRED, MC).divide(previous, MC);
        if (movePercent.compareTo(directionalMovePercent) > 0) {
            return MarketRegime.BULL;
        }
        if (movePercent.compareTo(directionalMovePercent.negate()) < 0) {
            return MarketRegime.BEAR;
        }
        return MarketRegime.FLAT;
    }
}
