package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresAggregateTradeMinuteRepository;
import com.smalistean.propstrategy.database.PostgresFundingRateRepository;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.database.PostgresMetricSnapshotRepository;
import com.smalistean.propstrategy.database.PostgresOrderFlowFeatureRepository;
import com.smalistean.propstrategy.database.PostgresVolumeProfileBinRepository;
import com.smalistean.propstrategy.database.VolumeProfilePriceSteps;
import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.HigherTimeframeBiasAssembler;
import com.smalistean.propstrategy.feature.HigherTimeframeLiquidityMapAssembler;
import com.smalistean.propstrategy.feature.MarketRegimeAssembler;
import com.smalistean.propstrategy.feature.MultiTimeframeFeatureAssembler;
import com.smalistean.propstrategy.feature.ParameterizedFeatureGenerator;
import com.smalistean.propstrategy.feature.VolumeProfileFeatureAssembler;
import com.smalistean.propstrategy.feature.VolumeProfileFeatureAssemblerV5;
import com.smalistean.propstrategy.feature.gerchik.GerchikLevelMapAssembler;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyParameters;
import com.smalistean.propstrategy.strategy.VolumeProfileAwareStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloBasePocRetestStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloV4BasePocContinuationStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloV5BasePocContinuationStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloV5LiquidityLimitStrategy;
import com.smalistean.propstrategy.strategy.apollo.ApolloVariableBasePocStrategy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the bar/funding/minute inputs a backtest needs, for any strategy in the registry.
 *
 * <p>Which features a strategy needs, and therefore which repositories and assemblers have to run,
 * varies enough between families that the wiring is a long conditional rather than a uniform call.
 * It lives here so there is exactly one copy of it: a second copy inside a new runner would drift
 * from this one silently, and a strategy would then be measured differently depending on which
 * entry point ran it - which is precisely the kind of difference a ranking exercise must not have.
 *
 * <p>The window is passed in rather than read from a dataset definition, because the challenge
 * harness needs the full history while a conventional backtest wants only its training slice.
 */
public final class BacktestBarAssembler {

    public record AssembledDataset(List<BacktestEngine.BacktestBar> bars,
                                   List<FundingRate> funding,
                                   List<Kline> minuteCandles,
                                   int loadedCandles) {
    }

    private BacktestBarAssembler() {
    }

    public static AssembledDataset assemble(DatabaseConfig database, String strategyType,
                                            StrategyParameters strategyParameters, String interval,
                                            String marketSymbol, Strategy strategy,
                                            boolean makerEnabled, Instant startInclusive,
                                            Instant endExclusive) {
        ParameterizedFeatureGenerator featureGenerator = new ParameterizedFeatureGenerator();
        PostgresKlineRepository klineRepository = new PostgresKlineRepository(database);
        boolean multiTimeframe = strategyType.equals("multi-timeframe-flat-long");
        boolean orderFlow = strategyType.equals("order-flow-exhaustion");
        boolean htfLiquidity = strategyType.equals("apollo-higher-timeframe-liquidity-sweep")
                || strategyType.equals("apollo-ordered-liquidity-sequence-v3");
        boolean gerchikLevels = strategyType.startsWith("gerchik-")
                && !strategyType.equals("gerchik-level");
        boolean volumeProfile = strategy instanceof VolumeProfileAwareStrategy;
        int warmupCandles = multiTimeframe ? 1 : volumeProfile
                ? strategy.requiredFeatures().stream()
                .filter(key -> !key.name().startsWith("volumeProfile")
                        && !key.name().startsWith("exactBase")
                        && !key.name().startsWith("selectedBase"))
                .mapToInt(key -> key.period() + key.lookback()).max().orElse(0) + 1
                : featureGenerator.requiredWarmupCandles(strategy.requiredFeatures());
        List<Kline> candles = klineRepository.findRangeWithWarmup(marketSymbol, interval,
                startInclusive, endExclusive, warmupCandles);
        List<FeatureSnapshot> generatedSnapshots;
        if (multiTimeframe) {
            if (!interval.equals("5m")) {
                throw new IllegalArgumentException("Multi-timeframe strategy requires market.interval=5m");
            }
            List<Kline> fifteen = klineRepository.findRangeWithWarmup(marketSymbol, "15m",
                    startInclusive, endExclusive, 220);
            List<Kline> hourly = klineRepository.findRangeWithWarmup(marketSymbol, "1h",
                    startInclusive, endExclusive, 25);
            generatedSnapshots = new MultiTimeframeFeatureAssembler()
                    .assemble(candles, fifteen, hourly, 200, 14, 14);
        } else if (htfLiquidity) {
            if (!interval.equals("15m")) {
                throw new IllegalArgumentException("Apollo higher-timeframe liquidity strategy requires 15m");
            }
            Set<FeatureKey> technicalKeys = strategy.requiredFeatures().stream()
                    .filter(key -> !key.name().startsWith("higherTimeframe")).collect(Collectors.toSet());
            List<FeatureSnapshot> technical = featureGenerator.generate(candles, technicalKeys);
            List<Kline> hourly = klineRepository.findRangeWithWarmup(marketSymbol, "1h",
                    startInclusive, endExclusive, 260);
            boolean v3 = strategyType.equals("apollo-ordered-liquidity-sequence-v3");
            int mapLookbackBars = v3 ? strategyParameters.requiredInt("mapLookbackBars") : 48;
            int mapPivotStrength = v3 ? strategyParameters.requiredInt("mapPivotStrength") : 2;
            int mapMinimumTouches = v3 ? strategyParameters.requiredInt("mapMinimumTouches") : 2;
            BigDecimal mapToleranceAtr = v3
                    ? strategyParameters.requiredDecimal("mapToleranceAtr")
                    : new BigDecimal("0.30");
            generatedSnapshots = new HigherTimeframeLiquidityMapAssembler().attach(technical, hourly,
                    mapLookbackBars, mapPivotStrength, mapMinimumTouches, mapToleranceAtr);
        } else if (gerchikLevels) {
            if (!interval.equals("15m")) {
                throw new IllegalArgumentException("Gerchik level strategies require market.interval=15m");
            }
            Set<FeatureKey> technicalKeys = strategy.requiredFeatures().stream()
                    .filter(key -> !key.name().startsWith("gerchik"))
                    .collect(Collectors.toSet());
            List<FeatureSnapshot> technical = featureGenerator.generate(candles, technicalKeys);
            // Levels are drawn on the higher timeframe and persist for months, so the map needs far
            // more warmup than the 15m features do: the course draws hourly levels over 10-15 days
            // and daily levels over a year.
            String levelInterval = System.getProperty("gerchikLevelInterval", "1h");
            List<Kline> levelBars = klineRepository.findRangeWithWarmup(marketSymbol, levelInterval,
                    startInclusive, endExclusive,
                    Integer.getInteger("gerchikLevelWarmupBars", 2000));
            generatedSnapshots = new GerchikLevelMapAssembler()
                    .attach(technical, levelBars,
                            Integer.getInteger("gerchikPivotStrength", 3),
                            new BigDecimal(System.getProperty("gerchikToleranceAtr", "0.05")));
        } else if (orderFlow) {
            if (!interval.equals("5m")) {
                throw new IllegalArgumentException("Order-flow strategy requires market.interval=5m");
            }
            List<FeatureSnapshot> technical = featureGenerator.generate(candles,
                    Set.of(FeatureKey.close(), FeatureKey.ema(200), FeatureKey.atr(14)));
            List<FeatureSnapshot> flow = new PostgresOrderFlowFeatureRepository(database)
                    .findFiveMinuteSnapshots(marketSymbol, startInclusive, endExclusive);
            generatedSnapshots = merge(technical, flow);
        } else if (volumeProfile) {
            generatedSnapshots = volumeProfileSnapshots(database, klineRepository, featureGenerator,
                    strategy, interval, marketSymbol, candles, startInclusive, endExclusive);
        } else {
            generatedSnapshots = featureGenerator.generate(candles, strategy.requiredFeatures());
        }
        List<FeatureSnapshot> snapshots = generatedSnapshots.stream()
                .filter(snapshot -> !snapshot.candleOpenTime().isBefore(startInclusive))
                .filter(snapshot -> snapshot.candleOpenTime().isBefore(endExclusive))
                .toList();
        Map<Instant, Kline> candlesByOpenTime = new HashMap<>();
        candles.forEach(candle -> candlesByOpenTime.put(candle.openTime(), candle));
        List<BacktestEngine.BacktestBar> bars = snapshots.stream()
                .map(snapshot -> new BacktestEngine.BacktestBar(
                        candlesByOpenTime.get(snapshot.candleOpenTime()), snapshot))
                .toList();
        List<FundingRate> funding = new PostgresFundingRateRepository(database)
                .findRange(marketSymbol, startInclusive, endExclusive);
        String tradeThroughSource = System.getProperty("makerTradeThroughSource", "klines");
        if (!tradeThroughSource.equals("klines") && !tradeThroughSource.equals("aggTrades")) {
            throw new IllegalArgumentException("makerTradeThroughSource must be klines or aggTrades");
        }
        List<Kline> minuteCandles = !makerEnabled ? List.of()
                : tradeThroughSource.equals("aggTrades")
                ? new PostgresAggregateTradeMinuteRepository(database).findExecutionRange(
                        marketSymbol, startInclusive, endExclusive)
                : klineRepository.findRangeWithWarmup(marketSymbol, "1m",
                        startInclusive, endExclusive, 0);
        return new AssembledDataset(bars, funding, minuteCandles, candles.size());
    }

    private static List<FeatureSnapshot> volumeProfileSnapshots(
            DatabaseConfig database, PostgresKlineRepository klineRepository,
            ParameterizedFeatureGenerator featureGenerator, Strategy strategy, String interval,
            String marketSymbol, List<Kline> candles, Instant startInclusive, Instant endExclusive) {
        if (!interval.equals("15m")) {
            throw new IllegalArgumentException("Volume-profile strategy requires market.interval=15m");
        }
        VolumeProfileAwareStrategy typed = (VolumeProfileAwareStrategy) strategy;
        int lookback = typed.profileLookbackBuckets();
        Set<FeatureKey> technicalKeys = strategy.requiredFeatures().stream()
                .filter(key -> !key.name().startsWith("volumeProfile")
                        && !key.name().startsWith("exactBase")
                        && !key.name().startsWith("selectedBase")
                        && !key.name().startsWith("completedHour")
                        // supplied by HigherTimeframeBiasAssembler, not the 15m generator
                        && !key.name().startsWith("higherTimeframe")
                        // supplied by MarketRegimeAssembler from Binance metrics
                        && !key.name().startsWith("market"))
                .collect(Collectors.toSet());
        List<FeatureSnapshot> technical = featureGenerator.generate(candles, technicalKeys);
        if (strategy instanceof ApolloVariableBasePocStrategy variable
                && variable.requiredFeatures().stream()
                .anyMatch(key -> key.name().equals("completedHourClose"))) {
            List<Kline> hourly = klineRepository.findRangeWithWarmup(marketSymbol, "1h",
                    startInclusive, endExclusive, 55);
            technical = new VolumeProfileFeatureAssembler()
                    .mergeCompletedHourlyTrend(technical, hourly, 50);
        }
        int bucketMinutes = Integer.getInteger("profileBucketMinutes", 15);
        BigDecimal priceStep = System.getProperty("profilePriceStep") != null
                ? new BigDecimal(System.getProperty("profilePriceStep"))
                : VolumeProfilePriceSteps.defaultFor(marketSymbol);
        BigDecimal neighborFraction = new BigDecimal(System.getProperty(
                "profileNeighborMinimumPocFraction", "0.50"));
        Instant profileStart = startInclusive.minusSeconds(
                (long) lookback * bucketMinutes * 60 * 2);
        var bins = new PostgresVolumeProfileBinRepository(database).findRange(
                marketSymbol, bucketMinutes, priceStep, profileStart, endExclusive);
        VolumeProfileFeatureAssembler assembler = new VolumeProfileFeatureAssembler();
        List<FeatureSnapshot> snapshots = strategy instanceof ApolloBasePocRetestStrategy apollo
                ? assembler.mergeExactBase(technical, bins, apollo.baseBars(), neighborFraction)
                : strategy instanceof ApolloVariableBasePocStrategy variable
                ? assembler.mergeSelectedBases(technical, bins, variable.atrKey(),
                        variable.detectorConfig(), neighborFraction)
                : strategy instanceof ApolloV4BasePocContinuationStrategy v4
                ? assembler.mergePersistentBases(technical, bins, v4.atrKey(),
                        FeatureKey.volumeRatio(v4.volumePeriod()),
                        v4.detectorConfig(), neighborFraction, v4.breakoutAtr(), v4.reclaimWindowBars())
                : strategy instanceof ApolloV5BasePocContinuationStrategy v5
                ? new VolumeProfileFeatureAssemblerV5().mergePersistentBases(technical, bins, v5.atrKey(),
                        FeatureKey.volumeRatio(v5.volumePeriod()),
                        v5.detectorConfig(), neighborFraction, v5.breakoutAtr(), v5.reclaimWindowBars(),
                        v5.referenceBars(), v5.maximumBoundaryTouches(),
                        VolumeProfilePriceSteps.pocBinAtrFractionFor(marketSymbol, v5.pocBinAtrFraction()),
                        v5.internalWaveMinimumShare(), v5.consumedBasesRemainTargets(),
                        v5.acceptanceMinimumBodyFraction(), v5.acceptanceMinimumBodyCandles(),
                        v5.profileBodyBoundedSelection())
                : strategy instanceof ApolloV5LiquidityLimitStrategy vb
                ? new VolumeProfileFeatureAssemblerV5().mergePersistentBases(technical, bins, vb.atrKey(),
                        FeatureKey.volumeRatio(vb.volumePeriod()),
                        vb.detectorConfig(), neighborFraction, vb.breakoutAtr(), vb.reclaimWindowBars(),
                        vb.referenceBars(), vb.maximumBoundaryTouches(),
                        VolumeProfilePriceSteps.pocBinAtrFractionFor(marketSymbol, vb.pocBinAtrFraction()),
                        vb.internalWaveMinimumShare(), vb.consumedBasesRemainTargets(),
                        vb.acceptanceMinimumBodyFraction(), vb.acceptanceMinimumBodyCandles(),
                        vb.profileBodyBoundedSelection())
                : assembler.merge(technical, bins, lookback, neighborFraction);
        if (strategy instanceof ApolloV5LiquidityLimitStrategy hb
                && hb.requiresHigherTimeframeAlignment()) {
            List<Kline> hourlyForBias = klineRepository.findRangeWithWarmup(marketSymbol, "1h",
                    startInclusive, endExclusive, 240);
            snapshots = new HigherTimeframeBiasAssembler()
                    .attach(snapshots, hourlyForBias, hb.higherTimeframePivotStrength());
        }
        // Regime is attached whenever metrics exist for the symbol and window. A strategy that
        // does not ask for the feature simply never reads it, so this stays inert by default.
        int regimeDays = Integer.getInteger("marketRegimeDays", 30);
        if (regimeDays > 0) {
            var metrics = new PostgresMetricSnapshotRepository(database).findRange(marketSymbol,
                    startInclusive.minusSeconds((long) regimeDays * 86400L * 2), endExclusive);
            if (!metrics.isEmpty()) {
                snapshots = new MarketRegimeAssembler().attach(snapshots, metrics, regimeDays);
            }
        }
        return snapshots;
    }

    private static List<FeatureSnapshot> merge(List<FeatureSnapshot> technical,
                                               List<FeatureSnapshot> flow) {
        Map<Instant, FeatureSnapshot> flowByTime = new HashMap<>();
        flow.forEach(item -> flowByTime.put(item.candleOpenTime(), item));
        return technical.stream().filter(item -> flowByTime.containsKey(item.candleOpenTime()))
                .map(item -> {
                    FeatureSnapshot orderFlow = flowByTime.get(item.candleOpenTime());
                    Map<FeatureKey, BigDecimal> values = new HashMap<>(item.values());
                    values.putAll(orderFlow.values());
                    Instant available = item.availableAt().isAfter(orderFlow.availableAt())
                            ? item.availableAt() : orderFlow.availableAt();
                    Instant executable = item.earliestExecutionTime()
                            .isAfter(orderFlow.earliestExecutionTime())
                            ? item.earliestExecutionTime() : orderFlow.earliestExecutionTime();
                    return new FeatureSnapshot(item.candleOpenTime(), available, executable, values);
                }).toList();
    }
}
