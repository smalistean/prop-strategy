package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.database.PostgresKlineRepository.KlineRangeStats;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

public final class BtcHistoricalImportApplication {

    private static final String SYMBOL = "BTCUSDT";
    private static final int BATCH_SIZE = 1000;

    private BtcHistoricalImportApplication() {
    }

    public static void main(String[] args) {
        importSymbol(SYMBOL, null);
    }

    static void importSymbol(String symbol, Instant fixedStartInclusive) {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(config);

        BinanceKlineClient client = new BinanceKlineClient();
        PostgresKlineRepository repository = new PostgresKlineRepository(config);
        Instant now = Instant.now();

        for (KlineInterval interval : KlineInterval.values()) {
            importInterval(symbol, fixedStartInclusive, client, repository, interval, now);
        }
        System.out.printf("%s Futures import completed and verified.%n", symbol);
    }

    private static void importInterval(String symbol, Instant fixedStartInclusive,
                                       BinanceKlineClient client,
                                       PostgresKlineRepository repository,
                                       KlineInterval interval,
                                       Instant now) {
        Instant endExclusive = interval.floor(now);
        Instant requestedStart = fixedStartInclusive == null
                ? ZonedDateTime.ofInstant(endExclusive, ZoneOffset.UTC).minusYears(3).toInstant()
                : fixedStartInclusive;
        Instant startInclusive = interval.floor(requestedStart);
        long expected = Duration.between(startInclusive, endExclusive)
                .dividedBy(interval.duration());

        KlineRangeStats initial = repository.rangeStats(
                symbol, interval.code(), startInclusive, endExclusive);
        Instant cursor = resumeCursor(initial, startInclusive, interval);
        System.out.printf("%s %s: importing %,d expected candles from %s to %s; %,d already stored.%n",
                symbol, interval.code(), expected, startInclusive, endExclusive, initial.count());

        long startedAt = System.nanoTime();
        int batches = 0;
        while (cursor.isBefore(endExclusive)) {
            long remaining = Duration.between(cursor, endExclusive)
                    .dividedBy(interval.duration());
            int limit = (int) Math.min(BATCH_SIZE, remaining);
            long endTimeInclusive = endExclusive.toEpochMilli() - 1;
            Instant batchStart = cursor;

            List<Kline> batch = client.fetchKlines(
                            symbol, interval.code(), cursor.toEpochMilli(), endTimeInclusive, limit)
                    .stream()
                    .filter(kline -> !kline.openTime().isBefore(batchStart)
                            && kline.openTime().isBefore(endExclusive))
                    .toList();

            if (batch.isEmpty()) {
                throw new IllegalStateException("Binance returned no " + interval.code()
                        + " candles at " + cursor);
            }
            repository.upsertAll(symbol, interval.code(), batch);
            cursor = batch.getLast().openTime().plus(interval.duration());
            batches++;
            if (batches % 10 == 0 || !cursor.isBefore(endExclusive)) {
                long completedPrefix = Duration.between(startInclusive, cursor)
                        .dividedBy(interval.duration());
                double percent = completedPrefix * 100.0 / expected;
                long elapsedSeconds = Duration.ofNanos(System.nanoTime() - startedAt).toSeconds();
                System.out.printf("%s %s: %,d / %,d (%.2f%%), last=%s, elapsed=%ds%n",
                        symbol, interval.code(), completedPrefix, expected, percent,
                        cursor.minus(interval.duration()), elapsedSeconds);
            }
        }

        KlineRangeStats result = repository.rangeStats(
                symbol, interval.code(), startInclusive, endExclusive);
        Instant expectedLast = endExclusive.minus(interval.duration());
        if (result.count() != expected
                || !startInclusive.equals(result.firstOpenTime())
                || !expectedLast.equals(result.lastOpenTime())) {
            throw new IllegalStateException("Verification failed for %s: expected %,d rows [%s, %s], got %s"
                    .formatted(interval.code(), expected, startInclusive, expectedLast, result));
        }
        System.out.printf("%s %s: verified %,d candles.%n",
                symbol, interval.code(), result.count());
    }

    private static Instant resumeCursor(KlineRangeStats existing, Instant startInclusive,
                                        KlineInterval interval) {
        if (existing.count() == 0 || !startInclusive.equals(existing.firstOpenTime())) {
            return startInclusive;
        }
        long prefixCount = Duration.between(startInclusive, existing.lastOpenTime())
                .dividedBy(interval.duration()) + 1;
        return existing.count() == prefixCount
                ? existing.lastOpenTime().plus(interval.duration())
                : startInclusive;
    }
}
