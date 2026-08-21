package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestConfigurationLoader;
import com.smalistean.propstrategy.backtester.BacktestDataset;
import com.smalistean.propstrategy.backtester.BacktestEngine;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresFundingRateRepository;
import com.smalistean.propstrategy.database.PostgresAggregateTradeMinuteRepository;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.database.PostgresOrderFlowFeatureRepository;
import com.smalistean.propstrategy.database.PostgresVolumeProfileBinRepository;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.ParameterizedFeatureGenerator;
import com.smalistean.propstrategy.feature.MultiTimeframeFeatureAssembler;
import com.smalistean.propstrategy.feature.VolumeProfileFeatureAssembler;
import com.smalistean.propstrategy.feature.VolumeProfileFeatureAssemblerV5;
import com.smalistean.propstrategy.feature.HigherTimeframeLiquidityMapAssembler;
import com.smalistean.propstrategy.feature.HigherTimeframeBiasAssembler;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyRegistry;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloBasePocRetestStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloVariableBasePocStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloV4BasePocContinuationStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloV5BasePocContinuationStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloV5LiquidityLimitStrategy;

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
        String marketSymbol = System.getProperty("marketSymbol", loaded.symbol())
                .trim().toUpperCase();

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        ParameterizedFeatureGenerator featureGenerator = new ParameterizedFeatureGenerator();
        PostgresKlineRepository klineRepository = new PostgresKlineRepository(database);
        boolean multiTimeframe = loaded.strategyType().equals("multi-timeframe-flat-long");
        boolean orderFlow = loaded.strategyType().equals("order-flow-exhaustion");
        boolean htfLiquidity = loaded.strategyType().equals("apollo-higher-timeframe-liquidity-sweep")
                || loaded.strategyType().equals("apollo-ordered-liquidity-sequence-v3");
        boolean gerchikLevels = loaded.strategyType().startsWith("gerchik-")
                && !loaded.strategyType().equals("gerchik-level");
        boolean volumeProfile = strategy instanceof VolumeProfileAwareStrategy;
        int warmupCandles = multiTimeframe ? 1 : volumeProfile
                ? strategy.requiredFeatures().stream()
                .filter(key -> !key.name().startsWith("volumeProfile")
                        && !key.name().startsWith("exactBase")
                        && !key.name().startsWith("selectedBase"))
                .mapToInt(key -> key.period() + key.lookback()).max().orElse(0) + 1
                : featureGenerator.requiredWarmupCandles(strategy.requiredFeatures());
        List<Kline> candles = klineRepository.findRangeWithWarmup(marketSymbol, loaded.interval(),
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), warmupCandles);
        List<FeatureSnapshot> generatedSnapshots;
        if (multiTimeframe) {
            if (!loaded.interval().equals("5m")) {
                throw new IllegalArgumentException("Multi-timeframe strategy requires market.interval=5m");
            }
            List<Kline> fifteen = klineRepository.findRangeWithWarmup(marketSymbol, "15m",
                    loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 220);
            List<Kline> hourly = klineRepository.findRangeWithWarmup(marketSymbol, "1h",
                    loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 25);
            generatedSnapshots = new MultiTimeframeFeatureAssembler()
                    .assemble(candles, fifteen, hourly, 200, 14, 14);
        } else if (htfLiquidity) {
            if (!loaded.interval().equals("15m")) throw new IllegalArgumentException("Apollo higher-timeframe liquidity strategy requires 15m");
            java.util.Set<com.smalistean.propstrategy.feature.FeatureKey> technicalKeys = strategy.requiredFeatures().stream()
                    .filter(key -> !key.name().startsWith("higherTimeframe")).collect(java.util.stream.Collectors.toSet());
            List<FeatureSnapshot> technical = featureGenerator.generate(candles, technicalKeys);
            List<Kline> hourly = klineRepository.findRangeWithWarmup(marketSymbol, "1h",
                    loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 260);
            int mapLookbackBars = loaded.strategyType().equals("apollo-ordered-liquidity-sequence-v3")
                    ? loaded.strategyParameters().requiredInt("mapLookbackBars") : 48;
            int mapPivotStrength = loaded.strategyType().equals("apollo-ordered-liquidity-sequence-v3")
                    ? loaded.strategyParameters().requiredInt("mapPivotStrength") : 2;
            int mapMinimumTouches = loaded.strategyType().equals("apollo-ordered-liquidity-sequence-v3")
                    ? loaded.strategyParameters().requiredInt("mapMinimumTouches") : 2;
            BigDecimal mapToleranceAtr = loaded.strategyType().equals("apollo-ordered-liquidity-sequence-v3")
                    ? loaded.strategyParameters().requiredDecimal("mapToleranceAtr")
                    : new BigDecimal("0.30");
            generatedSnapshots = new HigherTimeframeLiquidityMapAssembler().attach(technical, hourly,
                    mapLookbackBars, mapPivotStrength, mapMinimumTouches, mapToleranceAtr);
        } else if (gerchikLevels) {
            if (!loaded.interval().equals("15m")) {
                throw new IllegalArgumentException("Gerchik level strategies require market.interval=15m");
            }
            java.util.Set<com.smalistean.propstrategy.feature.FeatureKey> technicalKeys =
                    strategy.requiredFeatures().stream()
                            .filter(key -> !key.name().startsWith("gerchik"))
                            .collect(java.util.stream.Collectors.toSet());
            List<FeatureSnapshot> technical = featureGenerator.generate(candles, technicalKeys);
            // Levels are drawn on the higher timeframe and persist for months, so the map needs far
            // more warmup than the 15m features do: the course draws hourly levels over 10-15 days
            // and daily levels over a year.
            String levelInterval = System.getProperty("gerchikLevelInterval", "1h");
            List<Kline> levelBars = klineRepository.findRangeWithWarmup(marketSymbol, levelInterval,
                    loaded.dataset().startInclusive(), loaded.dataset().endExclusive(),
                    Integer.getInteger("gerchikLevelWarmupBars", 2000));
            generatedSnapshots = new com.smalistean.propstrategy.feature.gerchik.GerchikLevelMapAssembler()
                    .attach(technical, levelBars,
                            Integer.getInteger("gerchikPivotStrength", 3),
                            new BigDecimal(System.getProperty("gerchikToleranceAtr", "0.05")));
        } else if (orderFlow) {
            if (!loaded.interval().equals("5m")) {
                throw new IllegalArgumentException("Order-flow strategy requires market.interval=5m");
            }
            List<FeatureSnapshot> technical = featureGenerator.generate(candles,
                    java.util.Set.of(com.smalistean.propstrategy.feature.FeatureKey.close(),
                            com.smalistean.propstrategy.feature.FeatureKey.ema(200),
                            com.smalistean.propstrategy.feature.FeatureKey.atr(14)));
            List<FeatureSnapshot> flow = new PostgresOrderFlowFeatureRepository(database)
                    .findFiveMinuteSnapshots(marketSymbol, loaded.dataset().startInclusive(),
                            loaded.dataset().endExclusive());
            generatedSnapshots = merge(technical, flow);
        } else if (volumeProfile) {
            if (!loaded.interval().equals("15m")) {
                throw new IllegalArgumentException("Volume-profile strategy requires market.interval=15m");
            }
            VolumeProfileAwareStrategy typed = (VolumeProfileAwareStrategy) strategy;
            int lookback = typed.profileLookbackBuckets();
            java.util.Set<com.smalistean.propstrategy.feature.FeatureKey> technicalKeys =
                    strategy.requiredFeatures().stream()
                            .filter(key -> !key.name().startsWith("volumeProfile")
                                    && !key.name().startsWith("exactBase")
                                    && !key.name().startsWith("selectedBase")
                                    && !key.name().startsWith("completedHour")
                                    // supplied by HigherTimeframeBiasAssembler, not the 15m generator
                                    && !key.name().startsWith("higherTimeframe")
                                    // supplied by MarketRegimeAssembler from Binance metrics
                                    && !key.name().startsWith("market"))
                            .collect(java.util.stream.Collectors.toSet());
            List<FeatureSnapshot> technical = featureGenerator.generate(candles, technicalKeys);
            if (strategy instanceof ApolloVariableBasePocStrategy variable
                    && variable.requiredFeatures().stream().anyMatch(key -> key.name().equals("completedHourClose"))) {
                List<Kline> hourly = klineRepository.findRangeWithWarmup(marketSymbol, "1h",
                        loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 55);
                technical = new VolumeProfileFeatureAssembler().mergeCompletedHourlyTrend(
                        technical, hourly, 50);
            }
            int bucketMinutes = Integer.getInteger("profileBucketMinutes", 15);
            BigDecimal priceStep = System.getProperty("profilePriceStep") != null
                    ? new BigDecimal(System.getProperty("profilePriceStep"))
                    : com.smalistean.propstrategy.database.VolumeProfilePriceSteps.defaultFor(marketSymbol);
            BigDecimal neighborFraction = new BigDecimal(System.getProperty(
                    "profileNeighborMinimumPocFraction", "0.50"));
            Instant profileStart = loaded.dataset().startInclusive().minusSeconds(
                    (long) lookback * bucketMinutes * 60 * 2);
            var bins = new PostgresVolumeProfileBinRepository(database).findRange(
                    marketSymbol, bucketMinutes, priceStep, profileStart,
                    loaded.dataset().endExclusive());
            VolumeProfileFeatureAssembler assembler = new VolumeProfileFeatureAssembler();
            generatedSnapshots = strategy instanceof ApolloBasePocRetestStrategy apollo
                    ? assembler.mergeExactBase(technical, bins, apollo.baseBars(), neighborFraction)
                    : strategy instanceof ApolloVariableBasePocStrategy variable
                    ? assembler.mergeSelectedBases(technical, bins, variable.atrKey(),
                            variable.detectorConfig(), neighborFraction)
                    : strategy instanceof ApolloV4BasePocContinuationStrategy v4
                    ? assembler.mergePersistentBases(technical, bins, v4.atrKey(),
                            com.smalistean.propstrategy.feature.FeatureKey.volumeRatio(v4.volumePeriod()),
                            v4.detectorConfig(), neighborFraction, v4.breakoutAtr(), v4.reclaimWindowBars())
                    : strategy instanceof ApolloV5BasePocContinuationStrategy v5
                    ? new VolumeProfileFeatureAssemblerV5().mergePersistentBases(technical, bins, v5.atrKey(),
                            com.smalistean.propstrategy.feature.FeatureKey.volumeRatio(v5.volumePeriod()),
                            v5.detectorConfig(), neighborFraction, v5.breakoutAtr(), v5.reclaimWindowBars(),
                            v5.referenceBars(), v5.maximumBoundaryTouches(),
                            com.smalistean.propstrategy.database.VolumeProfilePriceSteps
                                    .pocBinAtrFractionFor(marketSymbol, v5.pocBinAtrFraction()),
                            v5.internalWaveMinimumShare(), v5.consumedBasesRemainTargets(),
                            v5.acceptanceMinimumBodyFraction(), v5.acceptanceMinimumBodyCandles(),
                            v5.profileBodyBoundedSelection())
                    : strategy instanceof ApolloV5LiquidityLimitStrategy vb
                    ? new VolumeProfileFeatureAssemblerV5().mergePersistentBases(technical, bins, vb.atrKey(),
                            com.smalistean.propstrategy.feature.FeatureKey.volumeRatio(vb.volumePeriod()),
                            vb.detectorConfig(), neighborFraction, vb.breakoutAtr(), vb.reclaimWindowBars(),
                            vb.referenceBars(), vb.maximumBoundaryTouches(),
                            com.smalistean.propstrategy.database.VolumeProfilePriceSteps
                                    .pocBinAtrFractionFor(marketSymbol, vb.pocBinAtrFraction()),
                            vb.internalWaveMinimumShare(), vb.consumedBasesRemainTargets(),
                            vb.acceptanceMinimumBodyFraction(), vb.acceptanceMinimumBodyCandles(),
                            vb.profileBodyBoundedSelection())
                    : assembler.merge(technical, bins, lookback, neighborFraction);
            if (strategy instanceof ApolloV5LiquidityLimitStrategy hb && hb.requiresHigherTimeframeAlignment()) {
                List<Kline> hourlyForBias = klineRepository.findRangeWithWarmup(marketSymbol, "1h",
                        loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 240);
                generatedSnapshots = new HigherTimeframeBiasAssembler()
                        .attach(generatedSnapshots, hourlyForBias, hb.higherTimeframePivotStrength());
            }
            // Regime is attached whenever metrics exist for the symbol and window. A strategy that
            // does not ask for the feature simply never reads it, so this stays inert by default.
            int regimeDays = Integer.getInteger("marketRegimeDays", 30);
            if (regimeDays > 0) {
                var metrics = new com.smalistean.propstrategy.database.PostgresMetricSnapshotRepository(database)
                        .findRange(marketSymbol,
                                loaded.dataset().startInclusive().minusSeconds((long) regimeDays * 86400L * 2),
                                loaded.dataset().endExclusive());
                if (!metrics.isEmpty()) {
                    generatedSnapshots = new com.smalistean.propstrategy.feature.MarketRegimeAssembler()
                            .attach(generatedSnapshots, metrics, regimeDays);
                }
            }
        } else {
            generatedSnapshots = featureGenerator.generate(candles, strategy.requiredFeatures());
        }
        List<FeatureSnapshot> snapshots = generatedSnapshots.stream()
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
                .findRange(marketSymbol, loaded.dataset().startInclusive(),
                        loaded.dataset().endExclusive());
        String tradeThroughSource = System.getProperty("makerTradeThroughSource", "klines");
        if (!tradeThroughSource.equals("klines") && !tradeThroughSource.equals("aggTrades")) {
            throw new IllegalArgumentException(
                    "makerTradeThroughSource must be klines or aggTrades");
        }
        List<Kline> minuteCandles = !loaded.engine().execution().makerEnabled() ? List.of()
                : tradeThroughSource.equals("aggTrades")
                ? new PostgresAggregateTradeMinuteRepository(database).findExecutionRange(
                        marketSymbol, loaded.dataset().startInclusive(),
                        loaded.dataset().endExclusive())
                : klineRepository.findRangeWithWarmup(marketSymbol, "1m",
                        loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 0);

        BacktestEngine.BacktestResult result = new BacktestEngine(loaded.engine())
                .run(strategy, bars, funding, minuteCandles);
        System.out.printf("Backtest: dataset=%s [%s, %s), strategy=%s, symbol=%s, interval=%s, "
                        + "loadedCandles=%,d, evaluatedBars=%,d%n",
                loaded.dataset().type(), loaded.dataset().startInclusive(),
                loaded.dataset().endExclusive(), strategy.name(), marketSymbol,
                loaded.interval(), candles.size(), bars.size());
        PerformanceReport.Report report = new PerformanceReport().generate(result);
        System.out.println(report);
        if (strategy instanceof ApolloVariableBasePocStrategy variable) {
            System.out.println(variable.diagnosticSummary());
        }
        if (loaded.engine().execution().makerEnabled()) {
            System.out.printf("Maker trade-through source: %s (%,d minute rows)%n",
                    tradeThroughSource, minuteCandles.size());
            BacktestEngine.ExecutionStats stats = result.executionStats();
            System.out.printf("Maker execution: entries filled=%d/%d, expired=%d; "
                            + "strategy exits filled=%d/%d, taker fallbacks=%d%n",
                    stats.makerEntryFills(), stats.makerEntryOrders(), stats.expiredMakerEntries(),
                    stats.makerExitFills(), stats.makerExitOrders(), stats.takerExitFallbacks());
        }
        // Default 5 keeps existing output; -DtradeListLimit=0 prints every trade, which the
        // bootstrap/walk-forward evidence tooling needs to resample individual trade PnLs.
        int tradeListLimit = Integer.getInteger("tradeListLimit", 5);
        result.account().closedTrades().stream()
                .limit(tradeListLimit == 0 ? Long.MAX_VALUE : tradeListLimit).forEach(trade ->
                // entry/exit prices are printed so favorable-excursion analysis can reconstruct how
                // far a trade ran before it closed, without altering the engine to record it.
                System.out.printf("%s %s -> %s net=%s entry=%s exit=%s qty=%s reason=%s%n",
                        trade.side(), trade.entryTime(), trade.exitTime(),
                        trade.netPnl().stripTrailingZeros().toPlainString(),
                        trade.entryPrice().stripTrailingZeros().toPlainString(),
                        trade.exitPrice().stripTrailingZeros().toPlainString(),
                        trade.quantity().stripTrailingZeros().toPlainString(),
                        trade.exitReason()));

        if (Boolean.getBoolean("diagnostics")) {
            System.out.println(new StrategyDiagnosticReport().generate(
                    result.account().closedTrades(), bars,
                    loaded.engine().execution().takerFeeBps(),
                    loaded.engine().execution().makerFeeBps(),
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

    private static List<FeatureSnapshot> merge(List<FeatureSnapshot> technical,
                                               List<FeatureSnapshot> flow) {
        Map<Instant, FeatureSnapshot> flowByTime = new HashMap<>();
        flow.forEach(item -> flowByTime.put(item.candleOpenTime(), item));
        return technical.stream().filter(item -> flowByTime.containsKey(item.candleOpenTime()))
                .map(item -> {
                    FeatureSnapshot orderFlow = flowByTime.get(item.candleOpenTime());
                    Map<com.smalistean.propstrategy.feature.FeatureKey, BigDecimal> values =
                            new HashMap<>(item.values());
                    values.putAll(orderFlow.values());
                    Instant available = item.availableAt().isAfter(orderFlow.availableAt())
                            ? item.availableAt() : orderFlow.availableAt();
                    Instant executable = item.earliestExecutionTime().isAfter(orderFlow.earliestExecutionTime())
                            ? item.earliestExecutionTime() : orderFlow.earliestExecutionTime();
                    return new FeatureSnapshot(item.candleOpenTime(), available, executable, values);
                }).toList();
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
        // Derived from the dataset window rather than fixed at four, which silently assumed a
        // 24-month training period. A shorter window (a walk-forward fold, or the pre-training
        // history) left the trailing periods with no bars at all, and the engine's "requires at
        // least one bar" guard then failed the whole run after the main report had already been
        // computed. The final period is clamped to the dataset end so it is measured, not dropped.
        for (int index = 1; subperiodStart.isBefore(loaded.dataset().endExclusive()); index++) {
            Instant periodStart = subperiodStart;
            Instant uncapped = ZonedDateTime.ofInstant(periodStart, ZoneOffset.UTC)
                    .plusMonths(6).toInstant();
            Instant subperiodEnd = uncapped.isAfter(loaded.dataset().endExclusive())
                    ? loaded.dataset().endExclusive() : uncapped;
            if (between(bars, periodStart, subperiodEnd).isEmpty()) {
                break;
            }
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
                        execution.strategyExitTakerFallback(), execution.breakEvenEnabled(),
                        execution.breakEvenTriggerRiskMultiple()), base.propRules(), base.exits());
        PerformanceReport.Report stressed = reportGenerator.generate(
                new BacktestEngine(stressedConfig).run(
                        strategySupplier.get(), bars, funding, minuteCandles));
        System.out.printf("Cost stress x%s: net=%s, PF=%s, DD=%s%%%n",
                costMultiplier.stripTrailingZeros().toPlainString(),
                stressed.netProfit().stripTrailingZeros().toPlainString(),
                stressed.tradeStats().profitFactor().stripTrailingZeros().toPlainString(),
                stressed.drawdown().maxDrawdownPct().stripTrailingZeros().toPlainString());
        // The acceptance gate is defined against the full four-subperiod training window. A shorter
        // window (walk-forward fold, or pre-training history) cannot satisfy it, and running it
        // anyway would only produce a meaningless verdict. Skipping is stated explicitly rather
        // than silently, so a skipped gate is never mistaken for a passed one.
        if (subperiodReports.size() == 4) {
            System.out.println(evaluator.evaluate(criteria, overall, subperiodReports, stressed));
        } else {
            System.out.printf("Acceptance evaluation SKIPPED - NOT PASSED, NOT RUN: the gate needs "
                    + "four 6-month subperiods and this window [%s, %s) produced %d.%n",
                    loaded.dataset().startInclusive(), loaded.dataset().endExclusive(),
                    subperiodReports.size());
        }
    }

    private static List<BacktestEngine.BacktestBar> between(
            List<BacktestEngine.BacktestBar> bars, Instant startInclusive, Instant endExclusive) {
        return bars.stream().filter(bar -> !bar.candle().openTime().isBefore(startInclusive)
                        && bar.candle().openTime().isBefore(endExclusive))
                .toList();
    }
}
