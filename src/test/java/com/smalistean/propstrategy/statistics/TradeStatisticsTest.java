package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.Trade;
import com.smalistean.propstrategy.strategy.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TradeStatisticsTest {

    @Test
    void calculatesNetPerformanceAndCosts() {
        TradeStatistics.Stats stats = new TradeStatistics().calculate(List.of(
                trade("100", "2", "3", "1", "4", "5"),
                trade("-50", "2", "2", "-2", "1", "1")));

        assertEquals(2, stats.totalTrades());
        assertEquals(1, stats.winningTrades());
        assertEquals(1, stats.losingTrades());
        assertEquals(0, stats.winRate().compareTo(new BigDecimal("50")));
        assertEquals(0, stats.totalPnl().compareTo(new BigDecimal("50")));
        assertEquals(0, stats.averagePnl().compareTo(new BigDecimal("25")));
        assertEquals(0, stats.averageWin().compareTo(new BigDecimal("100")));
        assertEquals(0, stats.averageLoss().compareTo(new BigDecimal("50")));
        assertEquals(0, stats.profitFactor().compareTo(new BigDecimal("2")));
        assertEquals(0, stats.totalFees().compareTo(new BigDecimal("9")));
        assertEquals(0, stats.totalFunding().compareTo(new BigDecimal("-1")));
        assertEquals(0, stats.totalSlippageCost().compareTo(new BigDecimal("11")));
    }

    private static Trade trade(String netPnl, String entryFee, String exitFee,
                               String funding, String entrySlippage, String exitSlippage) {
        return new Trade(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T01:00:00Z"),
                BigDecimal.valueOf(100), BigDecimal.valueOf(101), BigDecimal.ONE,
                Side.LONG, new BigDecimal(netPnl), new BigDecimal(entryFee),
                new BigDecimal(exitFee), new BigDecimal(funding),
                new BigDecimal(entrySlippage), new BigDecimal(exitSlippage),
                new BigDecimal(netPnl), "test");
    }
}
