package com.smalistean.propstrategy.xvf.shadow;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XvfShadowCaptureTimingMigrationIT {

    @Test
    void upgradesExistingV21RowAndRestoresAppendOnlyProtection() throws Exception {
        PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.10-alpine")
                .withDatabaseName("xvf_v22_upgrade")
                .withUsername("xvf_test")
                .withPassword("xvf_test");
        postgres.start();
        try {
            migrateToV21(postgres);
            UUID legacyRunId = insertLegacyRun(postgres);

            migrateToLatest(postgres);

            assertTrue(migrationSucceeded(postgres, "22"));
            assertLegacyTimingBackfill(postgres, legacyRunId);
            assertAppendOnlyProtectionRestored(postgres, legacyRunId);
        } finally {
            postgres.stop();
        }
    }

    private static void migrateToV21(PostgreSQLContainer postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target(MigrationVersion.fromVersion("21"))
                .load()
                .migrate();
    }

    private static void migrateToLatest(PostgreSQLContainer postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();
    }

    private static UUID insertLegacyRun(PostgreSQLContainer postgres) throws SQLException {
        UUID runId = UUID.randomUUID();
        try (Connection connection = open(postgres);
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO xvf_signal_run (
                         signal_run_id,
                         snapshot_schema_version,
                         cutoff_utc,
                         production_date,
                         production_zone,
                         generated_at,
                         code_revision,
                         strategy_version,
                         configuration_hash,
                         configuration_snapshot,
                         settled_funding_watermarks,
                         pending_funding_watermarks,
                         venue_state_snapshot,
                         capital_usd,
                         candidate_count,
                         data_issues,
                         capture_status,
                         failure_code,
                         failure_detail
                     ) VALUES (
                         ?,
                         1,
                         TIMESTAMPTZ '2026-08-21 09:00:00.123456+00',
                         DATE '2026-08-21',
                         'Europe/Chisinau',
                         TIMESTAMPTZ '2026-08-21 09:00:01.654321+00',
                         'legacy-revision',
                         'legacy-strategy',
                         repeat('0', 64),
                         '{}'::jsonb,
                         '{}'::jsonb,
                         '{}'::jsonb,
                         '{}'::jsonb,
                         NULL,
                         0,
                         '[{"code":"LEGACY_CAPTURE_FAILURE"}]'::jsonb,
                         'FAILED',
                         'LEGACY_CAPTURE_FAILURE',
                         'Created before V22'
                     )
                     """)) {
            statement.setObject(1, runId);
            statement.executeUpdate();
        }
        return runId;
    }

    private static boolean migrationSucceeded(PostgreSQLContainer postgres, String version)
            throws SQLException {
        try (Connection connection = open(postgres);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT success
                       FROM flyway_schema_history
                      WHERE version = ?
                     """)) {
            statement.setString(1, version);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getBoolean(1);
            }
        }
    }

    private static void assertLegacyTimingBackfill(PostgreSQLContainer postgres, UUID runId)
            throws SQLException {
        try (Connection connection = open(postgres);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT scheduled_decision_at,
                            capture_started_at,
                            capture_ended_at,
                            scheduled_attempt_id
                       FROM xvf_signal_run
                      WHERE signal_run_id = ?
                     """)) {
            statement.setObject(1, runId);
            try (ResultSet results = statement.executeQuery()) {
                assertTrue(results.next());
                assertEquals(Instant.parse("2026-08-21T09:00:00.123456Z"),
                        results.getObject("scheduled_decision_at", OffsetDateTime.class).toInstant());
                assertEquals(Instant.parse("2026-08-21T09:00:00.123456Z"),
                        results.getObject("capture_started_at", OffsetDateTime.class).toInstant());
                assertEquals(Instant.parse("2026-08-21T09:00:01.654321Z"),
                        results.getObject("capture_ended_at", OffsetDateTime.class).toInstant());
                assertEquals("LEGACY-" + runId, results.getString("scheduled_attempt_id"));
            }
        }
    }

    private static void assertAppendOnlyProtectionRestored(PostgreSQLContainer postgres, UUID runId) {
        SQLException error = assertThrows(SQLException.class, () -> {
            try (Connection connection = open(postgres);
                 PreparedStatement statement = connection.prepareStatement("""
                         UPDATE xvf_signal_run
                            SET code_revision = 'forbidden-change'
                          WHERE signal_run_id = ?
                         """)) {
                statement.setObject(1, runId);
                statement.executeUpdate();
            }
        });
        assertEquals("55000", error.getSQLState());
    }

    private static Connection open(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }
}
