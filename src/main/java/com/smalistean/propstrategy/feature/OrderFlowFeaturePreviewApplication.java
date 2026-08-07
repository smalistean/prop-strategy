package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.OrderFlowMinute;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.database.PostgresOrderFlowRepository;

import java.time.Instant;
import java.util.List;

public final class OrderFlowFeaturePreviewApplication {

    private OrderFlowFeaturePreviewApplication() {
    }

    public static void main(String[] args) {
        String symbol = "BTCUSDT";
        Instant start = Instant.parse(System.getProperty("orderFlowPreviewStart", "2025-08-01T00:00:00Z"));
        Instant end = Instant.parse(System.getProperty("orderFlowPreviewEnd", "2025-08-07T00:00:00Z"));
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        List<Kline> klines = new PostgresKlineRepository(database).findRangeWithWarmup(
                symbol, "1m", start, end, OrderFlowFeatureGenerator.MAX_LOOKBACK + 1);
        Instant queryStart = klines.getFirst().openTime();
        List<OrderFlowMinute> flow = new PostgresOrderFlowRepository(database)
                .findRange(symbol, queryStart, end);
        List<FeatureSnapshot> snapshots = new OrderFlowFeatureGenerator().generate(klines, flow)
                .stream().filter(item -> !item.candleOpenTime().isBefore(start)).toList();
        FeatureSnapshot last = snapshots.getLast();
        System.out.printf("Order-flow features: klines=%,d flowMinutes=%,d snapshots=%,d "
                        + "range=[%s,%s] last15mImbalance=%s lastAbsorption=%s "
                        + "lastExhaustion=%s coverage240=%s quality240=%s availableAt=%s%n",
                klines.size(), flow.size(), snapshots.size(), snapshots.getFirst().candleOpenTime(),
                last.candleOpenTime(), last.require(FeatureKey.orderFlowImbalance(15)),
                last.require(FeatureKey.sellAbsorption(15)),
                last.require(FeatureKey.sellExhaustion(5, 15)),
                last.require(FeatureKey.orderFlowCoverage(240)),
                last.require(FeatureKey.orderFlowQuality(240)), last.earliestExecutionTime());
    }
}
