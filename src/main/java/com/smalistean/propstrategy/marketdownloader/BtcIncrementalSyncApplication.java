package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresKlineRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class BtcIncrementalSyncApplication {

    private static final String SYMBOL = "BTCUSDT";
    private static final int BATCH_SIZE = 1000;

    private BtcIncrementalSyncApplication() {
    }

    public static void main(String[] args) {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(config);

        BinanceKlineClient client = new BinanceKlineClient();
        PostgresKlineRepository repository = new PostgresKlineRepository(config);
        Instant now = Instant.now();

        long synchronizedCandles = 0;
        for (KlineInterval interval : KlineInterval.values()) {
            synchronizedCandles += synchronizeInterval(client, repository, interval, now);
        }
        System.out.printf("BTCUSDT incremental sync completed: %,d closed candles upserted.%n",
                synchronizedCandles);
    }

    private static long synchronizeInterval(BinanceKlineClient client,
                                            PostgresKlineRepository repository,
                                            KlineInterval interval,
                                            Instant now) {
        Instant endExclusive = interval.floor(now);
        Optional<Instant> latest = repository.latestOpenTime(SYMBOL, interval.code());
        Instant cursor = nextCursor(latest, interval);
        if (cursor == null) {
            throw new IllegalStateException("No existing BTCUSDT " + interval.code()
                    + " candles. Run BtcHistoricalImportApplication first.");
        }
        if (!cursor.isBefore(endExclusive)) {
            System.out.printf("%s: already current through %s.%n",
                    interval.code(), endExclusive.minus(interval.duration()));
            return 0;
        }

        long expected = Duration.between(cursor, endExclusive).dividedBy(interval.duration());
        long synchronizedCandles = 0;
        System.out.printf("%s: synchronizing %,d closed candles from %s to %s.%n",
                interval.code(), expected, cursor, endExclusive);

        while (cursor.isBefore(endExclusive)) {
            long remaining = Duration.between(cursor, endExclusive).dividedBy(interval.duration());
            int limit = (int) Math.min(BATCH_SIZE, remaining);
            Instant batchStart = cursor;
            List<Kline> batch = client.fetchKlines(
                            SYMBOL, interval.code(), cursor.toEpochMilli(),
                            endExclusive.toEpochMilli() - 1, limit)
                    .stream()
                    .filter(kline -> !kline.openTime().isBefore(batchStart)
                            && kline.openTime().isBefore(endExclusive))
                    .toList();

            if (batch.isEmpty()) {
                throw new IllegalStateException("Binance returned no " + interval.code()
                        + " candles at " + cursor);
            }
            repository.upsertAll(SYMBOL, interval.code(), batch);
            synchronizedCandles += batch.size();
            cursor = batch.getLast().openTime().plus(interval.duration());
        }

        Instant actualLatest = repository.latestOpenTime(SYMBOL, interval.code()).orElseThrow();
        Instant expectedLatest = endExclusive.minus(interval.duration());
        if (!expectedLatest.equals(actualLatest) || synchronizedCandles != expected) {
            throw new IllegalStateException("Incremental verification failed for %s: "
                    .formatted(interval.code())
                    + "expected latest=" + expectedLatest + " and count=" + expected
                    + ", got latest=" + actualLatest + " and count=" + synchronizedCandles);
        }
        System.out.printf("%s: verified %,d new candles through %s.%n",
                interval.code(), synchronizedCandles, actualLatest);
        return synchronizedCandles;
    }

    static Instant nextCursor(Optional<Instant> latestOpenTime, KlineInterval interval) {
        return latestOpenTime.map(value -> value.plus(interval.duration())).orElse(null);
    }
}
