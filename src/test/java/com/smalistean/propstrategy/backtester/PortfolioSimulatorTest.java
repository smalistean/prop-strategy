package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.strategy.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PortfolioSimulatorTest {

    @Test
    void sharesCapitalAndRejectsOverlappingTradeAtPositionCap() {
        PortfolioSimulator simulator = new PortfolioSimulator(new PortfolioSimulator.Config(
                new BigDecimal("100000"), 1, new BigDecimal("3"), new BigDecimal("3")));
        PortfolioSimulator.Result result = simulator.run(List.of(
                candidate("BTCUSDT", 0, 20, "1000"),
                candidate("XRPUSDT", 10, 30, "2000"),
                candidate("ADAUSDT", 21, 40, "1000")));

        assertEquals(2, result.acceptedTrades());
        assertEquals(1, result.positionCapRejections());
        assertEquals(0, result.finalBalance().compareTo(new BigDecimal("102010.00")));
    }

    @Test
    void appliesCorrelatedNotionalCapBeforeTotalLeverageCap() {
        PortfolioSimulator simulator = new PortfolioSimulator(new PortfolioSimulator.Config(
                new BigDecimal("100000"), 3, new BigDecimal("3"), new BigDecimal("1")));
        PortfolioSimulator.Result result = simulator.run(List.of(
                candidate("BTCUSDT", 0, 20, "1000"),
                candidate("XRPUSDT", 10, 30, "1000")));

        assertEquals(1, result.acceptedTrades());
        assertEquals(1, result.correlationCapRejections());
    }

    private static PortfolioSimulator.CandidateTrade candidate(
            String symbol, long entrySeconds, long exitSeconds, String pnl) {
        Trade trade = new Trade(Instant.EPOCH.plusSeconds(entrySeconds),
                Instant.EPOCH.plusSeconds(exitSeconds), new BigDecimal("100"),
                new BigDecimal("101"), new BigDecimal("600"), Side.LONG,
                new BigDecimal(pnl), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(pnl), "test");
        return new PortfolioSimulator.CandidateTrade(symbol, trade, new BigDecimal("100000"));
    }
}
