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

    @Test
    void makerEntryFillsOnlyWhenOneMinutePriceTradesThroughLimit() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "101", "99", "100"),
                bar(2, "100", "101", "99", "100"));
        Kline fillMinute = minute(15, "100", "100.1", "99.98", "100");

        Trade trade = makerEngine().run(enterOnce(Side.LONG, "10", "100"),
                        bars, List.of(), List.of(fillMinute,
                                minute(16, "100", "100.1", "99.9", "100")))
                .account().closedTrades().getFirst();

        assertEquals(0, trade.entryPrice().compareTo(new BigDecimal("99.99")));
        assertEquals(fillMinute.closeTime(), trade.entryTime());
        assertEquals(0, trade.entrySlippageCost().compareTo(BigDecimal.ZERO));
    }

    @Test
    void makerEntryExpiresWhenPriceOnlyTouchesLimit() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "101", "99", "100"));

        BacktestEngine.BacktestResult result = makerEngine().run(
                enterOnce(Side.LONG, "10", "100"), bars, List.of(),
                List.of(minute(15, "100", "100", "99.99", "100")));

        assertTrue(result.account().closedTrades().isEmpty());
    }

    @Test
    void movesLongStopToCostAdjustedBreakEvenAfterOneRiskUnit() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "111", "99", "105"),
                bar(2, "105", "106", "104", "105"));
        List<Kline> minutes = List.of(
                minute(15, "100", "100.1", "99.98", "100"),
                minute(16, "100", "110", "100", "105"));

        Trade trade = makerEngine(true).run(
                        enterOnce(Side.LONG, "10", "100"), bars, List.of(), minutes)
                .account().closedTrades().getFirst();

        assertEquals("break-even stop", trade.exitReason());
        assertTrue(trade.exitPrice().compareTo(trade.entryPrice()) > 0);
        assertTrue(trade.netPnl().abs().compareTo(new BigDecimal("0.00000001")) < 0);
    }

    @Test
    void adverseTriggerPlacesPersistentMakerScratchAndDoesNotAssumeSameMinuteFill() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "101", "97", "100"));
        List<Kline> minutes = List.of(
                minute(15, "100", "100.1", "99.98", "100"),
                minute(16, "100", "100.2", "97", "99"),
                minute(17, "99", "100.2", "98", "100"));
        Strategy strategy = new Strategy() {
            @Override public String name() { return "scratch-test"; }
            @Override public Set<FeatureKey> requiredFeatures() { return Set.of(FeatureKey.close()); }
            @Override public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                                       PositionView position) {
                return index == 0 ? new StrategyDecision.EnterAtLevelsWithScratch(
                        Side.LONG, new BigDecimal("90"), new BigDecimal("105"),
                        new BigDecimal("98")) : StrategyDecision.hold();
            }
        };

        Trade trade = makerEngine().run(strategy, bars, List.of(), minutes)
                .account().closedTrades().getFirst();

        assertEquals("adverse excursion scratch exit", trade.exitReason());
        assertEquals(minute(17, "99", "100.2", "98", "100").closeTime(), trade.exitTime());
        assertTrue(trade.netPnl().abs().compareTo(new BigDecimal("0.000001")) < 0);
    }

    @Test
    void partiallyClosesAtOneRiskUnitAndKeepsRemainderOpen() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "112", "99", "111"),
                bar(2, "111", "112", "110", "111"));
        List<Kline> minutes = List.of(
                minute(15, "100", "100.1", "99.98", "100"),
                minute(16, "100", "110.1", "100", "110"));
        BacktestEngine.ExitConfig exits = new BacktestEngine.ExitConfig(
                true, BigDecimal.ONE, new BigDecimal("0.5"),
                false, BigDecimal.ONE, BigDecimal.ONE,
                false, 8, new BigDecimal("0.25"));

        List<Trade> trades = makerEngine(false, exits).run(
                        enterOnce(Side.LONG, "10", "100"), bars, List.of(), minutes)
                .account().closedTrades();

        assertEquals(2, trades.size());
        assertEquals("partial take profit", trades.getFirst().exitReason());
        assertEquals(0, trades.getFirst().quantity().compareTo(trades.getLast().quantity()));
        assertEquals("end of data", trades.getLast().exitReason());
    }

    @Test
    void trailsRemainderAfterPriceReachesActivationLevel() {
        List<BacktestEngine.BacktestBar> bars = List.of(
                bar(0, "100", "101", "99", "100"),
                bar(1, "100", "116", "103", "105"));
        List<Kline> minutes = List.of(
                minute(15, "100", "100.1", "99.98", "100"),
                minute(16, "100", "115", "110", "114"),
                minute(17, "106", "107", "104", "105"));
        BacktestEngine.ExitConfig exits = new BacktestEngine.ExitConfig(
                false, BigDecimal.ONE, new BigDecimal("0.5"),
                true, BigDecimal.ONE, BigDecimal.ONE,
                false, 8, new BigDecimal("0.25"));

        Trade trade = makerEngine(false, exits).run(
                        enterOnce(Side.LONG, "10", "100"), bars, List.of(), minutes)
                .account().closedTrades().getFirst();

        assertEquals("stop loss", trade.exitReason());
        assertEquals(0, trade.exitPrice().compareTo(new BigDecimal("104.979")));
    }

    private static BacktestEngine engine(String slippageBps, String feeBps) {
        return new BacktestEngine(new BacktestEngine.BacktestConfig(
                new BigDecimal("10000"), new BigDecimal("0.01"),
                new BigDecimal("100"), new BacktestEngine.ExecutionConfig(
                false, BigDecimal.ZERO, new BigDecimal(feeBps),
                new BigDecimal(slippageBps), BigDecimal.ZERO, 5, true,
                false, BigDecimal.ONE),
                new PropRuleEngine.PropRules(
                new BigDecimal("1000"), new BigDecimal("1000"),
                new BigDecimal("1000"))));
    }

    private static BacktestEngine makerEngine() {
        return makerEngine(false);
    }

    private static BacktestEngine makerEngine(boolean breakEvenEnabled) {
        return makerEngine(breakEvenEnabled, BacktestEngine.ExitConfig.baseline());
    }

    private static BacktestEngine makerEngine(boolean breakEvenEnabled,
                                               BacktestEngine.ExitConfig exits) {
        return new BacktestEngine(new BacktestEngine.BacktestConfig(
                new BigDecimal("10000"), new BigDecimal("0.01"), new BigDecimal("100"),
                new BacktestEngine.ExecutionConfig(true, new BigDecimal("1.8"),
                        new BigDecimal("4.5"), new BigDecimal("2"),
                        BigDecimal.ONE, 5, true, breakEvenEnabled, BigDecimal.ONE),
                new PropRuleEngine.PropRules(new BigDecimal("1000"),
                        new BigDecimal("1000"), new BigDecimal("1000")), exits));
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

    private static Kline minute(int minuteOffset, String open, String high,
                                String low, String close) {
        Instant openTime = START.plus(Duration.ofMinutes(minuteOffset));
        return new Kline(openTime, new BigDecimal(open), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), BigDecimal.ONE,
                openTime.plus(Duration.ofMinutes(1)).minusMillis(1),
                BigDecimal.ZERO, 1, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
