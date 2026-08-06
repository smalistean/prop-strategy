package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestEngine;
import com.smalistean.propstrategy.backtester.Trade;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.strategy.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyDiagnosticReportTest {

    @Test
    void reportsRawCostsGroupingAndMakerCounterfactual() {
        Instant entry = Instant.parse("2026-01-01T00:00:00Z");
        Instant exit = Instant.parse("2026-01-01T01:00:00Z");
        Trade trade = new Trade(entry, exit, decimal("100"), decimal("101"), BigDecimal.ONE,
                Side.LONG, decimal("0.96"), decimal("0.05"), decimal("0.0505"),
                decimal("0.01"), decimal("0.02"), decimal("0.02"),
                decimal("0.8695"), "take profit");
        Kline candle = new Kline(entry, decimal("100"), decimal("102"), decimal("99"),
                decimal("101"), BigDecimal.ONE, exit, BigDecimal.ZERO, 1,
                BigDecimal.ZERO, BigDecimal.ZERO);
        BacktestEngine.BacktestBar bar = new BacktestEngine.BacktestBar(candle,
                new FeatureSnapshot(entry, exit, exit.plusMillis(1), Map.of()));

        String report = new StrategyDiagnosticReport().generate(
                List.of(trade), List.of(bar), decimal("5"), decimal("2"), false);

        assertTrue(report.contains("Price PnL before costs : 1"));
        assertTrue(report.contains("LONG | trades=1"));
        assertTrue(report.contains("take profit | trades=1"));
        assertTrue(report.contains("Maker-filled scenario"));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
