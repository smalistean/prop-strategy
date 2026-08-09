package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestConfigurationLoader;
import com.smalistean.propstrategy.backtester.BacktestEngine;
import com.smalistean.propstrategy.backtester.PortfolioSimulator;
import com.smalistean.propstrategy.backtester.Trade;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresFundingRateRepository;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.ParameterizedFeatureGenerator;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyRegistry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

/**
 * Runs the frozen Stage-3 cross-sectional hypothesis. Ranking uses completed 1h candles only;
 * 15m entries are independently simulated and then replayed against one capped portfolio.
 */
public final class CrossSectionalResearchApplication {
    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final List<String> UNIVERSE = List.of("BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT",
            "BNBUSDT", "ADAUSDT", "DOGEUSDT", "LINKUSDT");
    private static final Set<FeatureKey> HOUR_FEATURES = Set.of(FeatureKey.close(), FeatureKey.ema(50));
    private static final Set<FeatureKey> FIFTEEN_FEATURES = Set.of(FeatureKey.close(), FeatureKey.ema(20),
            FeatureKey.ema(50), FeatureKey.rsi(14), FeatureKey.atr(14), FeatureKey.volumeRatio(20));

    private record HourState(BigDecimal return24h, boolean btcHealthy) { }
    private record SymbolResult(String symbol, List<Trade> trades, PerformanceReport.Report report) { }

    private CrossSectionalResearchApplication() { }

    public static void main(String[] args) {
        Path engineFile = Path.of(System.getProperty("engineConfig", "config/backtests/engine-cross-sectional-v1.properties"));
        Path strategyFile = Path.of(System.getProperty("strategyConfig", "config/backtests/cross-sectional-long-pullback-v1.properties"));
        var loaded = new BacktestConfigurationLoader().load(engineFile, strategyFile);
        if (loaded.dataset().type() != com.smalistean.propstrategy.backtester.BacktestDataset.Type.TRAINING
                || !loaded.interval().equals("15m") || !loaded.strategyType().equals("cross-sectional-long-pullback")) {
            throw new IllegalArgumentException("Cross-sectional v1 is frozen for 15m training only");
        }
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        PostgresKlineRepository klines = new PostgresKlineRepository(database);
        Map<String, NavigableMap<Instant, HourState>> hourly = hourlyStates(klines, loaded);
        Map<String, Map<Instant, Integer>> ranks = ranks(hourly);
        if (Boolean.getBoolean("stressOnly")) {
            printStress(loaded, database, klines, hourly, ranks);
            return;
        }

        List<SymbolResult> results = new ArrayList<>();
        for (String symbol : UNIVERSE) {
            SymbolResult result = run(symbol, loaded, database, klines, hourly.get(symbol), hourly.get("BTCUSDT"),
                    ranks.get(symbol), loaded.engine());
            results.add(result);
            print(result);
        }
        printPooled(results);
        printPortfolio(results);
        printStress(loaded, database, klines, hourly, ranks);
    }

    private static Map<String, NavigableMap<Instant, HourState>> hourlyStates(
            PostgresKlineRepository klines, BacktestConfigurationLoader.LoadedConfiguration loaded) {
        Map<String, NavigableMap<Instant, HourState>> states = new HashMap<>();
        ParameterizedFeatureGenerator generator = new ParameterizedFeatureGenerator();
        for (String symbol : UNIVERSE) {
            List<Kline> candles = klines.findRangeWithWarmup(symbol, "1h", loaded.dataset().startInclusive(),
                    loaded.dataset().endExclusive(), 60);
            Map<Instant, FeatureSnapshot> features = generator.generate(candles, HOUR_FEATURES).stream()
                    .collect(java.util.stream.Collectors.toMap(FeatureSnapshot::candleOpenTime, item -> item));
            NavigableMap<Instant, HourState> values = new TreeMap<>();
            for (int index = 24; index < candles.size(); index++) {
                Kline candle = candles.get(index);
                FeatureSnapshot feature = features.get(candle.openTime());
                if (feature == null) continue;
                BigDecimal return24h = candle.close().subtract(candles.get(index - 24).close(), MC)
                        .multiply(BigDecimal.valueOf(100), MC).divide(candles.get(index - 24).close(), MC);
                boolean healthy = candle.close().compareTo(feature.require(FeatureKey.ema(50))) > 0
                        && return24h.abs().compareTo(BigDecimal.TEN) <= 0;
                values.put(candle.closeTime(), new HourState(return24h, healthy));
            }
            states.put(symbol, values);
        }
        return states;
    }

    private static Map<String, Map<Instant, Integer>> ranks(Map<String, NavigableMap<Instant, HourState>> hourly) {
        Map<String, Map<Instant, Integer>> result = new HashMap<>();
        UNIVERSE.forEach(symbol -> result.put(symbol, new HashMap<>()));
        for (Instant time : hourly.get("BTCUSDT").keySet()) {
            List<String> eligible = UNIVERSE.stream().filter(symbol -> hourly.get(symbol).containsKey(time))
                    .sorted(Comparator.comparing((String symbol) -> hourly.get(symbol).get(time).return24h()).reversed())
                    .toList();
            if (eligible.size() != UNIVERSE.size()) continue;
            for (int index = 0; index < eligible.size(); index++) result.get(eligible.get(index)).put(time, index + 1);
        }
        return result;
    }

    private static SymbolResult run(String symbol, BacktestConfigurationLoader.LoadedConfiguration loaded,
                                    DatabaseConfig database, PostgresKlineRepository klines,
                                    NavigableMap<Instant, HourState> hourly,
                                    NavigableMap<Instant, HourState> btcHourly,
                                    Map<Instant, Integer> ranks,
                                    BacktestEngine.BacktestConfig engine) {
        Strategy strategy = StrategyRegistry.defaults().create(loaded.strategyType(), loaded.strategyParameters());
        ParameterizedFeatureGenerator generator = new ParameterizedFeatureGenerator();
        List<Kline> candles = klines.findRangeWithWarmup(symbol, "15m", loaded.dataset().startInclusive(),
                loaded.dataset().endExclusive(), generator.requiredWarmupCandles(FIFTEEN_FEATURES));
        Map<Instant, Kline> byTime = new HashMap<>();
        candles.forEach(candle -> byTime.put(candle.openTime(), candle));
        List<BacktestEngine.BacktestBar> bars = generator.generate(candles, FIFTEEN_FEATURES).stream()
                .filter(item -> !item.candleOpenTime().isBefore(loaded.dataset().startInclusive()))
                .filter(item -> item.candleOpenTime().isBefore(loaded.dataset().endExclusive()))
                .map(item -> new BacktestEngine.BacktestBar(byTime.get(item.candleOpenTime()),
                        attachContext(item, hourly.floorEntry(item.availableAt()),
                                btcHourly.floorEntry(item.availableAt()), ranks)))
                .toList();
        List<Kline> minutes = klines.findRangeWithWarmup(symbol, "1m", loaded.dataset().startInclusive(),
                loaded.dataset().endExclusive(), 0);
        var result = new BacktestEngine(engine).run(strategy, bars,
                new PostgresFundingRateRepository(database).findRange(symbol, loaded.dataset().startInclusive(),
                        loaded.dataset().endExclusive()), minutes);
        return new SymbolResult(symbol, result.account().closedTrades(), new PerformanceReport().generate(result));
    }

    private static FeatureSnapshot attachContext(FeatureSnapshot snapshot,
                                                  Map.Entry<Instant, HourState> hour,
                                                  Map.Entry<Instant, HourState> btcHour,
                                                  Map<Instant, Integer> ranks) {
        Map<FeatureKey, BigDecimal> values = new HashMap<>(snapshot.values());
        Integer rank = hour == null ? null : ranks.get(hour.getKey());
        values.put(FeatureKey.crossSectionRank(), rank == null ? BigDecimal.valueOf(999) : BigDecimal.valueOf(rank));
        values.put(FeatureKey.btcMarketHealthy(), btcHour != null && btcHour.getValue().btcHealthy()
                ? BigDecimal.ONE : BigDecimal.ZERO);
        return new FeatureSnapshot(snapshot.candleOpenTime(), snapshot.availableAt(), snapshot.earliestExecutionTime(), values);
    }

    private static void print(SymbolResult result) {
        var stats = result.report().tradeStats();
        BigDecimal raw = result.trades().stream().map(trade -> trade.grossPnl().add(trade.fundingPnl(), MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.printf("%-8s trades=%3d net=%9s raw=%9s PF=%s DD=%s%%%n", result.symbol(),
                stats.totalTrades(), plain(result.report().netProfit()), plain(raw), plain(stats.profitFactor()),
                plain(result.report().drawdown().maxDrawdownPct()));
    }

    private static void printPooled(List<SymbolResult> results) {
        List<Trade> pooled = results.stream().flatMap(item -> item.trades().stream())
                .sorted(Comparator.comparing(Trade::entryTime)).toList();
        var stats = new TradeStatistics().calculate(pooled);
        BigDecimal raw = pooled.stream().map(trade -> trade.grossPnl().add(trade.fundingPnl(), MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.printf("POOLED trades=%d net=%s raw=%s PF=%s expectancy=%s%n", pooled.size(),
                plain(stats.totalPnl()), plain(raw), plain(stats.profitFactor()), plain(stats.averagePnl()));
    }

    private static void printPortfolio(List<SymbolResult> results) {
        List<PortfolioSimulator.CandidateTrade> candidates = candidates(results);
        var portfolio = new PortfolioSimulator(new PortfolioSimulator.Config(new BigDecimal("100000"), 2,
                new BigDecimal("3"), new BigDecimal("1.5"))).run(candidates);
        System.out.printf("PORTFOLIO return=%s%% final=%s realizedDD=%s%% accepted=%d rejected[position=%d leverage=%d correlation=%d] maxConcurrent=%d%n",
                plain(portfolio.returnPercent()), plain(portfolio.finalBalance()), plain(portfolio.maximumRealizedDrawdownPercent()),
                portfolio.acceptedTrades(), portfolio.positionCapRejections(), portfolio.leverageCapRejections(),
                portfolio.correlationCapRejections(), portfolio.maximumConcurrentPositions());
    }

    private static void printStress(BacktestConfigurationLoader.LoadedConfiguration loaded,
                                    DatabaseConfig database, PostgresKlineRepository klines,
                                    Map<String, NavigableMap<Instant, HourState>> hourly,
                                    Map<String, Map<Instant, Integer>> ranks) {
        var e = loaded.engine().execution();
        var stressedExecution = new BacktestEngine.ExecutionConfig(e.makerEnabled(), e.makerFeeBps().multiply(new BigDecimal("1.5"), MC),
                e.takerFeeBps().multiply(new BigDecimal("1.5"), MC), e.takerSlippageBps().multiply(new BigDecimal("1.5"), MC),
                e.makerOffsetBps(), e.makerOrderLifetimeMinutes(), e.strategyExitTakerFallback(), e.breakEvenEnabled(), e.breakEvenTriggerRiskMultiple());
        var stressed = new BacktestEngine.BacktestConfig(loaded.engine().initialBalance(), loaded.engine().riskFraction(),
                loaded.engine().maxLeverage(), stressedExecution, loaded.engine().propRules(), loaded.engine().exits());
        List<SymbolResult> results = UNIVERSE.stream().map(symbol -> run(symbol, loaded, database, klines,
                hourly.get(symbol), hourly.get("BTCUSDT"), ranks.get(symbol), stressed)).toList();
        BigDecimal net = results.stream().map(item -> item.report().netProfit()).reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.printf("1.5x-cost independent-account net=%s%n", plain(net));
    }

    private static List<PortfolioSimulator.CandidateTrade> candidates(List<SymbolResult> results) {
        List<PortfolioSimulator.CandidateTrade> candidates = new ArrayList<>();
        for (SymbolResult result : results) {
            BigDecimal balance = new BigDecimal("100000");
            for (Trade trade : result.trades().stream().sorted(Comparator.comparing(Trade::entryTime)).toList()) {
                candidates.add(new PortfolioSimulator.CandidateTrade(result.symbol(), trade, balance));
                balance = balance.add(trade.netPnl(), MC);
            }
        }
        return candidates;
    }

    private static String plain(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
