package com.smalistean.propstrategy.xvf.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Candidate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresXvfSignalRepositoryIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PostgreSQLContainer postgres;
    private static DatabaseConfig database;
    private static PostgresXvfSignalRepository repository;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:17.10-alpine")
                .withDatabaseName("xvf_audit")
                .withUsername("xvf_test")
                .withPassword("xvf_test");
        postgres.start();
        database = new DatabaseConfig(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        DatabaseMigrator.migrate(database);
        repository = new PostgresXvfSignalRepository(database);
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void migratesLatestAndRoundTripsTypedAuditData() throws Exception {
        UUID runId = UUID.randomUUID();
        XvfSignalRun run = XvfSignalRunFixtures.complete(runId, List.of(
                XvfSignalRunFixtures.binanceBybit(1, 1, 1, 2),
                XvfSignalRunFixtures.binanceHyperliquid(2, 2, 2, 1)));

        repository.insert(run);
        XvfSignalRun stored = repository.findById(runId).orElseThrow();

        assertTrue(flywayMigrationSucceeded("23"));
        assertRunEquals(run, stored);
    }

    @Test
    void findsAndUniquelyEnforcesScheduledAttemptIdentity() {
        XvfSignalRun first = XvfSignalRunFixtures.complete(UUID.randomUUID(), List.of());
        repository.insert(first);

        XvfSignalRun found = repository.findByScheduledAttemptId(
                first.scheduledAttemptId()).orElseThrow();
        assertEquals(first.signalRunId(), found.signalRunId());
        assertEquals(first.scheduledAttemptId(), found.scheduledAttemptId());

        XvfSignalRun duplicateAttempt = copyWithScheduledAttemptId(
                XvfSignalRunFixtures.complete(UUID.randomUUID(), List.of()),
                first.scheduledAttemptId());
        assertThrows(IllegalStateException.class, () -> repository.insert(duplicateAttempt));
        assertFalse(repository.findById(duplicateAttempt.signalRunId()).isPresent());
    }

    @Test
    void candidateConstraintFailureRollsBackTheWholeRun() {
        UUID runId = UUID.randomUUID();
        XvfSignalRun duplicateBaselineRank = XvfSignalRunFixtures.complete(runId, List.of(
                XvfSignalRunFixtures.binanceBybit(1, 1, 1, 1),
                XvfSignalRunFixtures.binanceHyperliquid(2, 2, 1, 2)));

        assertThrows(IllegalStateException.class, () -> repository.insert(duplicateBaselineRank));
        assertFalse(repository.findById(runId).isPresent());
    }

    @Test
    void partialAndFailedAttemptsRemainAuditable() {
        XvfSignalRun partial = XvfSignalRunFixtures.partial(UUID.randomUUID(),
                List.of(XvfSignalRunFixtures.binanceBybit(1, 1, 1, null)));
        XvfSignalRun failed = XvfSignalRunFixtures.failed(UUID.randomUUID());

        repository.insert(partial);
        repository.insert(failed);

        XvfSignalRun storedPartial = repository.findById(partial.signalRunId()).orElseThrow();
        XvfSignalRun storedFailed = repository.findById(failed.signalRunId()).orElseThrow();
        assertEquals(XvfSignalRun.CaptureStatus.PARTIAL, storedPartial.captureStatus());
        assertEquals(1, storedPartial.candidates().size());
        assertEquals("MISSING_DEPTH", storedPartial.failureCode());
        assertEquals(XvfSignalRun.CaptureStatus.FAILED, storedFailed.captureStatus());
        assertTrue(storedFailed.candidates().isEmpty());
        assertEquals("SIGNAL_QUERY_FAILED", storedFailed.failureCode());
    }

    @Test
    void permitsIndependentRunsAtTheSameCutoff() {
        Instant cutoff = Instant.parse("2026-08-21T12:00:00.654321Z");
        XvfSignalRun first = XvfSignalRunFixtures.complete(UUID.randomUUID(), cutoff, List.of());
        XvfSignalRun second = XvfSignalRunFixtures.complete(UUID.randomUUID(), cutoff, List.of());

        repository.insert(first);
        repository.insert(second);

        assertTrue(repository.findById(first.signalRunId()).isPresent());
        assertTrue(repository.findById(second.signalRunId()).isPresent());
    }

    @Test
    void blocksUpdatesDeletesAndTruncates() {
        XvfSignalRun run = XvfSignalRunFixtures.complete(UUID.randomUUID(),
                List.of(XvfSignalRunFixtures.binanceBybit(1, 1, 1, 1)));
        repository.insert(run);

        assertMutationRejected("UPDATE xvf_signal_run SET code_revision='changed' WHERE signal_run_id=?",
                run.signalRunId());
        assertMutationRejected("DELETE FROM xvf_signal_run WHERE signal_run_id=?", run.signalRunId());
        assertMutationRejected("UPDATE xvf_signal_candidate SET gross_rank=9 WHERE signal_run_id=?",
                run.signalRunId());
        assertMutationRejected("DELETE FROM xvf_signal_candidate WHERE signal_run_id=?", run.signalRunId());
        assertMutationRejected("TRUNCATE xvf_signal_candidate", null);
        assertMutationRejected("TRUNCATE xvf_signal_candidate, xvf_signal_run", null);
    }

    @Test
    void sealsCandidateMembershipAndRejectsCandidatesForFailedRuns() throws Exception {
        XvfSignalRun complete = XvfSignalRunFixtures.complete(UUID.randomUUID(),
                List.of(XvfSignalRunFixtures.binanceBybit(1, 1, 1, 1)));
        XvfSignalRun failed = XvfSignalRunFixtures.failed(UUID.randomUUID());
        repository.insert(complete);
        repository.insert(failed);

        assertThrows(SQLException.class,
                () -> copyCandidate(complete.signalRunId(), complete.signalRunId(), 2, 2));
        assertThrows(SQLException.class,
                () -> copyCandidate(complete.signalRunId(), failed.signalRunId(), 1, 1));

        assertEquals(1, repository.findById(complete.signalRunId()).orElseThrow().candidates().size());
        assertTrue(repository.findById(failed.signalRunId()).orElseThrow().candidates().isEmpty());
    }

    private static boolean flywayMigrationSucceeded(String version) throws SQLException {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT success FROM flyway_schema_history WHERE version = ?
                     """)) {
            statement.setString(1, version);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getBoolean(1);
            }
        }
    }

    private static void assertMutationRejected(String sql, UUID runId) {
        assertThrows(SQLException.class, () -> {
            try (Connection connection = open()) {
                if (runId == null) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                } else {
                    try (PreparedStatement statement = connection.prepareStatement(sql)) {
                        statement.setObject(1, runId);
                        statement.executeUpdate();
                    }
                }
            }
        });
    }

    private static void copyCandidate(UUID sourceRunId, UUID targetRunId,
                                      int evaluationOrder, int grossRank) throws SQLException {
        String sql = """
                INSERT INTO xvf_signal_candidate
                SELECT ?, ?, ?, base || 'X', pair_type,
                       short_venue, short_venue_symbol || 'X',
                       long_venue, long_venue_symbol || 'X',
                       NULL, NULL, raw_spread_annual_pct, eligible_yesterday,
                       stale_discount_factor, adjusted_spread_annual_pct,
                       pending_funding_fresh, thin_leg_weekly_quote_volume_usd,
                       maker_venue, taker_venue, planned_hold_hours,
                       pending_funding_spread_bps, entry_basis_bps, expected_funding_bps,
                       expected_basis_pnl_bps, expected_entry_fee_bps, expected_exit_fee_bps,
                       expected_slippage_bps, risk_penalty_bps, expected_net_bps,
                       requested_leg_notional_usd, short_leg_snapshot, long_leg_snapshot,
                       score_components, gate_results, score_status, decision_reasons, created_at
                FROM xvf_signal_candidate
                WHERE signal_run_id = ?
                ORDER BY evaluation_order
                LIMIT 1
                """;
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, targetRunId);
            statement.setInt(2, evaluationOrder);
            statement.setInt(3, grossRank);
            statement.setObject(4, sourceRunId);
            statement.executeUpdate();
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(database.url(), database.user(), database.password());
    }

    private static XvfSignalRun copyWithScheduledAttemptId(
            XvfSignalRun source, String scheduledAttemptId) {
        return new XvfSignalRun(
                source.signalRunId(), source.snapshotSchemaVersion(), source.scheduledDecisionAt(),
                source.cutoffUtc(), source.captureStartedAt(), source.captureEndedAt(),
                source.productionDate(), source.productionZone(), source.generatedAt(),
                scheduledAttemptId, source.codeRevision(), source.strategyVersion(),
                source.configurationHash(), source.configurationSnapshot(),
                source.settledFundingWatermarks(), source.pendingFundingWatermarks(),
                source.venueStateSnapshot(), source.capitalUsd(), source.dataIssues(),
                source.captureStatus(), source.failureCode(), source.failureDetail(),
                source.candidates());
    }

    private static void assertDecimalEquals(BigDecimal expected, BigDecimal actual) {
        assertEquals(0, expected.compareTo(actual), () -> expected + " != " + actual);
    }

    private static void assertNullableDecimalEquals(BigDecimal expected, BigDecimal actual) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual);
        } else {
            assertDecimalEquals(expected, actual);
        }
    }

    private static void assertRunEquals(XvfSignalRun expected, XvfSignalRun actual) throws Exception {
        assertEquals(expected.signalRunId(), actual.signalRunId());
        assertEquals(expected.snapshotSchemaVersion(), actual.snapshotSchemaVersion());
        assertEquals(expected.cutoffUtc(), actual.cutoffUtc());
        assertEquals(expected.productionDate(), actual.productionDate());
        assertEquals(expected.productionZone(), actual.productionZone());
        assertEquals(expected.generatedAt(), actual.generatedAt());
        assertEquals(expected.codeRevision(), actual.codeRevision());
        assertEquals(expected.strategyVersion(), actual.strategyVersion());
        assertEquals(expected.configurationHash(), actual.configurationHash());
        assertJsonEquals(expected.configurationSnapshot(), actual.configurationSnapshot());
        assertJsonEquals(expected.settledFundingWatermarks(), actual.settledFundingWatermarks());
        assertJsonEquals(expected.pendingFundingWatermarks(), actual.pendingFundingWatermarks());
        assertJsonEquals(expected.venueStateSnapshot(), actual.venueStateSnapshot());
        assertNullableDecimalEquals(expected.capitalUsd(), actual.capitalUsd());
        assertJsonEquals(expected.dataIssues(), actual.dataIssues());
        assertEquals(expected.captureStatus(), actual.captureStatus());
        assertEquals(expected.failureCode(), actual.failureCode());
        assertEquals(expected.failureDetail(), actual.failureDetail());
        assertEquals(expected.candidates().size(), actual.candidates().size());
        for (int i = 0; i < expected.candidates().size(); i++) {
            assertCandidateEquals(expected.candidates().get(i), actual.candidates().get(i));
        }
    }

    private static void assertCandidateEquals(Candidate expected, Candidate actual) throws Exception {
        assertEquals(expected.evaluationOrder(), actual.evaluationOrder());
        assertEquals(expected.pair(), actual.pair());
        assertEquals(expected.ranks(), actual.ranks());
        assertEquals(expected.signalScore().eligibleYesterday(),
                actual.signalScore().eligibleYesterday());
        assertEquals(expected.signalScore().pendingFundingFresh(),
                actual.signalScore().pendingFundingFresh());
        assertDecimalEquals(expected.signalScore().rawSpreadAnnualPct(),
                actual.signalScore().rawSpreadAnnualPct());
        assertDecimalEquals(expected.signalScore().staleDiscountFactor(),
                actual.signalScore().staleDiscountFactor());
        assertDecimalEquals(expected.signalScore().adjustedSpreadAnnualPct(),
                actual.signalScore().adjustedSpreadAnnualPct());
        assertNullableDecimalEquals(expected.signalScore().thinLegWeeklyQuoteVolumeUsd(),
                actual.signalScore().thinLegWeeklyQuoteVolumeUsd());
        assertEquals(expected.route(), actual.route());
        assertNullableDecimalEquals(expected.expectedNet().pendingFundingSpreadBps(),
                actual.expectedNet().pendingFundingSpreadBps());
        assertNullableDecimalEquals(expected.expectedNet().entryBasisBps(),
                actual.expectedNet().entryBasisBps());
        assertNullableDecimalEquals(expected.expectedNet().expectedFundingBps(),
                actual.expectedNet().expectedFundingBps());
        assertNullableDecimalEquals(expected.expectedNet().expectedBasisPnlBps(),
                actual.expectedNet().expectedBasisPnlBps());
        assertNullableDecimalEquals(expected.expectedNet().expectedEntryFeeBps(),
                actual.expectedNet().expectedEntryFeeBps());
        assertNullableDecimalEquals(expected.expectedNet().expectedExitFeeBps(),
                actual.expectedNet().expectedExitFeeBps());
        assertNullableDecimalEquals(expected.expectedNet().expectedSlippageBps(),
                actual.expectedNet().expectedSlippageBps());
        assertNullableDecimalEquals(expected.expectedNet().riskPenaltyBps(),
                actual.expectedNet().riskPenaltyBps());
        assertNullableDecimalEquals(expected.expectedNet().expectedNetBps(),
                actual.expectedNet().expectedNetBps());
        assertNullableDecimalEquals(expected.requestedLegNotionalUsd(),
                actual.requestedLegNotionalUsd());
        assertJsonEquals(expected.shortLegSnapshot(), actual.shortLegSnapshot());
        assertJsonEquals(expected.longLegSnapshot(), actual.longLegSnapshot());
        assertJsonEquals(expected.scoreComponents(), actual.scoreComponents());
        assertJsonEquals(expected.gateResults(), actual.gateResults());
        assertEquals(expected.scoreStatus(), actual.scoreStatus());
        assertJsonEquals(expected.decisionReasons(), actual.decisionReasons());
    }

    private static void assertJsonEquals(JsonDocument expected, JsonDocument actual) throws Exception {
        JsonNode expectedTree = MAPPER.readTree(expected.json());
        JsonNode actualTree = MAPPER.readTree(actual.json());
        assertEquals(expectedTree, actualTree);
    }
}
