package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.OrderFlowMinute;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderFlowFeatureGeneratorTest {

    private static final Instant START = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void calculatesBuyerSellerImbalanceAndSellAbsorption() {
        List<Kline> klines = klines(242);
        List<OrderFlowMinute> flow = flow(242, "100", "-60");
        FeatureSnapshot snapshot = new OrderFlowFeatureGenerator().generate(klines, flow).getFirst();

        assertEquals(0, snapshot.require(FeatureKey.orderFlowImbalance(15))
                .compareTo(new BigDecimal("-0.6")));
        assertEquals(0, snapshot.require(FeatureKey.orderFlowCoverage(240))
                .compareTo(BigDecimal.ONE));
        assertEquals(0, snapshot.require(FeatureKey.orderFlowQuality(240))
                .compareTo(BigDecimal.ONE));
        assertTrue(snapshot.require(FeatureKey.sellAbsorption(15)).signum() > 0);
    }

    @Test
    void futureOrderFlowCannotChangeEarlierSnapshot() {
        List<Kline> klines = klines(242);
        List<OrderFlowMinute> original = flow(242, "100", "-20");
        List<OrderFlowMinute> modified = new ArrayList<>(original);
        modified.set(241, minute(241, "1000000", "1000000"));
        OrderFlowFeatureGenerator generator = new OrderFlowFeatureGenerator();

        FeatureSnapshot before = generator.generate(klines, original).getFirst();
        FeatureSnapshot after = generator.generate(klines, modified).getFirst();

        assertEquals(before, after);
        assertEquals(klines.get(240).closeTime().plusMillis(1), before.earliestExecutionTime());
    }

    private static List<Kline> klines(int count) {
        List<Kline> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant open = START.plus(Duration.ofMinutes(i));
            BigDecimal price = new BigDecimal("100");
            result.add(new Kline(open, price, price, price, price, BigDecimal.ONE,
                    open.plusSeconds(60).minusMillis(1), price, 1, BigDecimal.ONE, price));
        }
        return result;
    }

    private static List<OrderFlowMinute> flow(int count, String quote, String delta) {
        List<OrderFlowMinute> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(minute(i, quote, delta));
        return result;
    }

    private static OrderFlowMinute minute(int index, String quote, String delta) {
        return new OrderFlowMinute(START.plus(Duration.ofMinutes(index)),
                new BigDecimal(quote), new BigDecimal(delta),
                new BigDecimal("20"), new BigDecimal("80"), true);
    }
}
