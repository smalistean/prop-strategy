package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.OrderFlowMinute;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OrderFlowFeatureGenerator {

    public static final int MAX_LOOKBACK = 240;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal SELL_ABSORPTION_MOVE = new BigDecimal("0.005");
    private static final BigDecimal STABILIZATION_MOVE = new BigDecimal("0.002");

    public List<FeatureSnapshot> generate(List<Kline> minuteKlines,
                                          List<OrderFlowMinute> orderFlow) {
        if (minuteKlines.size() <= MAX_LOOKBACK) return List.of();
        Map<java.time.Instant, OrderFlowMinute> flowByMinute = new HashMap<>();
        orderFlow.forEach(flow -> flowByMinute.put(flow.minuteTime(), flow));
        int size = minuteKlines.size();
        BigDecimal[] quote = prefix(size), delta = prefix(size), largeBuy = prefix(size),
                largeSell = prefix(size), observed = prefix(size), exact = prefix(size);
        for (int i = 0; i < size; i++) {
            copyPrevious(i, quote, delta, largeBuy, largeSell, observed, exact);
            OrderFlowMinute flow = flowByMinute.get(minuteKlines.get(i).openTime());
            if (flow != null) {
                quote[i + 1] = quote[i + 1].add(flow.quoteNotional(), MC);
                delta[i + 1] = delta[i + 1].add(flow.quoteDelta(), MC);
                largeBuy[i + 1] = largeBuy[i + 1].add(flow.large100kBuyQuote(), MC);
                largeSell[i + 1] = largeSell[i + 1].add(flow.large100kSellQuote(), MC);
                observed[i + 1] = observed[i + 1].add(BigDecimal.ONE);
                if (flow.reconciledExactly()) exact[i + 1] = exact[i + 1].add(BigDecimal.ONE);
            }
        }

        List<FeatureSnapshot> snapshots = new ArrayList<>(size - MAX_LOOKBACK);
        for (int i = MAX_LOOKBACK; i < size; i++) {
            Map<FeatureKey, BigDecimal> values = new HashMap<>();
            for (int period : List.of(5, 15, 60, 240)) {
                values.put(FeatureKey.orderFlowImbalance(period), imbalance(delta, quote, i, period));
                values.put(FeatureKey.rollingQuoteDelta(period), window(delta, i, period));
                values.put(FeatureKey.largeTradeImbalance(period),
                        imbalance(largeBuy, largeSell, i, period, true));
                values.put(FeatureKey.orderFlowCoverage(period),
                        window(observed, i, period).divide(BigDecimal.valueOf(period), MC));
                BigDecimal observedCount = window(observed, i, period);
                values.put(FeatureKey.orderFlowQuality(period), observedCount.signum() == 0
                        ? BigDecimal.ONE : window(exact, i, period).divide(observedCount, MC));
            }
            BigDecimal return5 = priceReturn(minuteKlines, i, 5);
            BigDecimal return15 = priceReturn(minuteKlines, i, 15);
            BigDecimal imbalance5 = values.get(FeatureKey.orderFlowImbalance(5));
            BigDecimal imbalance15 = values.get(FeatureKey.orderFlowImbalance(15));
            BigDecimal imbalance60 = values.get(FeatureKey.orderFlowImbalance(60));
            values.put(FeatureKey.priceReturn(5), return5);
            values.put(FeatureKey.priceReturn(15), return15);
            values.put(FeatureKey.deltaAcceleration(5, 60), imbalance5.subtract(imbalance60, MC));
            values.put(FeatureKey.sellAbsorption(15), sellAbsorption(imbalance15, return15,
                    values.get(FeatureKey.orderFlowCoverage(15))));
            values.put(FeatureKey.sellExhaustion(5, 15), sellExhaustion(
                    imbalance5, imbalance15, return5));
            values.put(FeatureKey.priceFlowDivergence(15), normalizedReturn(return15)
                    .subtract(imbalance15, MC));
            Kline kline = minuteKlines.get(i);
            snapshots.add(new FeatureSnapshot(kline.openTime(), kline.closeTime(),
                    kline.closeTime().plusMillis(1), values));
        }
        return List.copyOf(snapshots);
    }

    private static BigDecimal[] prefix(int size) {
        BigDecimal[] values = new BigDecimal[size + 1];
        java.util.Arrays.fill(values, BigDecimal.ZERO);
        return values;
    }

    private static void copyPrevious(int index, BigDecimal[]... arrays) {
        for (BigDecimal[] array : arrays) array[index + 1] = array[index];
    }

    private static BigDecimal window(BigDecimal[] prefix, int index, int period) {
        return prefix[index + 1].subtract(prefix[index + 1 - period], MC);
    }

    private static BigDecimal imbalance(BigDecimal[] delta, BigDecimal[] quote,
                                        int index, int period) {
        BigDecimal denominator = window(quote, index, period);
        return denominator.signum() == 0 ? BigDecimal.ZERO
                : window(delta, index, period).divide(denominator, MC);
    }

    private static BigDecimal imbalance(BigDecimal[] buy, BigDecimal[] sell,
                                        int index, int period, boolean ignored) {
        BigDecimal buys = window(buy, index, period), sells = window(sell, index, period);
        BigDecimal total = buys.add(sells, MC);
        return total.signum() == 0 ? BigDecimal.ZERO : buys.subtract(sells, MC).divide(total, MC);
    }

    private static BigDecimal priceReturn(List<Kline> klines, int index, int period) {
        BigDecimal previous = klines.get(index - period).close();
        return klines.get(index).close().subtract(previous, MC).divide(previous, MC);
    }

    private static BigDecimal sellAbsorption(BigDecimal imbalance, BigDecimal priceReturn,
                                             BigDecimal coverage) {
        BigDecimal pressure = imbalance.negate().max(BigDecimal.ZERO);
        BigDecimal downMove = priceReturn.negate().max(BigDecimal.ZERO);
        BigDecimal resistance = BigDecimal.ONE
                .subtract(downMove.divide(SELL_ABSORPTION_MOVE, MC), MC)
                .max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return pressure.multiply(resistance, MC).multiply(coverage, MC);
    }

    private static BigDecimal sellExhaustion(BigDecimal fastImbalance,
                                             BigDecimal slowImbalance,
                                             BigDecimal fastReturn) {
        BigDecimal earlierPressure = slowImbalance.negate().max(BigDecimal.ZERO);
        BigDecimal recentPressure = fastImbalance.negate().max(BigDecimal.ZERO);
        BigDecimal decay = earlierPressure.subtract(recentPressure, MC).max(BigDecimal.ZERO);
        BigDecimal stability = BigDecimal.ONE.subtract(
                fastReturn.abs().divide(STABILIZATION_MOVE, MC), MC)
                .max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return decay.multiply(stability, MC);
    }

    private static BigDecimal normalizedReturn(BigDecimal value) {
        return value.divide(SELL_ABSORPTION_MOVE, MC)
                .max(BigDecimal.ONE.negate()).min(BigDecimal.ONE);
    }
}
