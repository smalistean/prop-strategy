package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.BookTickerSecond;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.PostgresBookTickerSecondRepository;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Backfills {@code binance_book_ticker_second} from Binance's daily bookTicker archives.
 *
 * <p>Writes into the same table the live collector feeds, deliberately: a fill study has to run
 * over one uniform series, and having history in a second shape would mean writing the analysis
 * twice and comparing two things that were built differently.
 *
 * <p>The archive only covers <b>2024-01-04 to 2024-03-30</b> — Binance stopped publishing
 * bookTicker after that. Since {@code binance_perp_agg_trade_minute} for BTCUSDC begins
 * 2024-02-01, roughly 59 days carry both taker flow and best bid/ask, and that overlap is the only
 * window in existence where a passive fill can be simulated against real quotes rather than
 * assumed.
 *
 * <p>A day holds about 7.2M quote updates (673 MB expanded for BTCUSDC), so rows are streamed out
 * of the zip and folded into seconds as they are read; nothing accumulates beyond the second being
 * built and one batch awaiting insert.
 */
public final class BookTickerArchiveImportApplication {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final String ROOT = "https://data.binance.vision/data/futures/um/daily/bookTicker";

    private BookTickerArchiveImportApplication() {
    }

    public static void main(String[] args) throws Exception {
        String symbol = System.getProperty("bookTickerSymbol", "BTCUSDC").trim().toUpperCase();
        LocalDate start = LocalDate.parse(System.getProperty("bookTickerStart", "2024-02-01"));
        LocalDate end = LocalDate.parse(System.getProperty("bookTickerEnd", "2024-03-30"));
        Path directory = Path.of(System.getProperty(
                "bookTickerArchiveDir", "data/book-ticker/" + symbol)).toAbsolutePath();
        boolean keep = Boolean.getBoolean("bookTickerKeepArchives");

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        PostgresBookTickerSecondRepository repository = new PostgresBookTickerSecondRepository(database);
        BinanceArchiveDownloader downloader = new BinanceArchiveDownloader();

        long began = System.nanoTime();
        long totalRows = 0;
        long totalSeconds = 0;
        int days = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            String name = "%s-bookTicker-%s.zip".formatted(symbol, day);
            URI uri = URI.create("%s/%s/%s".formatted(ROOT, symbol, name));
            BinanceArchiveDownloader.DownloadedArchive archive;
            try {
                archive = downloader.download(uri, directory);
            } catch (RuntimeException e) {
                System.out.printf("!! %s unavailable: %s%n", name, e.getMessage());
                continue;
            }
            DayResult result = ingest(archive.path(), symbol, repository);
            totalRows += result.rows();
            totalSeconds += result.seconds();
            days++;
            System.out.printf("[%d] %s  %,d updates -> %,d seconds  (%.1f MB)%n",
                    days, day, result.rows(), result.seconds(), archive.size() / 1e6);
            // 673 MB per expanded day makes retention the default nobody wants; the second-level
            // rollup is the artefact, and the zip can always be fetched again.
            if (!keep) {
                Files.deleteIfExists(archive.path());
            }
        }
        System.out.printf("DONE %s: %d days, %,d updates, %,d seconds in %s%n",
                symbol, days, totalRows, totalSeconds,
                Duration.ofNanos(System.nanoTime() - began).toMinutes() + " min");
    }

    private record DayResult(long rows, long seconds) {
    }

    private static DayResult ingest(Path archive, String symbol,
                                    PostgresBookTickerSecondRepository repository) throws Exception {
        long rows = 0;
        long seconds = 0;
        List<BookTickerSecond> batch = new ArrayList<>();
        Accumulator current = null;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry = zip.getNextEntry();
            while (entry != null && entry.isDirectory()) {
                entry = zip.getNextEntry();
            }
            if (entry == null) {
                return new DayResult(0, 0);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(nonClosing(zip), StandardCharsets.UTF_8), 1 << 20)) {
                String line = reader.readLine();
                if (line != null && line.startsWith("update_id")) {
                    line = reader.readLine();
                }
                while (line != null) {
                    Quote quote = parse(line);
                    line = reader.readLine();
                    if (quote == null) {
                        continue;
                    }
                    rows++;
                    long second = quote.eventMillis() / 1000L;
                    if (current == null || current.second != second) {
                        if (current != null) {
                            batch.add(current.toRow(symbol));
                            seconds++;
                        }
                        current = new Accumulator(second, quote.bid(), quote.ask());
                    }
                    current.add(quote);
                    if (batch.size() >= 2_000) {
                        repository.upsertAll(batch);
                        batch.clear();
                    }
                }
            }
        }
        if (current != null) {
            batch.add(current.toRow(symbol));
            seconds++;
        }
        repository.upsertAll(batch);
        return new DayResult(rows, seconds);
    }

    /** The reader must not close the ZipInputStream while further entries may exist. */
    private static InputStream nonClosing(InputStream delegate) {
        return new InputStream() {
            @Override public int read() throws java.io.IOException { return delegate.read(); }
            @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
                return delegate.read(b, off, len);
            }
            @Override public void close() { }
        };
    }

    private record Quote(BigDecimal bid, BigDecimal bidQty, BigDecimal ask,
                         BigDecimal askQty, long eventMillis) {
    }

    private static Quote parse(String line) {
        // update_id,best_bid_price,best_bid_qty,best_ask_price,best_ask_qty,transaction_time,event_time
        try {
            int a = line.indexOf(','); if (a < 0) return null;
            int b = line.indexOf(',', a + 1); if (b < 0) return null;
            int c = line.indexOf(',', b + 1); if (c < 0) return null;
            int d = line.indexOf(',', c + 1); if (d < 0) return null;
            int e = line.indexOf(',', d + 1); if (e < 0) return null;
            int f = line.indexOf(',', e + 1); if (f < 0) return null;
            BigDecimal bid = new BigDecimal(line.substring(a + 1, b));
            BigDecimal bidQty = new BigDecimal(line.substring(b + 1, c));
            BigDecimal ask = new BigDecimal(line.substring(c + 1, d));
            BigDecimal askQty = new BigDecimal(line.substring(d + 1, e));
            long eventMillis = Long.parseLong(line.substring(f + 1).trim());
            if (bid.signum() <= 0 || ask.signum() <= 0) {
                return null;
            }
            return new Quote(bid, bidQty, ask, askQty, eventMillis);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class Accumulator {
        private final long second;
        private final BigDecimal openBid;
        private final BigDecimal openAsk;
        private BigDecimal closeBid;
        private BigDecimal closeAsk;
        private BigDecimal minBid;
        private BigDecimal maxBid;
        private BigDecimal minAsk;
        private BigDecimal maxAsk;
        private BigDecimal closeBidQty = BigDecimal.ZERO;
        private BigDecimal closeAskQty = BigDecimal.ZERO;
        private BigDecimal spreadSum = BigDecimal.ZERO;
        private BigDecimal minSpread;
        private BigDecimal maxSpread;
        private int updates;

        private Accumulator(long second, BigDecimal bid, BigDecimal ask) {
            this.second = second;
            this.openBid = bid;
            this.openAsk = ask;
            this.minBid = bid;
            this.maxBid = bid;
            this.minAsk = ask;
            this.maxAsk = ask;
        }

        private void add(Quote quote) {
            closeBid = quote.bid();
            closeAsk = quote.ask();
            closeBidQty = quote.bidQty();
            closeAskQty = quote.askQty();
            minBid = minBid.min(quote.bid());
            maxBid = maxBid.max(quote.bid());
            minAsk = minAsk.min(quote.ask());
            maxAsk = maxAsk.max(quote.ask());
            BigDecimal mid = quote.bid().add(quote.ask()).divide(TWO, MC);
            BigDecimal spread = quote.ask().subtract(quote.bid()).divide(mid, MC).multiply(BPS, MC);
            spreadSum = spreadSum.add(spread);
            minSpread = minSpread == null ? spread : minSpread.min(spread);
            maxSpread = maxSpread == null ? spread : maxSpread.max(spread);
            updates++;
        }

        private BookTickerSecond toRow(String symbol) {
            return new BookTickerSecond(symbol, Instant.ofEpochSecond(second), updates,
                    openBid, openAsk, closeBid, closeAsk, minBid, maxBid, minAsk, maxAsk,
                    closeBidQty, closeAskQty,
                    spreadSum.divide(BigDecimal.valueOf(updates), MC), minSpread, maxSpread);
        }
    }
}
