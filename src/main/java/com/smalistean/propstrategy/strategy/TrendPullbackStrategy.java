package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.feature.IndicatorCalculator;

import java.math.BigDecimal;
import java.util.List;

public class TrendPullbackStrategy implements Strategy {

    private final int fastPeriod;
    private final int slowPeriod;
    private final int rsiPeriod;
    private final BigDecimal rsiOversold;
    private final BigDecimal rsiOverbought;

    public TrendPullbackStrategy() {
        this(20, 50, 14, BigDecimal.valueOf(40), BigDecimal.valueOf(60));
    }

    public TrendPullbackStrategy(int fastPeriod, int slowPeriod, int rsiPeriod,
                                 BigDecimal rsiOversold, BigDecimal rsiOverbought) {
        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.rsiPeriod = rsiPeriod;
        this.rsiOversold = rsiOversold;
        this.rsiOverbought = rsiOverbought;
    }

    @Override
    public String name() {
        return "TrendPullback";
    }

    @Override
    public Signal evaluate(List<Kline> history, int index) {
        int minBars = Math.max(slowPeriod, rsiPeriod + 1);
        if (index < minBars) {
            return Signal.HOLD;
        }

        BigDecimal fastEma = IndicatorCalculator.ema(history, index, fastPeriod);
        BigDecimal slowEma = IndicatorCalculator.ema(history, index, slowPeriod);
        BigDecimal rsi = IndicatorCalculator.rsi(history, index, rsiPeriod);
        BigDecimal close = history.get(index).close();

        boolean uptrend = fastEma.compareTo(slowEma) > 0;
        boolean downtrend = fastEma.compareTo(slowEma) < 0;

        if (uptrend && close.compareTo(fastEma) <= 0 && rsi.compareTo(rsiOversold) <= 0) {
            return Signal.BUY;
        }
        if (downtrend && close.compareTo(fastEma) >= 0 && rsi.compareTo(rsiOverbought) >= 0) {
            return Signal.SELL;
        }
        return Signal.HOLD;
    }
}
