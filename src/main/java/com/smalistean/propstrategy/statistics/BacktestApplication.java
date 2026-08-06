package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestConfigurationLoader;
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
        Strategy strategy = StrategyRegistry.defaults().create(
                loaded.strategyType(), loaded.strategyParameters());

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        List<Kline> candles = new PostgresKlineRepository(database).findLatest(
                loaded.symbol(), loaded.interval(), loaded.candleLimit());
        List<FeatureSnapshot> snapshots = new ParameterizedFeatureGenerator()
                .generate(candles, strategy.requiredFeatures());
        Map<java.time.Instant, Kline> candlesByOpenTime = new HashMap<>();
        candles.forEach(candle -> candlesByOpenTime.put(candle.openTime(), candle));
        List<BacktestEngine.BacktestBar> bars = snapshots.stream()
                .map(snapshot -> new BacktestEngine.BacktestBar(
                        candlesByOpenTime.get(snapshot.candleOpenTime()), snapshot))
                .toList();
        List<FundingRate> funding = new PostgresFundingRateRepository(database)
                .findThrough(loaded.symbol(), candles.getLast().closeTime());

        BacktestEngine.BacktestResult result = new BacktestEngine(loaded.engine())
                .run(strategy, bars, funding);
        System.out.printf("Backtest: strategy=%s, symbol=%s, interval=%s, candles=%,d, featureBars=%,d%n",
                strategy.name(), loaded.symbol(), loaded.interval(), candles.size(), bars.size());
        System.out.println(new PerformanceReport().generate(result));
        result.account().closedTrades().stream().limit(5).forEach(trade ->
                System.out.printf("%s %s -> %s net=%s reason=%s%n",
                        trade.side(), trade.entryTime(), trade.exitTime(),
                        trade.netPnl().stripTrailingZeros().toPlainString(), trade.exitReason()));
    }
}
