package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;

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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Imports Binance funding-rate monthly archives for the wide symbol universe.
 *
 * <p>Needed because {@code XSMOM_PREREGISTRATION.md} charges funding on real rates: a market-neutral
 * book is short roughly forty alt perpetuals at any time, where funding is sometimes received and
 * sometimes paid, and at three payments a day that is not a rounding error. The database previously
 * held funding for only the sixteen symbols of the old universe.
 *
 * <p>Archive layout is {@code calc_time,funding_interval_hours,last_funding_rate}, distinct from the
 * REST shape, and the REST endpoint returns only 500 rows per call - roughly six months - which would
 * mean thousands of paged calls across 833 symbols.
 */
public final class FundingArchiveImportApplication {

    private static final String LISTING = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision";
    private static final String ROOT = "https://data.binance.vision";
    private static final Pattern KEY = Pattern.compile("<Key>([^<]+)</Key>");
    private static final Pattern MONTH = Pattern.compile("-(\\d{4}-\\d{2})\\.zip$");
    private static final String UPSERT = """
            INSERT INTO futures_funding_rate (symbol, funding_time, rate_type, funding_rate)
            VALUES (?, ?, 'ARCHIVE', ?)
            ON CONFLICT (symbol, funding_time, rate_type)
            DO UPDATE SET funding_rate = EXCLUDED.funding_rate, updated_at = NOW()
            """;

    private FundingArchiveImportApplication() {
    }

    public static void main(String[] args) throws Exception {
        String quote = System.getProperty("fundingQuote", "USDT");
        int threads = Integer.getInteger("fundingThreads", 8);
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();

        List<String> symbols = new ArrayList<>(new HashSet<>(listSymbols(client)));
        symbols.removeIf(s -> !s.endsWith(quote));
        symbols.sort(null);
        System.out.printf("universe: %d %s perpetuals%n", symbols.size(), quote);

        long began = System.nanoTime();
        AtomicInteger rowsTotal = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (String symbol : symbols) {
            pool.submit(() -> {
                try {
                    Set<String> present = existingMonths(database, symbol);
                    int rows = 0;
                    for (String month : listMonths(client, symbol)) {
                        if (present.contains(month)) continue;
                        rows += store(database, symbol, fetch(client, symbol, month));
                    }
                    rowsTotal.addAndGet(rows);
                    int n = done.incrementAndGet();
                    if (n % 100 == 0) {
                        System.out.printf("[%d/%d] %,d rows so far%n", n, symbols.size(), rowsTotal.get());
                    }
                } catch (RuntimeException e) {
                    System.out.printf("!! %s failed: %s%n", symbol, e.getMessage());
                    done.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        if (!pool.awaitTermination(12, TimeUnit.HOURS)) {
            throw new IllegalStateException("Timed out importing funding");
        }
        System.out.printf("FUNDING IMPORT DONE: %,d rows, %d minutes%n",
                rowsTotal.get(), (System.nanoTime() - began) / 60_000_000_000L);
    }

    private record Payment(Instant time, BigDecimal rate) { }

    private static int store(DatabaseConfig database, String symbol, List<Payment> payments) {
        if (payments == null || payments.isEmpty()) return 0;
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            for (Payment payment : payments) {
                statement.setString(1, symbol);
                statement.setObject(2, OffsetDateTime.ofInstant(payment.time(), ZoneOffset.UTC));
                statement.setBigDecimal(3, payment.rate());
                statement.addBatch();
            }
            statement.executeBatch();
            return payments.size();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to store funding for " + symbol, e);
        }
    }

    private static Set<String> existingMonths(DatabaseConfig database, String symbol) {
        Set<String> months = new HashSet<>();
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT DISTINCT to_char(funding_time AT TIME ZONE 'UTC','YYYY-MM') "
                             + "FROM futures_funding_rate WHERE symbol = ? AND rate_type = 'ARCHIVE'")) {
            statement.setString(1, symbol);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) months.add(results.getString(1));
            }
            return months;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Failed to read funding months for " + symbol, e);
        }
    }

    private static List<String> listSymbols(HttpClient client) {
        String xml = get(client, LISTING + "?delimiter=/&prefix=data/futures/um/monthly/fundingRate/");
        List<String> symbols = new ArrayList<>();
        Matcher matcher = Pattern.compile(
                "<Prefix>data/futures/um/monthly/fundingRate/([^/]+)/</Prefix>").matcher(xml);
        while (matcher.find()) symbols.add(matcher.group(1));
        return symbols;
    }

    private static List<String> listMonths(HttpClient client, String symbol) {
        String xml = get(client, LISTING
                + "?delimiter=/&prefix=data/futures/um/monthly/fundingRate/" + symbol + "/");
        List<String> months = new ArrayList<>();
        Matcher matcher = KEY.matcher(xml);
        while (matcher.find()) {
            Matcher month = MONTH.matcher(matcher.group(1));
            if (month.find()) months.add(month.group(1));
        }
        months.sort(null);
        return months;
    }

    private static List<Payment> fetch(HttpClient client, String symbol, String month) {
        String url = "%s/data/futures/um/monthly/fundingRate/%s/%s-fundingRate-%s.zip"
                .formatted(ROOT, symbol, symbol, month);
        try {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 404) return null;
            if (response.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + response.statusCode() + " for " + url);
            }
            List<Payment> payments = new ArrayList<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(response.body()))) {
                if (zip.getNextEntry() == null) return payments;
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(zip));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || line.startsWith("calc_time")) continue;
                    String[] f = line.split(",", -1);
                    if (f.length < 3 || f[2].isBlank()) continue;
                    long stamp = Long.parseLong(f[0].trim());
                    payments.add(new Payment(
                            Instant.ofEpochMilli(stamp > 100_000_000_000_000L ? stamp / 1000 : stamp),
                            new BigDecimal(f[2].trim())));
                }
            }
            return payments;
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to fetch " + url, e);
        }
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
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to list " + url, e);
        }
    }
}
