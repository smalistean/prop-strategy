package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.database.DatabaseConfig;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Small insert-only repository for due candidates and their settled funding facts. */
public final class PostgresXvfCandidateOutcomeRepository {

    private static final String FIND_DUE = """
            SELECT c.signal_run_id, c.evaluation_order, r.scheduled_decision_at, r.cutoff_utc,
                   c.planned_hold_hours,
                   r.scheduled_decision_at + make_interval(hours => c.planned_hold_hours) AS target_exit_utc,
                   c.short_venue, c.short_venue_symbol, c.long_venue, c.long_venue_symbol,
                   c.requested_leg_notional_usd
            FROM xvf_signal_candidate c
            JOIN xvf_signal_run r ON r.signal_run_id = c.signal_run_id
            WHERE c.score_status = 'SCORABLE'
              AND r.scheduled_decision_at + make_interval(hours => c.planned_hold_hours) <= ?
              AND NOT EXISTS (
                  SELECT 1 FROM xvf_signal_candidate_outcome o
                  WHERE o.signal_run_id = c.signal_run_id
                    AND o.evaluation_order = c.evaluation_order
                    AND o.horizon_hours = c.planned_hold_hours
                    AND o.capture_status = 'COMPLETE')
            ORDER BY target_exit_utc, c.signal_run_id, c.evaluation_order
            LIMIT ?
            """;

    private static final String FIND_FUNDING = """
            SELECT venue, venue_symbol, funding_time, funding_rate
            FROM perp_funding_all
            WHERE ((venue = ? AND venue_symbol = ?) OR (venue = ? AND venue_symbol = ?))
              AND funding_time > ? AND funding_time <= ?
            ORDER BY funding_time, venue, venue_symbol
            """;

    private static final String INSERT = """
            INSERT INTO xvf_signal_candidate_outcome (
                outcome_attempt_id, signal_run_id, evaluation_order, horizon_hours,
                target_exit_utc, capture_started_at, captured_at, capture_tolerance_seconds,
                capture_status, short_exit_snapshot, long_exit_snapshot,
                funding_observations, funding_watermarks, data_issues, formula_inputs_version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb),
                    CAST(? AS jsonb), CAST(? AS jsonb), CAST(? AS jsonb), ?)
            """;

    private final DatabaseConfig config;

    public PostgresXvfCandidateOutcomeRepository(DatabaseConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public List<DueCandidate> findDue(Instant eligibleAt, int limit) {
        Objects.requireNonNull(eligibleAt, "eligibleAt");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<DueCandidate> due = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(FIND_DUE)) {
            statement.setObject(1, utc(eligibleAt));
            statement.setInt(2, limit);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    due.add(new DueCandidate(
                            results.getObject("signal_run_id", UUID.class),
                            results.getInt("evaluation_order"),
                            instant(results, "scheduled_decision_at"),
                            instant(results, "cutoff_utc"),
                            results.getInt("planned_hold_hours"),
                            instant(results, "target_exit_utc"),
                            results.getString("short_venue"),
                            results.getString("short_venue_symbol"),
                            results.getString("long_venue"),
                            results.getString("long_venue_symbol"),
                            results.getBigDecimal("requested_leg_notional_usd")));
                }
            }
            return List.copyOf(due);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find due XVF candidate outcomes", e);
        }
    }

    public List<FundingFact> findFunding(DueCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        List<FundingFact> facts = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(FIND_FUNDING)) {
            statement.setString(1, candidate.shortVenue());
            statement.setString(2, candidate.shortVenueSymbol());
            statement.setString(3, candidate.longVenue());
            statement.setString(4, candidate.longVenueSymbol());
            statement.setObject(5, utc(candidate.entryCutoffUtc()));
            statement.setObject(6, utc(candidate.targetExitUtc()));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    facts.add(new FundingFact(
                            results.getString("venue"), results.getString("venue_symbol"),
                            instant(results, "funding_time"), results.getBigDecimal("funding_rate")));
                }
            }
            return List.copyOf(facts);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read settled funding for XVF candidate", e);
        }
    }

    public void insert(XvfCandidateOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, outcome.outcomeAttemptId());
            statement.setObject(2, outcome.signalRunId());
            statement.setInt(3, outcome.evaluationOrder());
            statement.setInt(4, outcome.horizonHours());
            statement.setObject(5, utc(outcome.targetExitUtc()));
            statement.setObject(6, utc(outcome.captureStartedAt()));
            statement.setObject(7, utc(outcome.capturedAt()));
            statement.setInt(8, outcome.captureToleranceSeconds());
            statement.setString(9, outcome.captureStatus().name());
            statement.setString(10, outcome.shortExitSnapshot().json());
            statement.setString(11, outcome.longExitSnapshot().json());
            statement.setString(12, outcome.fundingObservations().json());
            statement.setString(13, outcome.fundingWatermarks().json());
            statement.setString(14, outcome.dataIssues().json());
            statement.setShort(15, outcome.formulaInputsVersion());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert XVF candidate outcome "
                    + outcome.outcomeAttemptId(), e);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(config.url(), config.user(), config.password());
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet results, String column) throws SQLException {
        return results.getObject(column, OffsetDateTime.class).toInstant();
    }

    public record DueCandidate(
            UUID signalRunId, int evaluationOrder, Instant scheduledDecisionAt,
            Instant entryCutoffUtc,
            int horizonHours, Instant targetExitUtc,
            String shortVenue, String shortVenueSymbol,
            String longVenue, String longVenueSymbol,
            BigDecimal requestedLegNotionalUsd) {
        public DueCandidate {
            Objects.requireNonNull(signalRunId, "signalRunId");
            Objects.requireNonNull(scheduledDecisionAt, "scheduledDecisionAt");
            Objects.requireNonNull(entryCutoffUtc, "entryCutoffUtc");
            Objects.requireNonNull(targetExitUtc, "targetExitUtc");
            Objects.requireNonNull(requestedLegNotionalUsd, "requestedLegNotionalUsd");
        }
    }

    public record FundingFact(String venue, String venueSymbol,
                              Instant fundingTime, BigDecimal fundingRate) {
        public FundingFact {
            Objects.requireNonNull(venue, "venue");
            Objects.requireNonNull(venueSymbol, "venueSymbol");
            Objects.requireNonNull(fundingTime, "fundingTime");
            Objects.requireNonNull(fundingRate, "fundingRate");
        }
    }
}
