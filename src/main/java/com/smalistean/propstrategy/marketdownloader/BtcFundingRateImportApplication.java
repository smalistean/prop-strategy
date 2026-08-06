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
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(config);

        BinanceFundingRateClient client = new BinanceFundingRateClient();
        PostgresFundingRateRepository repository = new PostgresFundingRateRepository(config);
        Instant endInclusive = Instant.now();
        Instant requestedStart = ZonedDateTime.ofInstant(endInclusive, ZoneOffset.UTC)
                .minusYears(3)
                .toInstant();
        Instant cursor = repository.latestFundingTime(SYMBOL)
                .map(value -> value.plusMillis(1))
                .filter(value -> value.isAfter(requestedStart))
                .orElse(requestedStart);

        long imported = 0;
        while (!cursor.isAfter(endInclusive)) {
            List<FundingRate> batch = client.fetch(
                    SYMBOL, cursor.toEpochMilli(), endInclusive.toEpochMilli(), BATCH_SIZE);
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

        System.out.printf("BTCUSDT funding-rate sync completed: %,d rows processed, %,d total stored.%n",
                imported, repository.count(SYMBOL));
    }
}
