package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestConfigurationLoader;
import com.smalistean.propstrategy.backtester.BacktestEngine;
import com.smalistean.propstrategy.backtester.Trade;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresFundingRateRepository;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.MultiTimeframeFeatureAssembler;
import com.smalistean.propstrategy.strategy.StrategyRegistry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Runs the frozen multi-timeframe hypothesis over an unselected symbol universe. */
public final class MultiTimeframeResearchApplication {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);
    private static final List<String> UNIVERSE = List.of(
            "BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT",
            "BNBUSDT", "ADAUSDT", "DOGEUSDT", "LINKUSDT");

    private record SymbolResult(String symbol, List<Trade> trades,
                                PerformanceReport.Report report) {
    }

    private MultiTimeframeResearchApplication() {
    }

    public static void main(String[] args) {
        Path engineFile = Path.of(System.getProperty("engineConfig",
                "config/backtests/engine-multi-timeframe-frozen.properties"));
        Path strategyFile = Path.of(System.getProperty("strategyConfig",
                "config/backtests/multi-timeframe-flat-long.properties"));
        var loaded = new BacktestConfigurationLoader().load(engineFile, strategyFile);
        if (!loaded.strategyType().equals("multi-timeframe-flat-long")
                || !loaded.interval().equals("5m")) {
            throw new IllegalArgumentException("Research runner requires the 5m multi-timeframe strategy");
        }

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        List<String> universe = configuredUniverse();
        List<SymbolResult> results = new ArrayList<>();
        for (String symbol : universe) {
            SymbolResult result = run(symbol, loaded, database, loaded.engine());
            results.add(result);
            print(result);
        }
        printPooled(results, loaded.dataset().startInclusive(), loaded.dataset().endExclusive());

        var execution = loaded.engine().execution();
        var stressedExecution = new BacktestEngine.ExecutionConfig(execution.makerEnabled(),
                execution.makerFeeBps().multiply(new BigDecimal("1.5"), MC),
                execution.takerFeeBps().multiply(new BigDecimal("1.5"), MC),
                execution.takerSlippageBps().multiply(new BigDecimal("1.5"), MC),
                execution.makerOffsetBps(), execution.makerOrderLifetimeMinutes(),
                execution.strategyExitTakerFallback(), execution.breakEvenEnabled(),
                execution.breakEvenTriggerRiskMultiple());
        var stressedConfig = new BacktestEngine.BacktestConfig(loaded.engine().initialBalance(),
                loaded.engine().riskFraction(), loaded.engine().maxLeverage(), stressedExecution,
                loaded.engine().propRules(), loaded.engine().exits());
        List<SymbolResult> stressed = new ArrayList<>();
        for (String symbol : universe) {
            stressed.add(run(symbol, loaded, database, stressedConfig));
        }
        BigDecimal stressedNet = stressed.stream().map(item -> item.report().netProfit())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.printf("1.5x-cost pooled net=%s, trades=%d%n", plain(stressedNet),
                stressed.stream().mapToInt(item -> item.trades().size()).sum());
    }

    private static SymbolResult run(String symbol,
                                    BacktestConfigurationLoader.LoadedConfiguration loaded,
                                    DatabaseConfig database,
                                    BacktestEngine.BacktestConfig engine) {
        var strategy = StrategyRegistry.defaults().create(
                loaded.strategyType(), loaded.strategyParameters());
        var klines = new PostgresKlineRepository(database);
        List<Kline> five = klines.findRangeWithWarmup(symbol, "5m",
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 1);
        List<Kline> fifteen = klines.findRangeWithWarmup(symbol, "15m",
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 220);
        List<Kline> hourly = klines.findRangeWithWarmup(symbol, "1h",
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 25);
        List<FeatureSnapshot> snapshots = new MultiTimeframeFeatureAssembler()
                .assemble(five, fifteen, hourly, 200, 14, 14).stream()
                .filter(item -> !item.candleOpenTime().isBefore(loaded.dataset().startInclusive()))
                .filter(item -> item.candleOpenTime().isBefore(loaded.dataset().endExclusive()))
                .toList();
        Map<Instant, Kline> fiveByTime = new HashMap<>();
        five.forEach(candle -> fiveByTime.put(candle.openTime(), candle));
        List<BacktestEngine.BacktestBar> bars = snapshots.stream()
                .map(item -> new BacktestEngine.BacktestBar(
                        fiveByTime.get(item.candleOpenTime()), item)).toList();
        var funding = new PostgresFundingRateRepository(database).findRange(symbol,
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive());
        List<Kline> minutes = klines.findRangeWithWarmup(symbol, "1m",
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 0);
        var result = new BacktestEngine(engine).run(strategy, bars, funding, minutes);
        return new SymbolResult(symbol, result.account().closedTrades(),
                new PerformanceReport().generate(result));
    }

    private static void print(SymbolResult result) {
        var stats = result.report().tradeStats();
        BigDecimal raw = result.trades().stream()
                .map(trade -> trade.grossPnl().add(trade.fundingPnl(), MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        System.out.printf("%-8s trades=%3d return=%8s%% net=%10s raw=%10s PF=%s DD=%s%%%n",
                result.symbol(), stats.totalTrades(), plain(result.report().returnPct()),
                plain(result.report().netProfit()), plain(raw), plain(stats.profitFactor()),
                plain(result.report().drawdown().maxDrawdownPct()));
    }

    private static void printPooled(List<SymbolResult> results, Instant start, Instant end) {
        List<Trade> pooled = results.stream().flatMap(item -> item.trades().stream())
                .sorted(Comparator.comparing(Trade::entryTime)).toList();
        var stats = new TradeStatistics().calculate(pooled);
        BigDecimal raw = pooled.stream().map(trade -> trade.grossPnl().add(trade.fundingPnl(), MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal positiveNet = results.stream().map(item -> item.report().netProfit())
                .filter(value -> value.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal largestContribution = results.stream().map(item -> item.report().netProfit())
                .filter(value -> value.signum() > 0).max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        long overlaps = 0;
        for (int i = 0; i < pooled.size(); i++) {
            for (int j = i + 1; j < pooled.size(); j++) {
                if (!pooled.get(j).entryTime().isBefore(pooled.get(i).exitTime())) break;
                overlaps++;
            }
        }
        System.out.printf("POOLED trades=%d net=%s raw=%s PF=%s overlappingPairs=%d "
                        + "largestPositiveSymbolShare=%s%%%n", pooled.size(), plain(stats.totalPnl()),
                plain(raw), plain(stats.profitFactor()), overlaps,
                positiveNet.signum() == 0 ? "0" : plain(largestContribution.multiply(BigDecimal.valueOf(100), MC)
                        .divide(positiveNet, MC)));

        Instant segmentStart = start;
        for (int index = 1; segmentStart.isBefore(end); index++) {
            Instant segmentEnd = ZonedDateTime.ofInstant(segmentStart,
                    ZoneOffset.UTC).plusMonths(6).toInstant();
            if (segmentEnd.isAfter(end)) segmentEnd = end;
            Instant from = segmentStart;
            Instant to = segmentEnd;
            List<Trade> segment = pooled.stream().filter(trade -> !trade.exitTime().isBefore(from)
                    && trade.exitTime().isBefore(to)).toList();
            var segmentStats = new TradeStatistics().calculate(segment);
            System.out.printf("  segment%d [%s,%s) trades=%d net=%s PF=%s%n", index, from, to,
                    segment.size(), plain(segmentStats.totalPnl()), plain(segmentStats.profitFactor()));
            segmentStart = segmentEnd;
        }
    }

    private static String plain(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static List<String> configuredUniverse() {
        String configured = System.getProperty("symbols", "").trim();
        if (configured.isEmpty()) return UNIVERSE;
        List<String> symbols = java.util.Arrays.stream(configured.split(","))
                .map(String::trim).map(String::toUpperCase).distinct().toList();
        if (symbols.isEmpty() || symbols.stream().anyMatch(value -> !UNIVERSE.contains(value))) {
            throw new IllegalArgumentException("symbols must be members of the frozen universe");
        }
        return symbols;
    }
}
