package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Candidate;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.CaptureStatus;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ExpectedNet;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Pair;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.PairType;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Ranks;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Route;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ScoreStatus;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.SignalScore;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Atomic, insert-only persistence for immutable XVF signal audit runs. */
public final class PostgresXvfSignalRepository {

    private static final String INSERT_RUN = """
            INSERT INTO xvf_signal_run (
              signal_run_id, snapshot_schema_version, scheduled_decision_at, cutoff_utc,
              capture_started_at, capture_ended_at, production_date, production_zone,
              generated_at, scheduled_attempt_id, code_revision, strategy_version,
              configuration_hash, configuration_snapshot, settled_funding_watermarks,
              pending_funding_watermarks, venue_state_snapshot, capital_usd, candidate_count,
              data_issues, capture_status, failure_code, failure_detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, CAST(? AS jsonb), ?, ?, ?)
            """;

    private static final String INSERT_CANDIDATE = """
            INSERT INTO xvf_signal_candidate (
              signal_run_id, evaluation_order, gross_rank, base, pair_type,
              short_venue, short_venue_symbol, long_venue, long_venue_symbol,
              baseline_book_rank, shadow_book_rank, raw_spread_annual_pct,
              eligible_yesterday, stale_discount_factor, adjusted_spread_annual_pct,
              pending_funding_fresh, thin_leg_weekly_quote_volume_usd,
              maker_venue, taker_venue, planned_hold_hours,
              pending_funding_spread_bps, entry_basis_bps, expected_funding_bps,
              expected_basis_pnl_bps, expected_entry_fee_bps, expected_exit_fee_bps,
              expected_slippage_bps, risk_penalty_bps, expected_net_bps,
              requested_leg_notional_usd, short_leg_snapshot, long_leg_snapshot,
              score_components, gate_results, score_status, decision_reasons)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb), ?, CAST(? AS jsonb))
            """;

    private static final String SELECT_RUN = """
            SELECT snapshot_schema_version, scheduled_decision_at, cutoff_utc,
                   capture_started_at, capture_ended_at, production_date, production_zone,
                   generated_at, scheduled_attempt_id, code_revision, strategy_version,
                   configuration_hash, configuration_snapshot::text, settled_funding_watermarks::text,
                   pending_funding_watermarks::text, venue_state_snapshot::text, capital_usd,
                   candidate_count, data_issues::text, capture_status, failure_code, failure_detail
            FROM xvf_signal_run
            WHERE signal_run_id = ?
            """;

    private static final String SELECT_CANDIDATES = """
            SELECT evaluation_order, gross_rank, base, pair_type,
                   short_venue, short_venue_symbol, long_venue, long_venue_symbol,
                   baseline_book_rank, shadow_book_rank, raw_spread_annual_pct,
                   eligible_yesterday, stale_discount_factor, adjusted_spread_annual_pct,
                   pending_funding_fresh, thin_leg_weekly_quote_volume_usd,
                   maker_venue, taker_venue, planned_hold_hours,
                   pending_funding_spread_bps, entry_basis_bps, expected_funding_bps,
                   expected_basis_pnl_bps, expected_entry_fee_bps, expected_exit_fee_bps,
                   expected_slippage_bps, risk_penalty_bps, expected_net_bps,
                   requested_leg_notional_usd, short_leg_snapshot::text,
                   long_leg_snapshot::text, score_components::text, gate_results::text,
                   score_status, decision_reasons::text
            FROM xvf_signal_candidate
            WHERE signal_run_id = ?
            ORDER BY evaluation_order
            """;

    private final DatabaseConfig config;

    public PostgresXvfSignalRepository(DatabaseConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Inserts the parent and every candidate in one transaction.
     *
     * <p>There is intentionally no conflict clause. A duplicate UUID or candidate is an audit
     * failure, not an invitation to overwrite the earlier decision.
     */
    public void insert(XvfSignalRun run) {
        Objects.requireNonNull(run, "run");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                insertRun(connection, run);
                insertCandidates(connection, run.signalRunId(), run.candidates());
                connection.commit();
            } catch (SQLException e) {
                rollbackPreserving(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert XVF signal run " + run.signalRunId(), e);
        }
    }

    /** Returns a stable, evaluation-order reconstruction of one immutable run. */
    public Optional<XvfSignalRun> findById(UUID signalRunId) {
        Objects.requireNonNull(signalRunId, "signalRunId");
        try (Connection connection = open()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(SELECT_RUN)) {
                statement.setObject(1, signalRunId);
                try (ResultSet results = statement.executeQuery()) {
                    if (!results.next()) {
                        connection.commit();
                        return Optional.empty();
                    }
                    int candidateCount = results.getInt("candidate_count");
                    List<Candidate> candidates = readCandidates(connection, signalRunId);
                    if (candidates.size() != candidateCount) {
                        throw new SQLException("XVF signal run " + signalRunId + " declares "
                                + candidateCount + " candidates but reads " + candidates.size());
                    }
                    XvfSignalRun run = new XvfSignalRun(
                            signalRunId,
                            results.getShort("snapshot_schema_version"),
                            results.getObject("scheduled_decision_at", OffsetDateTime.class).toInstant(),
                            results.getObject("cutoff_utc", OffsetDateTime.class).toInstant(),
                            results.getObject("capture_started_at", OffsetDateTime.class).toInstant(),
                            results.getObject("capture_ended_at", OffsetDateTime.class).toInstant(),
                            results.getObject("production_date", java.time.LocalDate.class),
                            ZoneId.of(results.getString("production_zone")),
                            results.getObject("generated_at", OffsetDateTime.class).toInstant(),
                            results.getString("scheduled_attempt_id"),
                            results.getString("code_revision"),
                            results.getString("strategy_version"),
                            results.getString("configuration_hash"),
                            JsonDocument.object(results.getString("configuration_snapshot")),
                            JsonDocument.object(results.getString("settled_funding_watermarks")),
                            JsonDocument.object(results.getString("pending_funding_watermarks")),
                            JsonDocument.object(results.getString("venue_state_snapshot")),
                            results.getBigDecimal("capital_usd"),
                            JsonDocument.array(results.getString("data_issues")),
                            CaptureStatus.valueOf(results.getString("capture_status")),
                            results.getString("failure_code"),
                            results.getString("failure_detail"),
                            candidates);
                    connection.commit();
                    return Optional.of(run);
                }
            } catch (SQLException e) {
                rollbackPreserving(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read XVF signal run " + signalRunId, e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static void insertRun(Connection connection, XvfSignalRun run) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_RUN)) {
            statement.setObject(1, run.signalRunId());
            statement.setShort(2, run.snapshotSchemaVersion());
            statement.setObject(3, utc(run.scheduledDecisionAt()));
            statement.setObject(4, utc(run.cutoffUtc()));
            statement.setObject(5, utc(run.captureStartedAt()));
            statement.setObject(6, utc(run.captureEndedAt()));
            statement.setObject(7, run.productionDate());
            statement.setString(8, run.productionZone().getId());
            statement.setObject(9, utc(run.generatedAt()));
            statement.setString(10, run.scheduledAttemptId());
            statement.setString(11, run.codeRevision());
            statement.setString(12, run.strategyVersion());
            statement.setString(13, run.configurationHash());
            statement.setString(14, run.configurationSnapshot().json());
            statement.setString(15, run.settledFundingWatermarks().json());
            statement.setString(16, run.pendingFundingWatermarks().json());
            statement.setString(17, run.venueStateSnapshot().json());
            setDecimal(statement, 18, run.capitalUsd());
            statement.setInt(19, run.candidates().size());
            statement.setString(20, run.dataIssues().json());
            statement.setString(21, run.captureStatus().name());
            setText(statement, 22, run.failureCode());
            setText(statement, 23, run.failureDetail());
            statement.executeUpdate();
        }
    }

    private static void insertCandidates(Connection connection, UUID signalRunId,
                                         List<Candidate> candidates) throws SQLException {
        if (candidates.isEmpty()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_CANDIDATE)) {
            for (Candidate candidate : candidates) {
                Pair pair = candidate.pair();
                Ranks ranks = candidate.ranks();
                SignalScore signal = candidate.signalScore();
                Route route = candidate.route();
                ExpectedNet expected = candidate.expectedNet();

                statement.setObject(1, signalRunId);
                statement.setInt(2, candidate.evaluationOrder());
                statement.setInt(3, ranks.grossRank());
                statement.setString(4, pair.base());
                statement.setString(5, pair.pairType().name());
                statement.setString(6, pair.shortVenue());
                statement.setString(7, pair.shortVenueSymbol());
                statement.setString(8, pair.longVenue());
                statement.setString(9, pair.longVenueSymbol());
                setInteger(statement, 10, ranks.baselineBookRank());
                setInteger(statement, 11, ranks.shadowBookRank());
                statement.setBigDecimal(12, signal.rawSpreadAnnualPct());
                statement.setBoolean(13, signal.eligibleYesterday());
                statement.setBigDecimal(14, signal.staleDiscountFactor());
                statement.setBigDecimal(15, signal.adjustedSpreadAnnualPct());
                setBoolean(statement, 16, signal.pendingFundingFresh());
                setDecimal(statement, 17, signal.thinLegWeeklyQuoteVolumeUsd());
                statement.setString(18, route.makerVenue());
                statement.setString(19, route.takerVenue());
                statement.setInt(20, route.plannedHoldHours());
                setDecimal(statement, 21, expected.pendingFundingSpreadBps());
                setDecimal(statement, 22, expected.entryBasisBps());
                setDecimal(statement, 23, expected.expectedFundingBps());
                setDecimal(statement, 24, expected.expectedBasisPnlBps());
                setDecimal(statement, 25, expected.expectedEntryFeeBps());
                setDecimal(statement, 26, expected.expectedExitFeeBps());
                setDecimal(statement, 27, expected.expectedSlippageBps());
                setDecimal(statement, 28, expected.riskPenaltyBps());
                setDecimal(statement, 29, expected.expectedNetBps());
                setDecimal(statement, 30, candidate.requestedLegNotionalUsd());
                statement.setString(31, candidate.shortLegSnapshot().json());
                statement.setString(32, candidate.longLegSnapshot().json());
                statement.setString(33, candidate.scoreComponents().json());
                statement.setString(34, candidate.gateResults().json());
                statement.setString(35, candidate.scoreStatus().name());
                statement.setString(36, candidate.decisionReasons().json());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static List<Candidate> readCandidates(Connection connection, UUID signalRunId)
            throws SQLException {
        List<Candidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(SELECT_CANDIDATES)) {
            statement.setObject(1, signalRunId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    Pair pair = new Pair(
                            results.getString("base"),
                            PairType.valueOf(results.getString("pair_type")),
                            results.getString("short_venue"),
                            results.getString("short_venue_symbol"),
                            results.getString("long_venue"),
                            results.getString("long_venue_symbol"));
                    Ranks ranks = new Ranks(
                            results.getInt("gross_rank"),
                            results.getObject("baseline_book_rank", Integer.class),
                            results.getObject("shadow_book_rank", Integer.class));
                    SignalScore signal = new SignalScore(
                            results.getBigDecimal("raw_spread_annual_pct"),
                            results.getBoolean("eligible_yesterday"),
                            results.getBigDecimal("stale_discount_factor"),
                            results.getBigDecimal("adjusted_spread_annual_pct"),
                            results.getObject("pending_funding_fresh", Boolean.class),
                            results.getBigDecimal("thin_leg_weekly_quote_volume_usd"));
                    Route route = new Route(
                            results.getString("maker_venue"),
                            results.getString("taker_venue"),
                            results.getInt("planned_hold_hours"));
                    ExpectedNet expected = new ExpectedNet(
                            results.getBigDecimal("pending_funding_spread_bps"),
                            results.getBigDecimal("entry_basis_bps"),
                            results.getBigDecimal("expected_funding_bps"),
                            results.getBigDecimal("expected_basis_pnl_bps"),
                            results.getBigDecimal("expected_entry_fee_bps"),
                            results.getBigDecimal("expected_exit_fee_bps"),
                            results.getBigDecimal("expected_slippage_bps"),
                            results.getBigDecimal("risk_penalty_bps"),
                            results.getBigDecimal("expected_net_bps"));
                    candidates.add(new Candidate(
                            results.getInt("evaluation_order"),
                            pair,
                            ranks,
                            signal,
                            route,
                            expected,
                            results.getBigDecimal("requested_leg_notional_usd"),
                            JsonDocument.object(results.getString("short_leg_snapshot")),
                            JsonDocument.object(results.getString("long_leg_snapshot")),
                            JsonDocument.object(results.getString("score_components")),
                            JsonDocument.object(results.getString("gate_results")),
                            ScoreStatus.valueOf(results.getString("score_status")),
                            JsonDocument.array(results.getString("decision_reasons"))));
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static OffsetDateTime utc(java.time.Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static void setDecimal(PreparedStatement statement, int index, BigDecimal value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, value);
        }
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setBoolean(PreparedStatement statement, int index, Boolean value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BOOLEAN);
        } else {
            statement.setBoolean(index, value);
        }
    }

    private static void setText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void rollbackPreserving(Connection connection, SQLException failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
