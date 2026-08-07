package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestConfigurationLoader;
import com.smalistean.propstrategy.backtester.BacktestEngine;
import com.smalistean.propstrategy.backtester.PortfolioSimulator;
import com.smalistean.propstrategy.backtester.Trade;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresFundingRateRepository;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.ParameterizedFeatureGenerator;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyRegistry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PortfolioExperimentApplication {

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final List<String> FULL_UNIVERSE = List.of(
            "BTCUSDT", "SOLUSDT", "XRPUSDT", "BNBUSDT", "ADAUSDT", "DOGEUSDT", "LINKUSDT");
    private static final List<String> TRAINING_POSITIVE = List.of(
            "BTCUSDT", "XRPUSDT", "ADAUSDT", "DOGEUSDT", "LINKUSDT");

    private PortfolioExperimentApplication() {
    }

    public static void main(String[] args) {
        Path engineFile = Path.of(System.getProperty("engineConfig", "config/backtests/engine.properties"));
        Path strategyFile = Path.of(System.getProperty(
                "strategyConfig", "config/backtests/rsi-atr-regime-flat-long.properties"));
        BacktestConfigurationLoader.LoadedConfiguration loaded =
                new BacktestConfigurationLoader().load(engineFile, strategyFile);
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        Map<String, List<Trade>> tradesBySymbol = new HashMap<>();
        for (String symbol : FULL_UNIVERSE) {
            List<Trade> trades = runSymbol(symbol, loaded, database);
            tradesBySymbol.put(symbol, trades);
            System.out.printf("Source %-8s trades=%d net=%s%n", symbol, trades.size(),
                    trades.stream().map(Trade::netPnl)
                            .reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros());
        }

        runScenario("full-universe", FULL_UNIVERSE, tradesBySymbol, 3, "3.0", "1.5");
        runScenario("full-universe-strict", FULL_UNIVERSE, tradesBySymbol, 2, "2.0", "1.0");
        runScenario("training-positive", TRAINING_POSITIVE, tradesBySymbol, 3, "3.0", "1.5");
        runScenario("training-positive-strict", TRAINING_POSITIVE, tradesBySymbol, 2, "2.0", "1.0");
    }

    private static void runScenario(String name, List<String> symbols,
                                    Map<String, List<Trade>> tradesBySymbol,
                                    int positions, String leverage, String correlatedFraction) {
        List<PortfolioSimulator.CandidateTrade> candidates = new ArrayList<>();
        for (String symbol : symbols) {
            BigDecimal sourceBalance = new BigDecimal("100000");
            for (Trade trade : tradesBySymbol.get(symbol).stream()
                    .sorted(java.util.Comparator.comparing(Trade::entryTime)).toList()) {
                candidates.add(new PortfolioSimulator.CandidateTrade(symbol, trade, sourceBalance));
                sourceBalance = sourceBalance.add(trade.netPnl(), MC);
            }
        }
        PortfolioSimulator.Result result = new PortfolioSimulator(new PortfolioSimulator.Config(
                new BigDecimal("100000"), positions, new BigDecimal(leverage),
                new BigDecimal(correlatedFraction))).run(candidates);
        System.out.printf("Portfolio %-24s return=%s%% final=%s realizedDD=%s%% accepted=%d "
                        + "rejected[position=%d leverage=%d correlation=%d] maxConcurrent=%d%n",
                name, result.returnPercent().stripTrailingZeros(),
                result.finalBalance().stripTrailingZeros(),
                result.maximumRealizedDrawdownPercent().stripTrailingZeros(),
                result.acceptedTrades(), result.positionCapRejections(),
                result.leverageCapRejections(), result.correlationCapRejections(),
                result.maximumConcurrentPositions());
    }

    private static List<Trade> runSymbol(String symbol,
                                         BacktestConfigurationLoader.LoadedConfiguration loaded,
                                         DatabaseConfig database) {
        Strategy strategy = StrategyRegistry.defaults().create(
                loaded.strategyType(), loaded.strategyParameters());
        ParameterizedFeatureGenerator generator = new ParameterizedFeatureGenerator();
        PostgresKlineRepository klines = new PostgresKlineRepository(database);
        int warmup = generator.requiredWarmupCandles(strategy.requiredFeatures());
        List<Kline> candles = klines.findRangeWithWarmup(symbol, loaded.interval(),
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), warmup);
        List<FeatureSnapshot> snapshots = generator.generate(candles, strategy.requiredFeatures())
                .stream().filter(item -> !item.candleOpenTime().isBefore(loaded.dataset().startInclusive()))
                .filter(item -> item.candleOpenTime().isBefore(loaded.dataset().endExclusive())).toList();
        Map<java.time.Instant, Kline> byTime = new HashMap<>();
        candles.forEach(candle -> byTime.put(candle.openTime(), candle));
        List<BacktestEngine.BacktestBar> bars = snapshots.stream()
                .map(item -> new BacktestEngine.BacktestBar(byTime.get(item.candleOpenTime()), item)).toList();
        List<FundingRate> funding = new PostgresFundingRateRepository(database).findRange(symbol,
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive());
        List<Kline> minutes = klines.findRangeWithWarmup(symbol, "1m",
                loaded.dataset().startInclusive(), loaded.dataset().endExclusive(), 0);
        return new BacktestEngine(loaded.engine()).run(strategy, bars, funding, minutes)
                .account().closedTrades();
    }
}
