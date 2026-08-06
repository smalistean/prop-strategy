package com.smalistean.propstrategy.statistics;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class StrategyAcceptanceEvaluator {

    public record Criteria(
            BigDecimal minimumNetProfit,
            BigDecimal minimumProfitFactor,
            BigDecimal maximumDrawdownPercent,
            int minimumTrades,
            int minimumProfitableSubperiods,
            BigDecimal maximumSinglePositiveSubperiodContributionPercent,
            BigDecimal minimumAverageWinLossRatio,
            BigDecimal stressCostMultiplier,
            BigDecimal minimumStressedNetProfit
    ) {
    }

    public record Check(String name, boolean passed, String actual, String required) {
    }

    public record Evaluation(boolean passed, List<Check> checks) {
        public Evaluation {
            checks = List.copyOf(checks);
        }

        @Override
        public String toString() {
            StringBuilder result = new StringBuilder("=== Strategy Acceptance: ")
                    .append(passed ? "PASS" : "FAIL").append(" ===\n");
            for (Check check : checks) {
                result.append(check.passed() ? "PASS" : "FAIL")
                        .append(" | ").append(check.name())
                        .append(" | actual=").append(check.actual())
                        .append(" | required=").append(check.required()).append('\n');
            }
            return result.toString();
        }
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    public Criteria load(Path path) {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read acceptance criteria " + path, e);
        }
        return new Criteria(
                decimal(properties, "acceptance.minimumNetProfit"),
                decimal(properties, "acceptance.minimumProfitFactor"),
                decimal(properties, "acceptance.maximumDrawdownPercent"),
                integer(properties, "acceptance.minimumTrades"),
                integer(properties, "acceptance.minimumProfitableSubperiods"),
                decimal(properties, "acceptance.maximumSinglePositiveSubperiodContributionPercent"),
                decimal(properties, "acceptance.minimumAverageWinLossRatio"),
                decimal(properties, "acceptance.stressCostMultiplier"),
                decimal(properties, "acceptance.minimumStressedNetProfit"));
    }

    public Evaluation evaluate(Criteria criteria,
                               PerformanceReport.Report overall,
                               List<PerformanceReport.Report> subperiods,
                               PerformanceReport.Report stressed) {
        if (subperiods.size() != 4) {
            throw new IllegalArgumentException("Acceptance requires four training subperiods");
        }
        List<Check> checks = new ArrayList<>();
        add(checks, "net profit", overall.netProfit(), criteria.minimumNetProfit(), true);
        add(checks, "profit factor", overall.tradeStats().profitFactor(),
                criteria.minimumProfitFactor(), true);
        add(checks, "maximum drawdown percent", overall.drawdown().maxDrawdownPct(),
                criteria.maximumDrawdownPercent(), false);
        checks.add(new Check("trade count",
                overall.tradeStats().totalTrades() >= criteria.minimumTrades(),
                Integer.toString(overall.tradeStats().totalTrades()),
                ">=" + criteria.minimumTrades()));

        int profitableSubperiods = (int) subperiods.stream()
                .filter(report -> report.netProfit().signum() > 0).count();
        checks.add(new Check("profitable six-month subperiods",
                profitableSubperiods >= criteria.minimumProfitableSubperiods(),
                Integer.toString(profitableSubperiods),
                ">=" + criteria.minimumProfitableSubperiods()));

        BigDecimal positiveProfit = subperiods.stream().map(PerformanceReport.Report::netProfit)
                .filter(value -> value.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal largestPositive = subperiods.stream().map(PerformanceReport.Report::netProfit)
                .filter(value -> value.signum() > 0).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal contribution = positiveProfit.signum() == 0 ? BigDecimal.valueOf(100)
                : largestPositive.divide(positiveProfit, MC).multiply(BigDecimal.valueOf(100), MC);
        add(checks, "largest positive subperiod contribution percent", contribution,
                criteria.maximumSinglePositiveSubperiodContributionPercent(), false);

        BigDecimal winLossRatio = overall.tradeStats().averageLoss().signum() == 0
                ? overall.tradeStats().averageWin()
                : overall.tradeStats().averageWin()
                .divide(overall.tradeStats().averageLoss(), MC);
        add(checks, "average win/loss ratio", winLossRatio,
                criteria.minimumAverageWinLossRatio(), true);
        add(checks, "stressed-cost net profit", stressed.netProfit(),
                criteria.minimumStressedNetProfit(), true);
        return new Evaluation(checks.stream().allMatch(Check::passed), checks);
    }

    private static void add(List<Check> checks, String name, BigDecimal actual,
                            BigDecimal threshold, boolean minimum) {
        boolean passed = minimum
                ? actual.compareTo(threshold) >= 0
                : actual.compareTo(threshold) <= 0;
        checks.add(new Check(name, passed, actual.stripTrailingZeros().toPlainString(),
                (minimum ? ">=" : "<=") + threshold.stripTrailingZeros().toPlainString()));
    }

    private static BigDecimal decimal(Properties properties, String name) {
        return new BigDecimal(required(properties, name));
    }

    private static int integer(Properties properties, String name) {
        return Integer.parseInt(required(properties, name));
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing acceptance property: " + name);
        }
        return value.trim();
    }
}
