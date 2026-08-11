package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.PostgresFundingRateRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

public final class BtcFundingRateImportApplication {

    private static final String SYMBOL = "BTCUSDT";
    private static final int BATCH_SIZE = 1000;

    private BtcFundingRateImportApplication() {
    }

    public static void main(String[] args) {
        String symbol = System.getProperty("symbol", SYMBOL).trim().toUpperCase();
        String configuredStart = System.getProperty("start", "").trim();
        Instant start = configuredStart.isEmpty() ? null : Instant.parse(configuredStart);
        importSymbol(symbol, start);
    }

    static void importSymbol(String symbol, Instant fixedStartInclusive) {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(config);

        BinanceFundingRateClient client = new BinanceFundingRateClient();
        PostgresFundingRateRepository repository = new PostgresFundingRateRepository(config);
        Instant endInclusive = Instant.now();
        Instant requestedStart = fixedStartInclusive == null
                ? ZonedDateTime.ofInstant(endInclusive, ZoneOffset.UTC).minusYears(3).toInstant()
                : fixedStartInclusive;
        // Funding is sparse (normally one row per eight hours), so replaying the
        // requested history is cheap and safely fills an older prefix around an
        // existing incremental import. The repository upsert is idempotent.
        Instant cursor = requestedStart;

        long imported = 0;
        while (!cursor.isAfter(endInclusive)) {
            List<FundingRate> batch = client.fetch(
                    symbol, cursor.toEpochMilli(), endInclusive.toEpochMilli(), BATCH_SIZE);
            if (batch.isEmpty()) {
                break;
            }
            repository.upsertAll(batch);
            imported += batch.size();
            cursor = batch.getLast().fundingTime().plusMillis(1);
            System.out.printf("Funding rates: %,d imported this run, latest=%s.%n",
                    imported, batch.getLast().fundingTime());
            if (batch.size() < BATCH_SIZE) {
                break;
            }
        }

        System.out.printf("%s funding-rate sync completed: %,d rows processed, %,d total stored.%n",
                symbol, imported, repository.count(symbol));
    }
}
