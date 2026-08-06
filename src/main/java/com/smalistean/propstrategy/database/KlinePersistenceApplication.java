package com.smalistean.propstrategy.database;

import com.smalistean.propstrategy.marketdownloader.BinanceKlineClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class KlinePersistenceApplication {

    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1h";
    private static final int LIMIT = 10;

    private KlinePersistenceApplication() {
    }

    public static void main(String[] args) {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(config);

        Instant start = Instant.now()
                .minus(365, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.HOURS);
        Instant end = start.plus(24, ChronoUnit.HOURS);

        List<Kline> klines = new BinanceKlineClient().fetchKlines(
                SYMBOL, INTERVAL, start.toEpochMilli(), end.toEpochMilli(), LIMIT);

        PostgresKlineRepository repository = new PostgresKlineRepository(config);
        int processed = repository.upsertAll(SYMBOL, INTERVAL, klines);
        long stored = repository.count(SYMBOL, INTERVAL);

        System.out.printf("Fetched %d Binance Futures klines.%n", klines.size());
        System.out.printf("Upserted %d rows into PostgreSQL.%n", processed);
        System.out.printf("PostgreSQL now contains %d %s %s klines.%n",
                stored, SYMBOL, INTERVAL);
    }
}
