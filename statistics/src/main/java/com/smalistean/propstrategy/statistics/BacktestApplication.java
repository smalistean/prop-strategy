package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestEngine;
import com.smalistean.propstrategy.backtester.PropRuleEngine;
import com.smalistean.propstrategy.database.CsvKlineRepository;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.marketdownloader.BinanceKlineClient;
import com.smalistean.propstrategy.marketdownloader.KlineDownloader;
import com.smalistean.propstrategy.strategy.TrendPullbackStrategy;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class BacktestApplication {

    public static void main(String[] args) {
        String symbol = args.length > 0 ? args[0] : "BTCUSDT";
        String interval = args.length > 1 ? args[1] : "1h";
        Path dataFile = Path.of(args.length > 2 ? args[2] : "data/" + symbol + "_" + interval + ".csv");

        CsvKlineRepository repository = new CsvKlineRepository();
        List<Kline> klines = loadOrDownload(symbol, interval, dataFile, repository);

        BacktestEngine.BacktestConfig config = new BacktestEngine.BacktestConfig(
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(0.01),
                BigDecimal.valueOf(0.02),
                BigDecimal.valueOf(2),
                BigDecimal.valueOf(4),
                new PropRuleEngine.PropRules(
                        BigDecimal.valueOf(10),
                        BigDecimal.valueOf(5),
                        BigDecimal.valueOf(10)
                )
        );

        BacktestEngine engine = new BacktestEngine(config);
        BacktestEngine.BacktestResult result = engine.run(new TrendPullbackStrategy(), klines);

        PerformanceReport report = new PerformanceReport();
        System.out.println(report.generate(result));
    }

    private static List<Kline> loadOrDownload(String symbol, String interval, Path dataFile,
                                              CsvKlineRepository repository) {
        if (Files.exists(dataFile)) {
            return repository.load(dataFile);
        }

        Instant end = Instant.now();
        Instant start = end.minus(90, ChronoUnit.DAYS);
        KlineDownloader downloader = new KlineDownloader(new BinanceKlineClient(), repository);
        downloader.download(symbol, interval, start, end, dataFile);
        return repository.load(dataFile);
    }
}
