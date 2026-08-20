package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Imports one representative snapshot per month from the Tardis Deribit options archives into
 * {@code deribit_option_quote}, the same table the live hourly recorder writes to.
 *
 * <h2>Why one snapshot, not the whole file</h2>
 * Each archive is a full day of tick-by-tick quote updates - up to several GB compressed for a recent
 * month - because Tardis publishes only the first day of every month. RESEARCH_OPTIONS.md's own
 * framing treats these as roughly independent MONTHLY observations (89 months far exceeds the ~14-day
 * decorrelation the strategy's autocorrelation measurement implies), not as a source of intraday path
 * data - the live recorder already covers that going forward. Importing every tick of every file would
 * cost orders of magnitude more time and storage for information the analysis does not use.
 *
 * <p>Instead: stream the file in order, keep only the latest row per instrument, and stop once the
 * exchange timestamp passes the target hour plus a grace window. Because Tardis captures are ordered
 * by exchange timestamp, this reconstructs the true order-book state "as of" the target time while
 * reading only the fraction of the file up to that point - the early exit is the entire point.
 *
 * <h2>Fields Tardis provides that the original schema (V10) did not store</h2>
 * bid_amount, ask_amount, bid_iv, ask_iv, delta, gamma, vega, theta, rho - added in V18. This data
 * cannot be re-fetched once the archives are gone, so nothing available is dropped on import.
 *
 * <h2>Usage</h2>
 * <pre>
 *   -DtardisDir=data/tardis/deribit          default
 *   -DtardisSnapshotHour=12                  UTC hour of day to snapshot, default noon
 * </pre>
 */
public final class TardisDeribitImportApplication {

    private static final Pattern FILE_DATE =
            Pattern.compile("deribit-options-(\\d{4}-\\d{2}-\\d{2})\\.csv\\.gz");
    private static final DateTimeFormatter FILE_DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    // Absorbs sub-second jitter in an exchange-timestamp-ordered stream without materially changing
    // how much of the file has to be read - see class javadoc.
    private static final long GRACE_MICROS = 5 * 60 * 1_000_000L;

    private static final String UPSERT = """
            INSERT INTO deribit_option_quote (
                snapshot_time, instrument_name, underlying, quote_currency, expiry_time, strike,
                option_type, bid_price, ask_price, bid_amount, ask_amount, bid_iv, ask_iv,
                mark_price, mark_iv, underlying_price, open_interest, delta, gamma, vega, theta, rho)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (snapshot_time, instrument_name) DO NOTHING
            """;

    private record Row(long timestampMicros, String symbol, String type, BigDecimal strike,
                       long expirationMicros, BigDecimal openInterest, BigDecimal bidPrice,
                       BigDecimal bidAmount, BigDecimal bidIv, BigDecimal askPrice,
                       BigDecimal askAmount, BigDecimal askIv, BigDecimal markPrice,
                       BigDecimal markIv, BigDecimal underlyingPrice, BigDecimal delta,
                       BigDecimal gamma, BigDecimal vega, BigDecimal theta, BigDecimal rho) { }

    private TardisDeribitImportApplication() {
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("tardisDir", "data/tardis/deribit"));
        int snapshotHour = Integer.getInteger("tardisSnapshotHour", 12);

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);

        File[] files = dir.listFiles((d, name) -> FILE_DATE.matcher(name).matches());
        if (files == null || files.length == 0) {
            throw new IllegalStateException("no tardis archives found in " + dir);
        }
        Arrays.sort(files);

        int totalRows = 0;
        long startAll = System.nanoTime();
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password())) {
            connection.setAutoCommit(false);
            for (File file : files) {
                Matcher m = FILE_DATE.matcher(file.getName());
                if (!m.matches()) {
                    continue;
                }
                LocalDate day = LocalDate.parse(m.group(1), FILE_DATE_FMT);
                Instant snapshot = day.atStartOfDay(ZoneOffset.UTC).plusHours(snapshotHour).toInstant();
                long targetMicros = snapshot.getEpochSecond() * 1_000_000L + snapshot.getNano() / 1_000L;

                long start = System.nanoTime();
                Map<String, Row> latest = readUpTo(file, targetMicros + GRACE_MICROS);
                int written = store(connection, snapshot, latest);
                connection.commit();
                totalRows += written;
                double seconds = (System.nanoTime() - start) / 1e9;
                System.out.printf("%-12s %-15s %,6d instruments, %,6d stored  (%.1fs)%n",
                        m.group(1), snapshot, latest.size(), written, seconds);
            }
        }
        System.out.printf("TARDIS IMPORT done: %,d rows across %d files, %.1f min%n",
                totalRows, files.length, (System.nanoTime() - startAll) / 60e9);
    }

    /**
     * Streams the gzipped archive in order, keeping the latest row per instrument until the target
     * (plus grace) is passed, then stops reading - this early exit is why a multi-GB file costs only
     * as much I/O as it takes to reach noon, not the whole day.
     */
    private static Map<String, Row> readUpTo(File file, long ceilingMicros) throws IOException {
        Map<String, Row> latest = new LinkedHashMap<>();
        try (var in = new GZIPInputStream(Files.newInputStream(file.toPath()), 1 << 20);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 20)) {
            String header = reader.readLine();
            if (header == null) {
                return latest;
            }
            String line;
            while ((line = reader.readLine()) != null) {
                Row row = parse(line);
                if (row == null) {
                    continue;
                }
                if (row.timestampMicros() > ceilingMicros) {
                    break;
                }
                latest.put(row.symbol(), row);
            }
        }
        return latest;
    }

    /** exchange,symbol,timestamp,local_timestamp,type,strike_price,expiration,open_interest,
     *  last_price,bid_price,bid_amount,bid_iv,ask_price,ask_amount,ask_iv,mark_price,mark_iv,
     *  underlying_index,underlying_price,delta,gamma,vega,theta,rho */
    private static Row parse(String line) {
        String[] f = line.split(",", -1);
        if (f.length < 24) {
            return null;
        }
        BigDecimal mark = dec(f[15]);
        if (mark == null) {
            return null;   // mark is the only price column guaranteed non-null; see live recorder
        }
        return new Row(Long.parseLong(f[2]), f[1], f[4], dec(f[5]), Long.parseLong(f[6]), dec(f[7]),
                dec(f[9]), dec(f[10]), dec(f[11]), dec(f[12]), dec(f[13]), dec(f[14]), mark, dec(f[16]),
                dec(f[18]), dec(f[19]), dec(f[20]), dec(f[21]), dec(f[22]), dec(f[23]));
    }

    private static BigDecimal dec(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int store(Connection connection, Instant snapshot, Map<String, Row> rows)
            throws SQLException {
        int written = 0;
        // Sorted so a partially-completed batch (a run interrupted mid-file) is easy to reason about
        // from the logs, not for correctness.
        try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            for (Row row : new TreeMap<>(rows).values()) {
                String[] parts = row.symbol().split("-");
                String underlying = parts[0];
                String quoteCurrency = underlying.endsWith("_USDC") ? "USDC" : underlying;
                statement.setObject(1, snapshot.atOffset(ZoneOffset.UTC));
                statement.setString(2, row.symbol());
                statement.setString(3, underlying);
                statement.setString(4, quoteCurrency);
                statement.setObject(5, Instant.ofEpochSecond(0, row.expirationMicros() * 1_000L)
                        .atOffset(ZoneOffset.UTC));
                statement.setBigDecimal(6, row.strike());
                statement.setString(7, row.type().equalsIgnoreCase("put") ? "P" : "C");
                setDecimal(statement, 8, row.bidPrice());
                setDecimal(statement, 9, row.askPrice());
                setDecimal(statement, 10, row.bidAmount());
                setDecimal(statement, 11, row.askAmount());
                setDecimal(statement, 12, row.bidIv());
                setDecimal(statement, 13, row.askIv());
                statement.setBigDecimal(14, row.markPrice());
                setDecimal(statement, 15, row.markIv());
                setDecimal(statement, 16, row.underlyingPrice());
                setDecimal(statement, 17, row.openInterest());
                setDecimal(statement, 18, row.delta());
                setDecimal(statement, 19, row.gamma());
                setDecimal(statement, 20, row.vega());
                setDecimal(statement, 21, row.theta());
                setDecimal(statement, 22, row.rho());
                statement.addBatch();
                written++;
            }
            statement.executeBatch();
        }
        return written;
    }

    private static void setDecimal(PreparedStatement statement, int index, BigDecimal value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }
}
