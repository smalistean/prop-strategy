package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresKlineRepository;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

/**
 * Imports Binance futures kline monthly archives for a wide symbol universe.
 *
 * <p>Built for the cross-sectional momentum test ({@code XSMOM_PREREGISTRATION.md}), which needs
 * price history for every USDT perpetual that ever traded rather than the fifteen already loaded.
 *
 * <p><b>Why the archive rather than the REST API.</b> {@code fapi/v1/exchangeInfo} lists 654 USDT
 * perpetuals, of which 527 still trade. The archive holds <b>832</b>, including fully delisted names
 * such as FTTUSDT and LUNAUSDT. Building a universe from the live list would define it by which
 * coins survived to today - unknowable in advance, and corrupting to both sides of a long/short
 * book. Listing and delisting dates then come from which archives exist, with no lookahead.
 *
 * <p>Resumption is by querying which months already hold rows, one grouped query per symbol, so a
 * re-run downloads only what is missing and no extra progress table is needed.
 */
public final class KlineArchiveImportApplication {

    private static final String LISTING = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision";
    /**
     * Archive segment: {@code futures/um} for perpetuals, {@code spot} for the spot leg. Spot is
     * needed by the cash-and-carry test, which hedges a short perp with long spot on the same asset.
     * Spot symbols are stored with a suffix so the two never collide in {@code futures_kline}.
     */
    private static String segment() { return System.getProperty("klineMarket", "futures/um"); }
    private static String table() { return "spot".equals(segment()) ? "spot_kline" : "futures_kline"; }
    private static final String ROOT = "https://data.binance.vision";
    private static final Pattern KEY = Pattern.compile("<Key>([^<]+)</Key>");
    private static final Pattern MONTH = Pattern.compile("-(\\d{4}-\\d{2})\\.zip$");

    private KlineArchiveImportApplication() {
    }

    public static void main(String[] args) throws Exception {
        String interval = System.getProperty("klineInterval", "1h");
        String quote = System.getProperty("klineQuote", "USDT");
        int threads = Integer.getInteger("klineThreads", 6);
        int symbolLimit = Integer.getInteger("klineSymbolLimit", Integer.MAX_VALUE);

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        PostgresKlineRepository repository = new PostgresKlineRepository(database);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        List<String> symbols = listSymbols(client, interval).stream()
                .filter(s -> s.endsWith(quote))
                .sorted()
                .limit(symbolLimit)
                .toList();
        System.out.printf("universe: %d %s perpetuals in the archive%n", symbols.size(), quote);

        long began = System.nanoTime();
        AtomicInteger rowsTotal = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (String symbol : symbols) {
            pool.submit(() -> {
                try {
                    Set<String> present = existingMonths(database, symbol, interval);
                    List<String> months = listMonths(client, symbol, interval);
                    int rows = 0;
                    for (String month : months) {
                        if (present.contains(month)) {
                            continue;
                        }
                        List<Kline> klines = fetch(client, symbol, interval, month);
                        if (klines != null && !klines.isEmpty()) {
                            rows += repository.upsertAll(symbol, interval, klines, table());
                        }
                    }
                    rowsTotal.addAndGet(rows);
                    int n = done.incrementAndGet();
                    if (rows > 0 || n % 25 == 0) {
                        System.out.printf("[%d/%d] %-16s %,7d new rows (%d months available)%n",
                                n, symbols.size(), symbol, rows, months.size());
                    }
                } catch (RuntimeException e) {
                    // Left unrecorded so the next run retries it, rather than baking a transient
                    // network failure into the dataset as an absence.
                    System.out.printf("!! %s failed: %s%n", symbol, e.getMessage());
                    done.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(12, TimeUnit.HOURS)) {
            throw new IllegalStateException("Timed out importing klines");
        }
        System.out.printf("KLINE IMPORT DONE: %,d rows across %d symbols in %d minutes%n",
                rowsTotal.get(), symbols.size(), (System.nanoTime() - began) / 60_000_000_000L);
    }

    /**
     * Enumerates every symbol directory in the segment, following S3 pagination. The spot listing
     * exceeds the 1,000-key page limit, so a single request silently returns a fraction of the
     * universe - which would quietly shrink the tradeable set rather than fail.
     */
    private static List<String> listSymbols(HttpClient client, String interval) {
        Set<String> symbols = new HashSet<>();
        String prefix = "data/" + segment() + "/daily/klines/";
        Pattern directory = Pattern.compile("<Prefix>" + Pattern.quote(prefix) + "([^/]+)/</Prefix>");
        String marker = "";
        while (true) {
            String xml = get(client, LISTING + "?delimiter=/&prefix=" + prefix
                    + (marker.isEmpty() ? "" : "&marker=" + java.net.URLEncoder.encode(
                            marker, java.nio.charset.StandardCharsets.UTF_8)));
            Matcher matcher = directory.matcher(xml);
            String last = null;
            while (matcher.find()) {
                symbols.add(matcher.group(1));
                last = matcher.group(1);
            }
            Matcher truncated = Pattern.compile("<IsTruncated>([^<]+)</IsTruncated>").matcher(xml);
            if (!truncated.find() || !"true".equals(truncated.group(1)) || last == null) {
                break;
            }
            marker = prefix + last + "/";
        }
        return new ArrayList<>(symbols);
    }

    /** Months Binance actually publishes for this symbol - this is what defines its trading life. */
    private static List<String> listMonths(HttpClient client, String symbol, String interval) {
        String prefix = "data/" + segment() + "/monthly/klines/%s/%s/".formatted(symbol, interval);
        String xml = get(client, LISTING + "?delimiter=/&prefix=" + prefix);
        List<String> months = new ArrayList<>();
        Matcher matcher = KEY.matcher(xml);
        while (matcher.find()) {
            Matcher month = MONTH.matcher(matcher.group(1));
            if (month.find()) {
                months.add(month.group(1));
            }
        }
        months.sort(null);
        return months;
    }

    private static Set<String> existingMonths(DatabaseConfig database, String symbol, String interval) {
        Set<String> months = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT DISTINCT to_char(open_time AT TIME ZONE 'UTC', 'YYYY-MM') "
                             + "FROM " + table() + " WHERE symbol = ? AND interval = ?")) {
            statement.setString(1, symbol);
            statement.setString(2, interval);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    months.add(results.getString(1));
                }
            }
            return months;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to read existing months for " + symbol, e);
        }
    }

    private static List<Kline> fetch(HttpClient client, String symbol, String interval, String month) {
        String url = "%s/data/%s/monthly/klines/%s/%s/%s-%s-%s.zip"
                .formatted(ROOT, segment(), symbol, interval, symbol, interval, month);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120)).GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
            }
            return parse(response.body());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to fetch " + url, e);
        }
    }

    private static List<Kline> parse(byte[] zipped) {
        List<Kline> klines = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped))) {
            if (zip.getNextEntry() == null) {
                return klines;
            }
            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(zip));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("open_time")) {
                    continue; // older archives omit the header entirely; newer ones carry it
                }
                String[] f = line.split(",", -1);
                if (f.length < 11) {
                    continue;
                }
                klines.add(new Kline(
                        epoch(f[0]), new BigDecimal(f[1]), new BigDecimal(f[2]), new BigDecimal(f[3]),
                        new BigDecimal(f[4]), new BigDecimal(f[5]), epoch(f[6]),
                        new BigDecimal(f[7]), (int) Double.parseDouble(f[8]),
                        new BigDecimal(f[9]), new BigDecimal(f[10])));
            }
            return klines;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to read kline archive", e);
        }
    }

    /** Binance has shipped both millisecond and microsecond stamps; pick by magnitude, not by date. */
    private static Instant epoch(String raw) {
        long value = Long.parseLong(raw.trim());
        return value > 100_000_000_000_000L
                ? Instant.ofEpochMilli(value / 1000)
                : Instant.ofEpochMilli(value);
    }

    private static String get(HttpClient client, String url) {
        try {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Failed to list " + url, e);
        }
    }
}
