package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktestEngineTest {

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void entersOnNextCandleAndChargesBothFees() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "111", "99", "105"),
                bar(2, "105", "106", "104", "105"));

        BacktestEngine.BacktestResult result = engine("0", "10").run(
                enterOnce(Side.LONG, "5", "10"), bars, List.of());

        Trade trade = result.account().closedTrades().getFirst();
        assertEquals(bars.get(1).candle().openTime(), trade.entryTime());
        assertEquals("take profit", trade.exitReason());
        assertEquals(0, trade.entryFee().compareTo(new BigDecimal("2")));
        assertEquals(0, trade.exitFee().compareTo(new BigDecimal("2.2")));
        assertEquals(0, trade.netPnl().compareTo(new BigDecimal("195.8")));
    }

    @Test
    void assumesStopFirstWhenStopAndTargetBothTradeInsideCandle() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "111", "94", "105"));

        Trade trade = engine("0", "0").run(
                enterOnce(Side.LONG, "5", "10"), bars, List.of())
                .account().closedTrades().getFirst();

        assertEquals("stop loss", trade.exitReason());
        assertEquals(0, trade.exitPrice().compareTo(new BigDecimal("95")));
        assertTrue(trade.netPnl().signum() < 0);
    }

    @Test
    void appliesPositiveFundingAsCostToLongPosition() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "101", "99", "100"),
                bar(2, "100", "101", "99", "100"));
        FundingRate funding = new FundingRate("BTCUSDT",
                bars.get(1).candle().openTime().plusSeconds(60), "Regular",
                new BigDecimal("0.001"), new BigDecimal("100"));

        Trade trade = engine("0", "0").run(
                enterOnce(Side.LONG, "10", "100"), bars, List.of(funding))
                .account().closedTrades().getFirst();

        assertEquals(0, trade.fundingPnl().compareTo(new BigDecimal("-1")));
        assertEquals(0, trade.netPnl().compareTo(new BigDecimal("-1")));
    }

    @Test
    void executesShortEntryAndTargetSymmetrically() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "101", "89", "95"));

        Trade trade = engine("0", "0").run(
                enterOnce(Side.SHORT, "5", "10"), bars, List.of())
                .account().closedTrades().getFirst();

        assertEquals(Side.SHORT, trade.side());
        assertEquals("take profit", trade.exitReason());
        assertEquals(0, trade.exitPrice().compareTo(new BigDecimal("90")));
        assertTrue(trade.netPnl().signum() > 0);
    }

    private static BacktestEngine engine(String slippageBps, String feeBps) {
        return new BacktestEngine(new BacktestEngine.BacktestConfig(
                new BigDecimal("10000"), new BigDecimal("0.01"),
                new BigDecimal("100"), new BigDecimal(slippageBps),
                new BigDecimal(feeBps), new PropRuleEngine.PropRules(
                new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("1000"))));
    }

    private static Strategy enterOnce(Side side, String stop, String target) {
        return new Strategy() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public Set<FeatureKey> requiredFeatures() {
                return Set.of(FeatureKey.close());
            }

            @Override
            public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                             PositionView position) {
                return index == 0
                        ? new StrategyDecision.Enter(side, new BigDecimal(stop),
                        new BigDecimal(target))
                        : StrategyDecision.hold();
            }
        };
    }

    private static BacktestEngine.BacktestBar bar(int index, String open, String high,
                                                  String low, String close) {
        Instant openTime = START.plus(Duration.ofMinutes(15L * index));
        Instant closeTime = openTime.plus(Duration.ofMinutes(15)).minusMillis(1);
        Kline candle = new Kline(openTime, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), BigDecimal.ONE,
                closeTime, BigDecimal.ZERO, 1, BigDecimal.ZERO, BigDecimal.ZERO);
        FeatureSnapshot features = new FeatureSnapshot(openTime, closeTime,
                closeTime.plusMillis(1), Map.of(FeatureKey.close(), new BigDecimal(close)));
        return new BacktestEngine.BacktestBar(candle, features);
    }
}
