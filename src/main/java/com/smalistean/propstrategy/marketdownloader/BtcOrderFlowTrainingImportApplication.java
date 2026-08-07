package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.AggregateTradeMinute;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.PostgresAggregateTradeMinuteRepository;

import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

public final class BtcOrderFlowTrainingImportApplication {

    static final String SYMBOL = "BTCUSDT";
    static final Instant START = Instant.parse("2023-08-07T00:00:00Z");
    static final Instant END = Instant.parse("2025-08-07T00:00:00Z");
    private static final String ROOT = "https://data.binance.vision/data/futures/um";

    private BtcOrderFlowTrainingImportApplication() {
    }

    public static void main(String[] args) {
        String symbol = System.getProperty("orderFlowSymbol", SYMBOL).trim().toUpperCase();
        Instant start = Instant.parse(System.getProperty("orderFlowStart", START.toString()));
        Instant end = Instant.parse(System.getProperty("orderFlowEnd", END.toString()));
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("orderFlowStart must precede orderFlowEnd");
        }
        String directoryProperty = System.getProperty(
                "orderFlowArchiveDir", "data/order-flow/" + symbol);
        Path archiveDirectory = Path.of(directoryProperty).toAbsolutePath();
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        PostgresAggregateTradeMinuteRepository repository =
                new PostgresAggregateTradeMinuteRepository(database);
        BinanceArchiveDownloader downloader = new BinanceArchiveDownloader();
        AggregateTradeArchiveReader reader = new AggregateTradeArchiveReader();
        long totalSourceRows = 0;
        int totalMinuteRows = 0;
        int completed = 0;
        List<Archive> archives = symbol.equals(SYMBOL) && start.equals(START) && end.equals(END)
                ? archives() : monthlyArchives(symbol, start, end);

        for (Archive planned : archives) {
            long started = System.nanoTime();
            BinanceArchiveDownloader.DownloadedArchive downloaded = downloader.download(
                    planned.uri(), archiveDirectory);
            String archiveName = downloaded.path().getFileName().toString();
            if (repository.archiveCompleted(archiveName, downloaded.sha256())) {
                completed++;
                System.out.printf("[%d/%d] SKIP completed %s%n", completed, archives.size(), archiveName);
                continue;
            }
            OptionalLong latest = repository.latestAggregateTradeIdBefore(symbol, planned.start());
            AggregateTradeArchiveReader.Result result = reader.read(downloaded.path(), symbol,
                    start, end, latest.isPresent() ? latest.getAsLong() : null);
            int persisted = 0;
            for (int offset = 0; offset < result.minutes().size(); offset += 1_000) {
                List<AggregateTradeMinute> batch = result.minutes().subList(offset,
                        Math.min(offset + 1_000, result.minutes().size()));
                persisted += repository.upsertAll(batch);
            }
            if (!result.minutes().isEmpty()) {
                Instant first = result.minutes().getFirst().minuteTime();
                Instant lastExclusive = result.minutes().getLast().minuteTime().plusSeconds(60);
                repository.reconcile(symbol, first, lastExclusive);
            }
            repository.recordCompletedArchive(archiveName, planned.uri().toString(),
                    downloaded.sha256(), downloaded.size(), result.minutes().size(), result.sourceRows());
            completed++; totalSourceRows += result.sourceRows(); totalMinuteRows += persisted;
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            System.out.printf("[%d/%d] DONE %s bytes=%,d sourceRows=%,d minutes=%,d "
                            + "filtered=%,d duplicates=%,d gaps=%,d seconds=%.1f%n",
                    completed, archives.size(), archiveName, downloaded.size(), result.sourceRows(),
                    persisted, result.filteredRows(), result.duplicateRows(), result.gapCount(), seconds);
        }
        long stored = repository.count(symbol, start, end);
        System.out.printf("%s order-flow training import complete: newSourceRows=%,d "
                        + "newMinuteRows=%,d storedMinutes=%,d expectedMaximum=%,d archives=%d%n",
                symbol, totalSourceRows, totalMinuteRows, stored,
                java.time.Duration.between(start, end).toMinutes(), archives.size());
    }

    static List<Archive> archives() {
        List<Archive> result = new ArrayList<>();
        YearMonth month = YearMonth.of(2023, 8);
        YearMonth lastMonth = YearMonth.of(2025, 7);
        while (!month.isAfter(lastMonth)) {
            String period = month.toString();
            Instant monthStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            result.add(new Archive(URI.create(ROOT + "/monthly/aggTrades/" + SYMBOL + "/"
                    + SYMBOL + "-aggTrades-" + period + ".zip"), monthStart, monthEnd));
            month = month.plusMonths(1);
        }
        for (int day = 1; day <= 6; day++) {
            String date = LocalDate.of(2025, 8, day).toString();
            Instant dayStart = LocalDate.of(2025, 8, day).atStartOfDay(ZoneOffset.UTC).toInstant();
            result.add(new Archive(URI.create(ROOT + "/daily/aggTrades/" + SYMBOL + "/"
                    + SYMBOL + "-aggTrades-" + date + ".zip"), dayStart, dayStart.plusSeconds(86_400)));
        }
        return List.copyOf(result);
    }

    static List<Archive> monthlyArchives(String symbol, Instant start, Instant end) {
        LocalDate startDate = start.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate endDate = end.atZone(ZoneOffset.UTC).toLocalDate();
        if (!start.equals(startDate.atStartOfDay(ZoneOffset.UTC).toInstant())
                || startDate.getDayOfMonth() != 1
                || !end.equals(endDate.atStartOfDay(ZoneOffset.UTC).toInstant())
                || endDate.getDayOfMonth() != 1) {
            throw new IllegalArgumentException(
                    "Generic order-flow import currently requires whole UTC months");
        }
        List<Archive> result = new ArrayList<>();
        YearMonth month = YearMonth.from(startDate);
        YearMonth endMonth = YearMonth.from(endDate);
        while (month.isBefore(endMonth)) {
            String period = month.toString();
            Instant monthStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            result.add(new Archive(URI.create(ROOT + "/monthly/aggTrades/" + symbol + "/"
                    + symbol + "-aggTrades-" + period + ".zip"), monthStart, monthEnd));
            month = month.plusMonths(1);
        }
        return List.copyOf(result);
    }

    record Archive(URI uri, Instant start, Instant end) {
    }
}
