package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.OpenInterestStatistic;
import com.smalistean.propstrategy.database.PostgresSupportingMarketDataRepository;
import com.smalistean.propstrategy.database.TraderRatio;
import com.smalistean.propstrategy.database.TraderRatio.RatioType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class BtcSupportingMarketDataImportApplication {

    private static final String SYMBOL = "BTCUSDT";
    private static final String PERIOD = "5m";
    private static final int BATCH_SIZE = 500;
    private static final Duration AVAILABLE_HISTORY = Duration.ofDays(30);

    private BtcSupportingMarketDataImportApplication() {
    }

    public static void main(String[] args) {
        importSymbol(SYMBOL);
    }

    static void importSymbol(String symbol) {
        String apiKey = requireEnvironment("BINANCE_API_KEY");
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(config);

        BinanceSupportingMarketDataClient client =
                new BinanceSupportingMarketDataClient(apiKey);
        PostgresSupportingMarketDataRepository repository =
                new PostgresSupportingMarketDataRepository(config);
        Instant endInclusive = Instant.now();
        Instant earliestAvailable = endInclusive.minus(AVAILABLE_HISTORY);

        importOpenInterest(symbol, client, repository, earliestAvailable, endInclusive);
        for (RatioType type : RatioType.values()) {
            importRatio(symbol, client, repository, type, earliestAvailable, endInclusive);
        }
        System.out.printf("%s supporting-market-data sync completed.%n", symbol);
    }

    private static void importOpenInterest(String symbol,
                                           BinanceSupportingMarketDataClient client,
                                           PostgresSupportingMarketDataRepository repository,
                                           Instant earliestAvailable,
                                           Instant endInclusive) {
        Instant cursor = endInclusive;
        long processed = 0;
        while (!cursor.isBefore(earliestAvailable)) {
            List<OpenInterestStatistic> batch = client.fetchOpenInterest(
                    symbol, PERIOD, earliestAvailable.toEpochMilli(), cursor.toEpochMilli(), BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            repository.upsertOpenInterest(batch);
            processed += batch.size();
            Instant firstTime = batch.getFirst().statisticTime();
            if (!firstTime.isAfter(earliestAvailable)) {
                break;
            }
            cursor = previousCursor(cursor, firstTime);
        }
        System.out.printf("Open interest: %,d processed, %,d total stored.%n",
                processed, repository.openInterestCount(symbol, PERIOD));
    }

    private static void importRatio(String symbol,
                                    BinanceSupportingMarketDataClient client,
                                    PostgresSupportingMarketDataRepository repository,
                                    RatioType type,
                                    Instant earliestAvailable,
                                    Instant endInclusive) {
        Instant cursor = endInclusive;
        long processed = 0;
        while (!cursor.isBefore(earliestAvailable)) {
            List<TraderRatio> batch = client.fetchRatios(
                    symbol, PERIOD, type, earliestAvailable.toEpochMilli(),
                    cursor.toEpochMilli(), BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            repository.upsertRatios(batch);
            processed += batch.size();
            Instant firstTime = batch.getFirst().statisticTime();
            if (!firstTime.isAfter(earliestAvailable)) {
                break;
            }
            cursor = previousCursor(cursor, firstTime);
        }
        System.out.printf("%s: %,d processed, %,d total stored.%n",
                type, processed, repository.ratioCount(symbol, PERIOD, type));
    }

    static Instant previousCursor(Instant currentCursor, Instant firstPageTime) {
        if (!firstPageTime.isBefore(currentCursor)) {
            throw new IllegalStateException("Binance page did not move backward from "
                    + currentCursor + "; first row was " + firstPageTime);
        }
        return firstPageTime.minusMillis(1);
    }

    private static String requireEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " environment variable is required");
        }
        return value;
    }
}
