package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.Kline;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MultiTimeframeFeatureAssembler {

    public static final FeatureKey CLOSE_15M = new FeatureKey("mtf15Close", 0);
    public static final FeatureKey EMA_15M = new FeatureKey("mtf15Ema", 0);
    public static final FeatureKey RSI_15M = new FeatureKey("mtf15Rsi", 0);
    public static final FeatureKey PREVIOUS_RSI_15M = new FeatureKey("mtf15PreviousRsi", 0);
    public static final FeatureKey ATR_15M = new FeatureKey("mtf15Atr", 0);
    public static final FeatureKey ATR_EXPANSION_15M = new FeatureKey("mtf15AtrExpansion", 0);
    public static final FeatureKey AGE_5M_BARS = new FeatureKey("mtf15Age5mBars", 0);
    public static final FeatureKey MOVE_24H_PERCENT = new FeatureKey("mtf1hMove24hPercent", 0);

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    public List<FeatureSnapshot> assemble(List<Kline> fiveMinuteCandles,
                                          List<Kline> fifteenMinuteCandles,
                                          List<Kline> hourlyCandles,
                                          int emaPeriod, int rsiPeriod, int atrPeriod) {
        ParameterizedFeatureGenerator generator = new ParameterizedFeatureGenerator();
        FeatureKey ema = FeatureKey.ema(emaPeriod);
        FeatureKey rsi = FeatureKey.rsi(rsiPeriod);
        FeatureKey atr = FeatureKey.atr(atrPeriod);
        FeatureKey expansion = FeatureKey.atrExpansion(atrPeriod);
        List<FeatureSnapshot> fifteen = generator.generate(fifteenMinuteCandles,
                Set.of(FeatureKey.close(), ema, rsi, atr, expansion));
        List<FeatureSnapshot> result = new ArrayList<>();
        int fifteenIndex = -1;
        int hourIndex = -1;
        for (Kline five : fiveMinuteCandles) {
            while (fifteenIndex + 1 < fifteen.size()
                    && !fifteen.get(fifteenIndex + 1).availableAt().isAfter(five.closeTime())) {
                fifteenIndex++;
            }
            while (hourIndex + 1 < hourlyCandles.size()
                    && !hourlyCandles.get(hourIndex + 1).closeTime().isAfter(five.closeTime())) {
                hourIndex++;
            }
            if (fifteenIndex < 1 || hourIndex < 24) {
                continue;
            }
            FeatureSnapshot current15 = fifteen.get(fifteenIndex);
            FeatureSnapshot previous15 = fifteen.get(fifteenIndex - 1);
            BigDecimal previousHourClose = hourlyCandles.get(hourIndex - 24).close();
            BigDecimal move = hourlyCandles.get(hourIndex).close().subtract(previousHourClose, MC)
                    .multiply(BigDecimal.valueOf(100), MC).divide(previousHourClose, MC);
            long age = Duration.between(current15.availableAt(), five.closeTime()).toMinutes() / 5;
            result.add(new FeatureSnapshot(five.openTime(), five.closeTime(),
                    five.closeTime().plusMillis(1), Map.of(
                    FeatureKey.close(), five.close(),
                    CLOSE_15M, current15.require(FeatureKey.close()),
                    EMA_15M, current15.require(ema),
                    RSI_15M, current15.require(rsi),
                    PREVIOUS_RSI_15M, previous15.require(rsi),
                    ATR_15M, current15.require(atr),
                    ATR_EXPANSION_15M, current15.require(expansion),
                    AGE_5M_BARS, BigDecimal.valueOf(age),
                    MOVE_24H_PERCENT, move)));
        }
        return List.copyOf(result);
    }
}
