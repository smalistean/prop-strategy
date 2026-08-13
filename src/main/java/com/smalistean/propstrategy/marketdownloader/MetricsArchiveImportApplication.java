package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.PostgresMetricSnapshotRepository;
import com.smalistean.propstrategy.database.PostgresMetricSnapshotRepository.Snapshot;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;

/**
 * Imports Binance futures "metrics" daily archives: open interest and positioning at 5-minute
 * resolution.
 *
 * <p>Why not the REST API: it serves roughly the last 30 days, which is why
 * {@code binance_perp_open_interest_statistic} holds exactly one month. These archives reach back to
 * 2021-10 for BTCUSDT and 2021-12 for the other symbols.
 *
 * <p>Why not {@link BinanceArchiveDownloader}: it fetches a CHECKSUM alongside every archive, which
 * is right for multi-gigabyte aggregate-trade files but doubles the request count for ~11 KB daily
 * files. Roughly 26,000 archives are needed here, so this streams each zip straight from the
 * response into the parser without touching disk.
 *
 * <p>Missing days are expected rather than exceptional - a symbol lists partway through the range,
 * or Binance simply has no file. A 404 is recorded as {@code MISSING} so a resumed run does not
 * retry it forever, and any other failure is left unrecorded so it is retried.
 */
public final class MetricsArchiveImportApplication {

    private static final String ROOT = "https://data.binance.vision/data/futures/um/daily/metrics";
    private static final String DEFAULT_SYMBOLS =
            "BTCUSDT,ETHUSDT,SOLUSDT,XRPUSDT,ADAUSDT,AVAXUSDT,BNBUSDT,DOGEUSDT,ETCUSDT,"
            + "DOTUSDT,LINKUSDT,LTCUSDT,TRXUSDT,AAVEUSDT,BCHUSDT";

    private MetricsArchiveImportApplication() {
    }

    public static void main(String[] args) throws Exception {
        List<String> symbols = List.of(System.getProperty("metricsSymbols", DEFAULT_SYMBOLS)
                .trim().toUpperCase().split(","));
        LocalDate start = LocalDate.parse(System.getProperty("metricsStart", "2021-10-01"));
        LocalDate end = LocalDate.parse(System.getProperty("metricsEnd", "2026-08-10"));
        int threads = Integer.getInteger("metricsThreads", 6);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("metricsStart must precede metricsEnd");
        }

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        PostgresMetricSnapshotRepository repository = new PostgresMetricSnapshotRepository(database);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        long began = System.nanoTime();
        AtomicInteger rowsTotal = new AtomicInteger();
        AtomicInteger missingTotal = new AtomicInteger();
        for (String symbol : symbols) {
            Set<LocalDate> done = repository.importedDays(symbol);
            List<LocalDate> pending = new ArrayList<>();
            for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
                if (!done.contains(day)) {
                    pending.add(day);
                }
            }
            if (pending.isEmpty()) {
                System.out.printf("%s: already complete (%d days recorded)%n", symbol, done.size());
                continue;
            }
            long symbolBegan = System.nanoTime();
            AtomicInteger rows = new AtomicInteger();
            AtomicInteger missing = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (LocalDate day : pending) {
                pool.submit(() -> {
                    try {
                        List<Snapshot> parsed = fetch(client, symbol, day);
                        if (parsed == null) {
                            repository.importDay(symbol, day, List.of(), "MISSING");
                            missing.incrementAndGet();
                            return;
                        }
                        repository.importDay(symbol, day, parsed, "OK");
                        rows.addAndGet(parsed.size());
                    } catch (RuntimeException e) {
                        // Left unrecorded on purpose: an unrecorded day is retried by the next run,
                        // whereas marking it would bake a transient network error into the record.
                        failed.incrementAndGet();
                    }
                });
            }
            pool.shutdown();
            if (!pool.awaitTermination(6, TimeUnit.HOURS)) {
                throw new IllegalStateException("Timed out importing " + symbol);
            }
            rowsTotal.addAndGet(rows.get());
            missingTotal.addAndGet(missing.get());
            System.out.printf("%s: %,d rows from %d days, %d missing, %d failed, %ds%n",
                    symbol, rows.get(), pending.size() - missing.get() - failed.get(),
                    missing.get(), failed.get(),
                    (System.nanoTime() - symbolBegan) / 1_000_000_000L);
        }
        System.out.printf("METRICS IMPORT DONE: %,d rows, %d missing days, %d minutes%n",
                rowsTotal.get(), missingTotal.get(),
                (System.nanoTime() - began) / 60_000_000_000L);
    }

    /** Returns parsed rows, or null when Binance has no archive for that day. */
    private static List<Snapshot> fetch(HttpClient client, String symbol, LocalDate day) {
        URI uri = URI.create("%s/%s/%s-metrics-%s.zip".formatted(ROOT, symbol, symbol, day));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " for " + uri);
            }
            return parse(symbol, response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to fetch " + uri, e);
        }
    }

    private static List<Snapshot> parse(String symbol, byte[] zipped) {
        List<Snapshot> rows = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped))) {
            if (zip.getNextEntry() == null) {
                throw new IllegalStateException("Empty metrics archive for " + symbol);
            }
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(zip));
            String line = reader.readLine();
            if (line == null) {
                return rows;
            }
            if (!line.startsWith("create_time")) {
                rows.add(row(symbol, line.split(",", -1)));
            }
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rows.add(row(symbol, line.split(",", -1)));
            }
            return rows;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read metrics archive for " + symbol, e);
        }
    }

    private static Snapshot row(String symbol, String[] f) {
        if (f.length < 8) {
            throw new IllegalStateException("Unexpected metrics row width " + f.length);
        }
        // "2024-06-03 00:00:00", stated by Binance in UTC and carrying no offset of its own.
        var time = LocalDateTime.parse(f[0].trim().replace(' ', 'T')).toInstant(ZoneOffset.UTC);
        return new Snapshot(symbol, time, decimal(f[2]), decimal(f[3]), decimal(f[4]),
                decimal(f[5]), decimal(f[6]), decimal(f[7]));
    }

    /**
     * Older archives write an absent value as a quoted empty string rather than an empty field:
     * {@code ...,71805.461,3379061594.796,"","","",""}. Treating those two quote characters as a
     * number fails the whole day, which is what produced the contiguous run of failures across
     * every date before roughly 2022.
     */
    private static BigDecimal decimal(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.isEmpty() ? null : new BigDecimal(trimmed);
    }
}
