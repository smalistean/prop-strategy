package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Freshness;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Instrument;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.IntervalSource;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingObservation;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingVenueWatermark;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.SettledVenueWatermark;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshotSource.FreshnessPolicy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PostgreSQL funding source for an XVF shadow decision.
 *
 * <p>Pending and settled facts are deliberately queried separately. Pending rows are venue estimates
 * for a future stamp; settled rows in {@code perp_funding_all} are payments that actually happened.
 * Joining or summing the two would recreate the double-counting class this schema was designed to
 * prevent.
 */
public final class PostgresXvfFundingSnapshotSource implements XvfFundingSnapshotSource {

    private static final String PENDING_HISTORY_SQL = """
            WITH causal AS (
              SELECT venue, venue_symbol, observed_hour, observed_at, target_stamp, funding_rate,
                     row_number() OVER (
                         PARTITION BY venue, venue_symbol
                         ORDER BY observed_hour DESC, observed_at DESC) AS history_rank
              FROM venue_funding_observation
              WHERE venue = ANY (?)
                AND observed_at <= ?
                AND observed_hour >= ? - interval '6 hours'
            ), recent AS (
              SELECT venue, venue_symbol, observed_hour, observed_at, target_stamp, funding_rate
              FROM causal
              WHERE history_rank <= 4
            )
            SELECT recent.venue,
                   recent.venue_symbol,
                   recent.observed_hour,
                   recent.observed_at,
                   recent.target_stamp,
                   recent.funding_rate,
                   previous.target_stamp AS previous_target_stamp
            FROM recent
            LEFT JOIN LATERAL (
              SELECT older.target_stamp
              FROM venue_funding_observation older
              WHERE older.venue = recent.venue
                AND older.venue_symbol = recent.venue_symbol
                AND older.observed_at <= recent.observed_at
                AND recent.target_stamp IS NOT NULL
                AND older.target_stamp < recent.target_stamp
              GROUP BY older.target_stamp
              ORDER BY older.target_stamp DESC
              LIMIT 1
            ) previous ON true
            ORDER BY recent.venue, recent.venue_symbol,
                     recent.observed_hour, recent.observed_at
            """;

    private static final String SETTLED_WATERMARK_SQL = """
            SELECT venue, max(funding_time) AS latest_funding_time
            FROM perp_funding_all
            WHERE venue = ANY (?)
              AND funding_time <= ?
            GROUP BY venue
            """;

    private final DatabaseConfig database;
    private final List<String> activeVenues;

    public PostgresXvfFundingSnapshotSource(DatabaseConfig database) {
        this.database = Objects.requireNonNull(database, "database");
        this.activeVenues = List.copyOf(Arrays.asList(XvfConfig.VENUES.clone()));
    }

    @Override
    public XvfFundingSnapshot read(Instant cutoffUtc, FreshnessPolicy freshnessPolicy) {
        Objects.requireNonNull(cutoffUtc, "cutoffUtc");
        Objects.requireNonNull(freshnessPolicy, "freshnessPolicy");

        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password())) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                Map<Instrument, List<PendingObservation>> pendingHistory = readPendingHistory(
                        connection, cutoffUtc, freshnessPolicy.maximumPendingAge());
                Map<Instrument, PendingObservation> pending = latestPending(pendingHistory);
                List<SettledVenueWatermark> settled = readSettledWatermarks(
                        connection, cutoffUtc, freshnessPolicy.maximumSettledAge());
                List<PendingVenueWatermark> pendingWatermarks = pendingWatermarks(pending, cutoffUtc);
                connection.commit();
                return new XvfFundingSnapshot(
                        cutoffUtc, pending, pendingHistory, pendingWatermarks, settled);
            } catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read XVF funding snapshot", e);
        }
    }

    private Map<Instrument, List<PendingObservation>> readPendingHistory(
            Connection connection, Instant cutoffUtc, Duration maximumAge) throws SQLException {
        Map<Instrument, List<PendingObservation>> observations = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(PENDING_HISTORY_SQL)) {
            java.sql.Array venues = connection.createArrayOf("text", activeVenues.toArray(String[]::new));
            try {
                statement.setArray(1, venues);
                statement.setObject(2, utc(cutoffUtc));
                statement.setObject(3, utc(cutoffUtc));
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        Instrument instrument = new Instrument(
                                results.getString("venue"), results.getString("venue_symbol"));
                        Instant observedHour = instant(results, "observed_hour");
                        Instant observedAt = instant(results, "observed_at");
                        Instant targetStamp = nullableInstant(results, "target_stamp");
                        Instant previousTargetStamp = nullableInstant(results, "previous_target_stamp");
                        Integer intervalHours = inferWholeHours(previousTargetStamp, targetStamp);
                        observations.computeIfAbsent(instrument, ignored -> new ArrayList<>()).add(
                                new PendingObservation(
                                        instrument,
                                        results.getBigDecimal("funding_rate"),
                                        observedHour,
                                        observedAt,
                                        targetStamp,
                                        intervalHours,
                                        intervalHours == null
                                                ? IntervalSource.UNKNOWN
                                                : IntervalSource.TARGET_STAMP_DELTA,
                                        freshness(observedAt, cutoffUtc, maximumAge)));
                    }
                }
            } finally {
                venues.free();
            }
        }
        return observations;
    }

    private static Map<Instrument, PendingObservation> latestPending(
            Map<Instrument, List<PendingObservation>> histories) {
        Map<Instrument, PendingObservation> latest = new LinkedHashMap<>();
        histories.forEach((instrument, observations) -> {
            if (observations.isEmpty()) {
                throw new IllegalStateException("Pending history cannot be empty");
            }
            latest.put(instrument, observations.getLast());
        });
        return latest;
    }

    private List<PendingVenueWatermark> pendingWatermarks(
            Map<Instrument, PendingObservation> pending, Instant cutoffUtc) {
        List<PendingVenueWatermark> out = new ArrayList<>();
        for (String venue : activeVenues) {
            List<PendingObservation> venueRows = pending.values().stream()
                    .filter(row -> venue.equals(row.instrument().venue()))
                    .toList();
            if (venueRows.isEmpty()) {
                out.add(new PendingVenueWatermark(
                        venue, null, 0, 0, 0, Freshness.MISSING));
                continue;
            }
            Instant latest = venueRows.stream().map(PendingObservation::observedAt)
                    .max(Comparator.naturalOrder()).orElseThrow();
            int fresh = (int) venueRows.stream()
                    .filter(row -> row.freshness() == Freshness.FRESH).count();
            int stale = venueRows.size() - fresh;
            Freshness venueFreshness = venueRows.stream()
                    .filter(row -> row.observedAt().equals(latest))
                    .map(PendingObservation::freshness)
                    .findFirst()
                    .orElseThrow();
            if (latest.isAfter(cutoffUtc)) {
                throw new IllegalStateException("Pending watermark is after cutoff");
            }
            out.add(new PendingVenueWatermark(
                    venue, latest, venueRows.size(), fresh, stale, venueFreshness));
        }
        return out;
    }

    private List<SettledVenueWatermark> readSettledWatermarks(
            Connection connection, Instant cutoffUtc, Duration maximumAge) throws SQLException {
        Map<String, Instant> found = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(SETTLED_WATERMARK_SQL)) {
            java.sql.Array venues = connection.createArrayOf("text", activeVenues.toArray(String[]::new));
            try {
                statement.setArray(1, venues);
                statement.setObject(2, utc(cutoffUtc));
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        found.put(results.getString("venue"),
                                instant(results, "latest_funding_time"));
                    }
                }
            } finally {
                venues.free();
            }
        }

        List<SettledVenueWatermark> out = new ArrayList<>();
        for (String venue : activeVenues) {
            Instant latest = found.get(venue);
            out.add(new SettledVenueWatermark(
                    venue, latest, freshness(latest, cutoffUtc, maximumAge)));
        }
        return out;
    }

    private static Freshness freshness(Instant timestamp, Instant cutoffUtc, Duration maximumAge) {
        if (timestamp == null) {
            return Freshness.MISSING;
        }
        if (timestamp.isAfter(cutoffUtc)) {
            throw new IllegalStateException("A source timestamp cannot be after the cutoff");
        }
        return Duration.between(timestamp, cutoffUtc).compareTo(maximumAge) <= 0
                ? Freshness.FRESH : Freshness.STALE;
    }

    private static Integer inferWholeHours(Instant previousTargetStamp, Instant targetStamp) {
        if (previousTargetStamp == null || targetStamp == null) {
            return null;
        }
        Duration delta = Duration.between(previousTargetStamp, targetStamp);
        long seconds = delta.getSeconds();
        if (seconds <= 0 || delta.getNano() != 0 || seconds % 3_600 != 0) {
            return null;
        }
        long hours = seconds / 3_600;
        return hours <= Integer.MAX_VALUE ? (int) hours : null;
    }

    private static Instant instant(ResultSet results, String column) throws SQLException {
        return results.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet results, String column) throws SQLException {
        OffsetDateTime value = results.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
