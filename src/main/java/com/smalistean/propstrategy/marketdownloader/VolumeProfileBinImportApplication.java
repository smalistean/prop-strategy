package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.PostgresVolumeProfileBinRepository;
import com.smalistean.propstrategy.database.VolumeProfileBin;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class VolumeProfileBinImportApplication {
    private VolumeProfileBinImportApplication() {}

    public static void main(String[] args) throws Exception {
        String symbol = System.getProperty("profileSymbol", "BTCUSDT").trim().toUpperCase();
        Instant start = Instant.parse(System.getProperty("profileStart", "2023-08-07T00:00:00Z"));
        Instant end = Instant.parse(System.getProperty("profileEnd", "2025-08-07T00:00:00Z"));
        int bucketMinutes = Integer.getInteger("profileBucketMinutes", 15);
        BigDecimal priceStep = System.getProperty("profilePriceStep") != null
                ? new BigDecimal(System.getProperty("profilePriceStep"))
                : com.smalistean.propstrategy.database.VolumeProfilePriceSteps.defaultFor(symbol);
        Path directory = Path.of(System.getProperty(
                "profileArchiveDir", "data/order-flow/" + symbol));
        List<Path> archives;
        try (var paths = Files.list(directory)) {
            archives = paths.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .filter(path -> HistoricalVolumeProfileApplication.overlaps(path, start, end))
                    .sorted().toList();
        }
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        PostgresVolumeProfileBinRepository repository = new PostgresVolumeProfileBinRepository(database);
        AggregateTradePriceBinReader reader = new AggregateTradePriceBinReader();
        int done = 0;
        for (Path archive : archives) {
            long started = System.nanoTime();
            String name = archive.getFileName().toString();
            String sha256 = BinanceArchiveDownloader.sha256(archive);
            if (repository.archiveCompleted(symbol, name, bucketMinutes, priceStep, sha256)) {
                System.out.printf("[%d/%d] SKIP %s%n", ++done, archives.size(), name);
                continue;
            }
            AggregateTradePriceBinReader.Result result = reader.read(
                    archive, symbol, start, end, bucketMinutes, priceStep);
            int persisted = 0;
            for (int offset = 0; offset < result.bins().size(); offset += 1_000) {
                List<VolumeProfileBin> batch = result.bins().subList(offset,
                        Math.min(offset + 1_000, result.bins().size()));
                persisted += repository.upsertAll(batch);
            }
            repository.recordCompleted(symbol, name, bucketMinutes, priceStep, sha256,
                    result.sourceRows(), persisted);
            System.out.printf("[%d/%d] DONE %s source=%,d included=%,d bins=%,d seconds=%.1f%n",
                    ++done, archives.size(), name, result.sourceRows(), result.includedRows(), persisted,
                    (System.nanoTime() - started) / 1_000_000_000.0);
        }
        System.out.printf("Stored %,d %s profile bins for [%s, %s), bucket=%dm step=%s%n",
                repository.count(symbol, bucketMinutes, priceStep, start, end), symbol, start, end,
                bucketMinutes, priceStep.toPlainString());
    }
}
