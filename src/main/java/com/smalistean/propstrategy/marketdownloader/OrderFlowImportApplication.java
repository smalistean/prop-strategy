package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.AggregateTradeMinute;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.PostgresAggregateTradeMinuteRepository;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class OrderFlowImportApplication {

    private static final Instant TRAINING_START = Instant.parse("2023-08-07T00:00:00Z");
    private static final Instant TRAINING_END = Instant.parse("2025-08-07T00:00:00Z");

    private OrderFlowImportApplication() {
    }

    public static void main(String[] args) throws Exception {
        if (Boolean.getBoolean("orderFlowMigrateOnly")) {
            DatabaseMigrator.migrate(DatabaseConfig.fromEnvironment());
            System.out.println("Order-flow database migration complete");
            return;
        }
        String symbol = System.getProperty("orderFlowSymbol", "BTCUSDT");
        Instant start = Instant.parse(System.getProperty("orderFlowStart", TRAINING_START.toString()));
        Instant end = Instant.parse(System.getProperty("orderFlowEnd", TRAINING_END.toString()));
        if (!start.isBefore(end)) throw new IllegalArgumentException("orderFlowStart must precede orderFlowEnd");
        boolean persist = Boolean.getBoolean("orderFlowPersist");
        String sourceUrl = System.getProperty("orderFlowUrl", "");
        Path archive;
        String sha256;
        long archiveSize;
        if (!sourceUrl.isBlank()) {
            String directory = System.getProperty("orderFlowArchiveDir", "");
            if (directory.isBlank()) {
                throw new IllegalArgumentException("orderFlowArchiveDir is required for downloads");
            }
            BinanceArchiveDownloader.DownloadedArchive downloaded =
                    new BinanceArchiveDownloader().download(URI.create(sourceUrl), Path.of(directory));
            archive = downloaded.path(); sha256 = downloaded.sha256(); archiveSize = downloaded.size();
        } else {
            String file = System.getProperty("orderFlowArchive", "");
            if (file.isBlank()) throw new IllegalArgumentException("Set orderFlowArchive or orderFlowUrl");
            archive = Path.of(file); sha256 = BinanceArchiveDownloader.sha256(archive);
            archiveSize = Files.size(archive); sourceUrl = archive.toUri().toString();
        }

        AggregateTradeArchiveReader.Result result = new AggregateTradeArchiveReader()
                .read(archive, symbol, start, end);
        System.out.printf("Archive=%s bytes=%,d sha256=%s sourceRows=%,d filtered=%,d "
                        + "minutes=%,d duplicates=%,d missingIds=%,d persist=%s%n",
                archive.getFileName(), archiveSize, sha256, result.sourceRows(), result.filteredRows(),
                result.minutes().size(), result.duplicateRows(), result.gapCount(), persist);
        if (!result.minutes().isEmpty()) {
            System.out.printf("Boundary [%s, %s]%n", result.minutes().getFirst().minuteTime(),
                    result.minutes().getLast().minuteTime());
        }
        if (!persist) return;

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        PostgresAggregateTradeMinuteRepository repository =
                new PostgresAggregateTradeMinuteRepository(database);
        int inserted = 0;
        for (int offset = 0; offset < result.minutes().size(); offset += 1_000) {
            List<AggregateTradeMinute> batch = result.minutes().subList(offset,
                    Math.min(offset + 1_000, result.minutes().size()));
            inserted += repository.upsertAll(batch);
        }
        repository.reconcile(symbol, start, end);
        repository.recordCompletedArchive(archive.getFileName().toString(), sourceUrl, sha256,
                archiveSize, result.minutes().size(), result.sourceRows());
        System.out.printf("Persisted and reconciled %,d minute rows%n", inserted);
    }
}
