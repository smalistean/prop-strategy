package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestConfigurationLoader;
import com.smalistean.propstrategy.backtester.BacktestDataset;
import com.smalistean.propstrategy.backtester.BacktestEngine;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresFundingRateRepository;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.ParameterizedFeatureGenerator;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyRegistry;

import java.nio.file.Path;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BacktestApplication {

    private BacktestApplication() {
    }

    public static void main(String[] args) {
        Path engineFile = Path.of(System.getProperty(
                "engineConfig", "config/backtests/engine.properties"));
        Path strategyFile = Path.of(System.getProperty(
                "strategyConfig", "config/backtests/ema-pullback.properties"));
        BacktestConfigurationLoader.LoadedConfiguration loaded =
                new BacktestConfigurationLoader().load(engineFile, strategyFile);
        StrategyRegistry registry = StrategyRegistry.defaults();
        java.util.function.Supplier<Strategy> strategySupplier = () -> registry.create(
                loaded.strategyType(), loaded.strategyParameters());
        Strategy strategy = strategySupplier.get();

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        ParameterizedFeatureGenerator featureGenerator = new ParameterizedFeatureGenerator();
        int warmupCandles = featureGenerator.requiredWarmupCandles(strategy.requiredFeatures());
        PostgresKlineRepository klineRepository = new PostgresKlineRepository(database);
        List<Kline> candles = klineRepository.findRangeWithWarmup(
                loaded.symbol(), loaded.interval(), loaded.dataset().startInclusive(),
                loaded.dataset().endExclusive(), warmupCandles);
        List<FeatureSnapshot> snapshots = featureGenerator
                .generate(candles, strategy.requiredFeatures()).stream()
                .filter(snapshot -> !snapshot.candleOpenTime()
                        .isBefore(loaded.dataset().startInclusive()))
                .filter(snapshot -> snapshot.candleOpenTime()
                        .isBefore(loaded.dataset().endExclusive()))
                .toList();
        Map<java.time.Instant, Kline> candlesByOpenTime = new HashMap<>();
        candles.forEach(candle -> candlesByOpenTime.put(candle.openTime(), candle));
        List<BacktestEngine.BacktestBar> bars = snapshots.stream()
                .map(snapshot -> new BacktestEngine.BacktestBar(
                        candlesByOpenTime.get(snapshot.candleOpenTime()), snapshot))
                .toList();
        List<FundingRate> funding = new PostgresFundingRateRepository(database)
                .findRange(loaded.symbol(), loaded.dataset().startInclusive(),
                        loaded.dataset().endExclusive());
        List<Kline> minuteCandles = loaded.engine().execution().makerEnabled()
                ? klineRepository.findRangeWithWarmup(loaded.symbol(), "1m",
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 0)
                : List.of();

        BacktestEngine.BacktestResult result = new BacktestEngine(loaded.engine())
                .run(strategy, bars, funding, minuteCandles);
        System.out.printf("Backtest: dataset=%s [%s, %s), strategy=%s, symbol=%s, interval=%s, "
                        + "loadedCandles=%,d, evaluatedBars=%,d%n",
                loaded.dataset().type(), loaded.dataset().startInclusive(),
                loaded.dataset().endExclusive(), strategy.name(), loaded.symbol(),
                loaded.interval(), candles.size(), bars.size());
        PerformanceReport.Report report = new PerformanceReport().generate(result);
        System.out.println(report);
        if (loaded.engine().execution().makerEnabled()) {
            BacktestEngine.ExecutionStats stats = result.executionStats();
            System.out.printf("Maker execution: entries filled=%d/%d, expired=%d; "
                            + "strategy exits filled=%d/%d, taker fallbacks=%d%n",
                    stats.makerEntryFills(), stats.makerEntryOrders(), stats.expiredMakerEntries(),
                    stats.makerExitFills(), stats.makerExitOrders(), stats.takerExitFallbacks());
        }
        result.account().closedTrades().stream().limit(5).forEach(trade ->
                System.out.printf("%s %s -> %s net=%s reason=%s%n",
                        trade.side(), trade.entryTime(), trade.exitTime(),
                        trade.netPnl().stripTrailingZeros().toPlainString(), trade.exitReason()));

        if (Boolean.getBoolean("diagnostics")) {
            BigDecimal makerFeeBps = new BigDecimal(System.getProperty("makerFeeBps", "2"));
            System.out.println(new StrategyDiagnosticReport().generate(
                    result.account().closedTrades(), bars,
                    loaded.engine().execution().takerFeeBps(), makerFeeBps,
                    loaded.engine().execution().makerEnabled()));
        }

        String acceptanceFile = System.getProperty(
                "acceptanceConfig", "config/backtests/acceptance-low-frequency.properties");
        if (loaded.dataset().type() == BacktestDataset.Type.TRAINING
                && !acceptanceFile.isBlank()) {
            evaluateAcceptance(loaded, bars, funding, minuteCandles, report, strategySupplier,
                    Path.of(acceptanceFile));
        }
    }

    private static void evaluateAcceptance(
            BacktestConfigurationLoader.LoadedConfiguration loaded,
            List<BacktestEngine.BacktestBar> bars,
            List<FundingRate> funding,
            List<Kline> minuteCandles,
            PerformanceReport.Report overall,
            java.util.function.Supplier<Strategy> strategySupplier,
            Path acceptanceFile) {
        if (loaded.dataset().type() != BacktestDataset.Type.TRAINING) {
            throw new IllegalStateException("Acceptance evaluation is defined only for TRAINING");
        }
        PerformanceReport reportGenerator = new PerformanceReport();
        List<PerformanceReport.Report> subperiodReports = new java.util.ArrayList<>();
        Instant subperiodStart = loaded.dataset().startInclusive();
        for (int index = 1; index <= 4; index++) {
            Instant periodStart = subperiodStart;
            Instant subperiodEnd = ZonedDateTime.ofInstant(periodStart, ZoneOffset.UTC)
                    .plusMonths(6).toInstant();
            List<BacktestEngine.BacktestBar> subBars = between(bars, periodStart, subperiodEnd);
            List<FundingRate> subFunding = funding.stream()
                    .filter(rate -> !rate.fundingTime().isBefore(periodStart)
                            && rate.fundingTime().isBefore(subperiodEnd))
                    .toList();
            List<Kline> subMinutes = minuteCandles.stream()
                    .filter(candle -> !candle.openTime().isBefore(periodStart)
                            && candle.openTime().isBefore(subperiodEnd))
                    .toList();
            PerformanceReport.Report subperiod = reportGenerator.generate(
                    new BacktestEngine(loaded.engine()).run(
                            strategySupplier.get(), subBars, subFunding, subMinutes));
            subperiodReports.add(subperiod);
            System.out.printf("Training subperiod %d [%s, %s): net=%s, PF=%s, DD=%s%%, trades=%d%n",
                    index, periodStart, subperiodEnd,
                    subperiod.netProfit().stripTrailingZeros().toPlainString(),
                    subperiod.tradeStats().profitFactor().stripTrailingZeros().toPlainString(),
                    subperiod.drawdown().maxDrawdownPct().stripTrailingZeros().toPlainString(),
                    subperiod.tradeStats().totalTrades());
            subperiodStart = subperiodEnd;
        }

        StrategyAcceptanceEvaluator evaluator = new StrategyAcceptanceEvaluator();
        StrategyAcceptanceEvaluator.Criteria criteria = evaluator.load(acceptanceFile);
        BacktestEngine.BacktestConfig base = loaded.engine();
        BigDecimal costMultiplier = criteria.stressCostMultiplier();
        BacktestEngine.ExecutionConfig execution = base.execution();
        BacktestEngine.BacktestConfig stressedConfig = new BacktestEngine.BacktestConfig(
                base.initialBalance(), base.riskFraction(), base.maxLeverage(),
                new BacktestEngine.ExecutionConfig(execution.makerEnabled(),
                        execution.makerFeeBps().multiply(costMultiplier),
                        execution.takerFeeBps().multiply(costMultiplier),
                        execution.takerSlippageBps().multiply(costMultiplier),
                        execution.makerOffsetBps(), execution.makerOrderLifetimeMinutes(),
                        execution.strategyExitTakerFallback()), base.propRules());
        PerformanceReport.Report stressed = reportGenerator.generate(
                new BacktestEngine(stressedConfig).run(
                        strategySupplier.get(), bars, funding, minuteCandles));
        System.out.printf("Cost stress x%s: net=%s, PF=%s, DD=%s%%%n",
                costMultiplier.stripTrailingZeros().toPlainString(),
                stressed.netProfit().stripTrailingZeros().toPlainString(),
                stressed.tradeStats().profitFactor().stripTrailingZeros().toPlainString(),
                stressed.drawdown().maxDrawdownPct().stripTrailingZeros().toPlainString());
        System.out.println(evaluator.evaluate(criteria, overall, subperiodReports, stressed));
    }

    private static List<BacktestEngine.BacktestBar> between(
            List<BacktestEngine.BacktestBar> bars, Instant startInclusive, Instant endExclusive) {
        return bars.stream().filter(bar -> !bar.candle().openTime().isBefore(startInclusive)
                        && bar.candle().openTime().isBefore(endExclusive))
                .toList();
    }
}
