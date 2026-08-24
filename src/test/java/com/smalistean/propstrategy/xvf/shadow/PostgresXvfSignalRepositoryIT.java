package com.smalistean.propstrategy.xvf.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Candidate;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.VenueSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

        assertTrue(flywayMigrationSucceeded("26"));
        assertRunEquals(run, stored);
    }

    @Test
    void persistsRetryableOutcomesAndRunsTheExpectedVersusRealizedReport() throws Exception {
        UUID runId = UUID.randomUUID();
        Candidate candidate = measurementCandidate();
        repository.insert(XvfSignalRunFixtures.complete(runId, List.of(candidate)));
        PostgresXvfCandidateOutcomeRepository outcomeRepository =
                new PostgresXvfCandidateOutcomeRepository(database);
        Instant target = XvfSignalRunFixtures.CUTOFF.plusSeconds(72 * 3600L);

        assertTrue(outcomeRepository.findDue(target.minusNanos(1_000), 10).isEmpty());
        List<PostgresXvfCandidateOutcomeRepository.DueCandidate> due =
                outcomeRepository.findDue(target, 10);
        assertEquals(1, due.size());
        insertMeasurementFunding(target);
        assertEquals(2, outcomeRepository.findFunding(due.getFirst()).size());

        XvfCandidateOutcome failed = outcome(runId, target,
                XvfCandidateOutcome.CaptureStatus.FAILED,
                JsonDocument.emptyObject(), JsonDocument.emptyObject(),
                JsonDocument.emptyArray(),
                JsonDocument.array("[{\"code\":\"TEMPORARY_SOURCE_FAILURE\"}]"));
        outcomeRepository.insert(failed);

        XvfCandidateOutcomeService service = new XvfCandidateOutcomeService(
                outcomeRepository,
                List.of(fakeSource("binance", "3890", "3900"),
                        fakeSource("bybit", "4050", "4060")),
                Clock.fixed(target.plusSeconds(1), ZoneOffset.UTC), 600);
        XvfCandidateOutcome complete = service.captureDue(10).getFirst();
        assertEquals(XvfCandidateOutcome.CaptureStatus.COMPLETE, complete.captureStatus());

        assertTrue(outcomeRepository.findDue(target.plusSeconds(1), 10).isEmpty());
        assertThrows(IllegalStateException.class, () -> outcomeRepository.insert(
                outcome(runId, target, XvfCandidateOutcome.CaptureStatus.COMPLETE,
                        complete.shortExitSnapshot(), complete.longExitSnapshot(),
                        complete.fundingObservations(), JsonDocument.emptyArray())));
        assertMutationRejected(
                "UPDATE xvf_signal_candidate_outcome SET formula_inputs_version=2 WHERE outcome_attempt_id=?",
                complete.outcomeAttemptId());

        String report = Files.readString(Path.of(
                "scripts/xvf-shadow/02_xvf_shadow_candidate_outcomes.sql"));
        try (Connection connection = open(); Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(report)) {
            boolean found = false;
            while (results.next()) {
                if (!complete.outcomeAttemptId().equals(
                        results.getObject("outcome_attempt_id", UUID.class))) {
                    continue;
                }
                found = true;
                assertDecimalEquals(new BigDecimal("4.246875"),
                        results.getBigDecimal("realized_net_usd"));
                assertDecimalEquals(new BigDecimal("0.095625"),
                        results.getBigDecimal("expected_net_usd"));
                assertTrue(results.getBoolean("captured_within_tolerance"));
            }
            assertTrue(found, "Expected the complete fixture outcome in the SQL report");
        }

        for (String scenario : List.of(
                "03_xvf_shadow_book_transitions.sql",
                "04_xvf_shadow_policy_comparison.sql",
                "05_xvf_shadow_capital_grid.sql",
                "06_xvf_shadow_maker_sensitivity.sql",
                "07_xvf_shadow_symbol_contribution.sql",
                "08_xvf_shadow_leverage_stress.sql")) {
            String sql = Files.readString(Path.of("scripts/xvf-shadow", scenario));
            try (Connection connection = open(); Statement statement = connection.createStatement();
                 ResultSet results = statement.executeQuery(sql)) {
                assertTrue(results.getMetaData().getColumnCount() > 0, scenario);
                int rows = 0;
                while (results.next()) {
                    rows++;
                }
                assertTrue(rows > 0, scenario + " should return the fixture measurement");
            }
        }
    }

    @Test
    void derivesOpenRetainAndTerminalCloseWithTransitionOnlyFees() throws Exception {
        Instant emptyCycle = Instant.parse("2026-08-21T10:00:00.123456Z");
        Instant openCycle = Instant.parse("2026-08-21T11:00:00.123456Z");
        Instant retainCycle = Instant.parse("2026-08-21T12:00:00.123456Z");
        UUID openRunId = UUID.randomUUID();
        UUID retainRunId = UUID.randomUUID();
        repository.insert(XvfSignalRunFixtures.complete(UUID.randomUUID(), emptyCycle, List.of()));
        repository.insert(XvfSignalRunFixtures.complete(
                openRunId, openCycle, List.of(XvfSignalRunFixtures.binanceBybit(1, 1, 1, 1))));
        repository.insert(XvfSignalRunFixtures.complete(
                retainRunId, retainCycle, List.of(XvfSignalRunFixtures.binanceBybit(1, 1, 1, 1))));

        assertTransition(openRunId, "OPEN", new BigDecimal("0.016875"), BigDecimal.ZERO);
        assertTransition(retainRunId, "RETAIN", BigDecimal.ZERO, BigDecimal.ZERO);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT transition_type, entry_fee_charged_usd, exit_fee_charged_usd
                FROM xvf_shadow_book_transition_v1
                WHERE policy = 'BASELINE_RANKING'
                  AND transition_phase = 'TERMINAL'
                  AND previous_signal_run_id = ?
                  AND base = 'ETH'
                """)) {
            statement.setObject(1, retainRunId);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                assertEquals("CLOSE", results.getString("transition_type"));
                assertDecimalEquals(BigDecimal.ZERO,
                        results.getBigDecimal("entry_fee_charged_usd"));
                assertDecimalEquals(new BigDecimal("0.045"),
                        results.getBigDecimal("exit_fee_charged_usd"));
            }
        }
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

    private static void assertTransition(UUID currentRunId, String transitionType,
                                         BigDecimal entryFee, BigDecimal exitFee) throws SQLException {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                SELECT transition_type, entry_fee_charged_usd, exit_fee_charged_usd
                FROM xvf_shadow_book_transition_v1
                WHERE policy = 'BASELINE_RANKING'
                  AND transition_phase = 'DECISION'
                  AND current_signal_run_id = ?
                  AND base = 'ETH'
                """)) {
            statement.setObject(1, currentRunId);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                assertEquals(transitionType, results.getString("transition_type"));
                assertDecimalEquals(entryFee, results.getBigDecimal("entry_fee_charged_usd"));
                assertDecimalEquals(exitFee, results.getBigDecimal("exit_fee_charged_usd"));
            }
        }
    }

    private static Candidate measurementCandidate() {
        Candidate source = XvfSignalRunFixtures.binanceBybit(1, 1, 1, 1);
        return new Candidate(
                source.evaluationOrder(), source.pair(), source.ranks(), source.signalScore(),
                source.route(), source.expectedNet(), source.requestedLegNotionalUsd(),
                JsonDocument.object("{\"baseUnitsPerContract\":1}"),
                JsonDocument.object("{\"baseUnitsPerContract\":1}"),
                JsonDocument.object("""
                        {"formulaVersion":1,"shortExecutableEntryPrice":4000,
                         "longExecutableEntryPrice":4000}
                        """),
                source.gateResults(), source.scoreStatus(), source.decisionReasons());
    }

    private static XvfCandidateOutcome outcome(
            UUID runId, Instant target, XvfCandidateOutcome.CaptureStatus status,
            JsonDocument shortExit, JsonDocument longExit,
            JsonDocument funding, JsonDocument issues) {
        return new XvfCandidateOutcome(
                UUID.randomUUID(), runId, 1, 72, target,
                target, target.plusSeconds(1), 600, status,
                shortExit, longExit, funding,
                JsonDocument.object("{\"short\":\"2026-08-24T09:00:00.123456Z\","
                        + "\"long\":\"2026-08-24T09:00:00.123456Z\"}"),
                issues, (short) 1);
    }

    private static XvfVenueSnapshotSource fakeSource(String venue, String bid, String ask) {
        Instant sourceTime = XvfSignalRunFixtures.CUTOFF.plusSeconds(72 * 3600L + 1);
        XvfVenueSnapshotSource.ResponseTiming timing = new XvfVenueSnapshotSource.ResponseTiming(
                sourceTime, Optional.of(sourceTime), sourceTime);
        XvfVenueSnapshotSource.InstrumentSnapshot instrument =
                new XvfVenueSnapshotSource.InstrumentSnapshot(
                        venue, "ETHUSDT", "ETH", BigDecimal.ONE,
                        Optional.of(new XvfVenueSnapshotSource.ReferenceSnapshot(
                                Optional.of(new BigDecimal(bid)),
                                Optional.of(new BigDecimal(bid)), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), Optional.empty(), timing)),
                        Optional.empty(),
                        Optional.of(new XvfVenueSnapshotSource.TopOfBookSnapshot(
                                new BigDecimal(bid), new BigDecimal("2"),
                                new BigDecimal(ask), new BigDecimal("2"), timing)),
                        Optional.empty(),
                        Optional.of(new XvfVenueSnapshotSource.OrderBookSnapshot(
                                List.of(new XvfVenueSnapshotSource.BookLevel(
                                        new BigDecimal(bid), new BigDecimal("2"), Optional.of(1))),
                                List.of(new XvfVenueSnapshotSource.BookLevel(
                                        new BigDecimal(ask), new BigDecimal("2"), Optional.of(1))),
                                timing)),
                        List.of());
        return new XvfVenueSnapshotSource() {
            @Override
            public String venue() {
                return venue;
            }

            @Override
            public VenueSnapshot fetch(Set<String> venueSymbols) {
                assertEquals(Set.of("ETHUSDT"), venueSymbols);
                return new VenueSnapshot(venue, Map.of("ETHUSDT", instrument), List.of());
            }
        };
    }

    private static void insertMeasurementFunding(Instant target) throws SQLException {
        try (Connection connection = open()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO binance_perp_funding_rate
                        (symbol, funding_time, rate_type, funding_rate, mark_price)
                    VALUES ('ETHUSDT', ?, 'REALIZED', 0.001, 4000)
                    """)) {
                statement.setObject(1, java.time.OffsetDateTime.ofInstant(
                        target, java.time.ZoneOffset.UTC));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO bybit_perp_funding_rate
                        (venue_symbol, base, funding_time, funding_rate)
                    VALUES ('ETHUSDT', 'ETH', ?, 0.0002)
                    """)) {
                statement.setObject(1, java.time.OffsetDateTime.ofInstant(
                        target, java.time.ZoneOffset.UTC));
                statement.executeUpdate();
            }
        }
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
