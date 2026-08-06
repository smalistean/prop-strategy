package com.smalistean.propstrategy.statistics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyAcceptanceEvaluatorTest {

    @Test
    void passesOnlyWhenOverallSubperiodAndStressCriteriaAllPass() {
        StrategyAcceptanceEvaluator.Criteria criteria = new StrategyAcceptanceEvaluator.Criteria(
                decimal("0.01"), decimal("1.10"), decimal("10"), 60, 3,
                decimal("60"), decimal("1.20"), decimal("1.5"), decimal("0.01"));
        PerformanceReport.Report overall = report("1000", "2", "5", 100);
        List<PerformanceReport.Report> subperiods = List.of(
                report("250", "1.2", "5", 25),
                report("250", "1.2", "5", 25),
                report("250", "1.2", "5", 25),
                report("250", "1.2", "5", 25));

        StrategyAcceptanceEvaluator.Evaluation evaluation =
                new StrategyAcceptanceEvaluator().evaluate(
                        criteria, overall, subperiods, report("100", "1.2", "8", 90));

        assertTrue(evaluation.passed());
        assertTrue(evaluation.checks().stream().allMatch(StrategyAcceptanceEvaluator.Check::passed));
    }

    private static PerformanceReport.Report report(
            String netProfit, String profitFactor, String drawdown, int trades) {
        BigDecimal initial = decimal("100000");
        BigDecimal net = decimal(netProfit);
        TradeStatistics.Stats stats = new TradeStatistics.Stats(
                trades, trades / 2, trades / 2, decimal("50"), net,
                net.divide(BigDecimal.valueOf(trades), MathContext.DECIMAL64), decimal("120"), decimal("60"),
                decimal(profitFactor), decimal("100"), BigDecimal.ZERO, decimal("50"));
        return new PerformanceReport.Report(
                initial, initial.add(net), net, net.divide(initial).multiply(decimal("100")),
                new DrawdownCalculator.DrawdownStats(decimal("500"), decimal(drawdown)),
                stats, Optional.empty());
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
