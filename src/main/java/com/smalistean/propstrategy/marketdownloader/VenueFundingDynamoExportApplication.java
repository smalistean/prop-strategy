package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pulls hourly funding observations out of DynamoDB into {@code venue_funding_observation}.
 *
 * <p>Counterpart to {@link DeribitDynamoExportApplication}, same shape: a MANIFEST item per hour names
 * the venues and their counts, and what actually transfers is checked against it so a partially
 * written hour is reported rather than mistaken for a quiet market.
 *
 * <p>These rows are observations of PENDING rates, not settled payments - see the V17 migration
 * header. They land in their own table and stay out of {@code perp_funding_all} for that reason.
 *
 * <h2>Usage</h2>
 * <pre>
 *   -DdynamoTable=deribit-chain-funding    required
 *   -DexportFrom=2026-08-16T00:00:00Z      optional; defaults to the newest hour already stored
 *   -DawsRegion=eu-central-1               optional
 * </pre>
 */
public final class VenueFundingDynamoExportApplication {

    private static final String UPSERT = """
            INSERT INTO venue_funding_observation (
                venue, venue_symbol, observed_hour, observed_at, target_stamp, funding_rate)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (venue, venue_symbol, observed_hour) DO NOTHING
            """;

    private VenueFundingDynamoExportApplication() {
    }

    public static void main(String[] args) throws Exception {
        String table = System.getProperty("dynamoTable");
        if (table == null || table.isBlank()) {
            throw new IllegalStateException("-DdynamoTable is required (the FundingTableName output)");
        }
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);

        var builder = DynamoDbClient.builder().httpClient(UrlConnectionHttpClient.builder().build());
        String region = System.getProperty("awsRegion");
        if (region != null && !region.isBlank()) {
            builder.region(software.amazon.awssdk.regions.Region.of(region));
        }

        try (DynamoDbClient dynamo = builder.build();
             Connection connection = DriverManager.getConnection(
                     database.url(), database.user(), database.password())) {
            connection.setAutoCommit(false);

            Instant from = startHour(connection);
            Instant to = Instant.now().truncatedTo(ChronoUnit.HOURS);
            System.out.printf("exporting %s .. %s from %s%n", from, to, table);

            int written = 0;
            int missing = 0;
            long rows = 0;
            for (Instant hour = from; !hour.isAfter(to); hour = hour.plus(1, ChronoUnit.HOURS)) {
                List<String> venues = readManifest(dynamo, table, hour);
                if (venues == null) {
                    System.out.printf("  %s  MISSING (no manifest)%n", hour);
                    missing++;
                    continue;
                }
                long hourRows = 0;
                for (String venue : venues) {
                    hourRows += copy(dynamo, table, connection, hour, venue);
                }
                connection.commit();
                rows += hourRows;
                written++;
                System.out.printf("  %s  %,d observations across %d venues%n",
                        hour, hourRows, venues.size());
            }
            System.out.printf("%n%d hours exported, %,d observations. %d missing.%n",
                    written, rows, missing);
        }
    }

    /** Venue names for one hour, or null when that hour was never completed. */
    private static List<String> readManifest(DynamoDbClient dynamo, String table, Instant hour) {
        QueryResponse response = dynamo.query(QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("venue_hour = :k")
                .expressionAttributeValues(Map.of(":k",
                        AttributeValue.builder().s("MANIFEST#" + hour).build()))
                .build());
        if (response.items().isEmpty()) {
            return null;
        }
        List<String> venues = new ArrayList<>();
        for (String pair : response.items().get(0).get("venue_counts").s().split(",")) {
            if (!pair.isBlank()) {
                venues.add(pair.split("=")[0]);
            }
        }
        return venues;
    }

    private static long copy(DynamoDbClient dynamo, String table, Connection connection,
                             Instant hour, String venue) throws SQLException {
        long written = 0;
        Map<String, AttributeValue> startKey = null;
        try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            do {
                QueryRequest.Builder request = QueryRequest.builder()
                        .tableName(table)
                        .keyConditionExpression("venue_hour = :k")
                        .expressionAttributeValues(Map.of(":k",
                                AttributeValue.builder().s(venue + "#" + hour).build()));
                if (startKey != null) {
                    request.exclusiveStartKey(startKey);
                }
                QueryResponse response = dynamo.query(request.build());
                for (Map<String, AttributeValue> item : response.items()) {
                    statement.setString(1, item.get("venue").s());
                    statement.setString(2, item.get("venue_symbol").s());
                    statement.setObject(3, hour.atOffset(ZoneOffset.UTC));
                    statement.setObject(4, Instant.parse(item.get("observed_at").s())
                            .atOffset(ZoneOffset.UTC));
                    AttributeValue stamp = item.get("target_stamp");
                    if (stamp == null || stamp.n() == null) {
                        statement.setNull(5, Types.TIMESTAMP_WITH_TIMEZONE);
                    } else {
                        statement.setObject(5, Instant.ofEpochMilli(Long.parseLong(stamp.n()))
                                .atOffset(ZoneOffset.UTC));
                    }
                    statement.setBigDecimal(6, new BigDecimal(item.get("funding_rate").n()));
                    statement.addBatch();
                    written++;
                }
                statement.executeBatch();
                startKey = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
            } while (startKey != null);
        }
        return written;
    }

    /** Resumes from the newest hour already stored; re-reads it, which is free under ON CONFLICT. */
    private static Instant startHour(Connection connection) throws SQLException {
        String override = System.getProperty("exportFrom");
        if (override != null && !override.isBlank()) {
            return Instant.parse(override).truncatedTo(ChronoUnit.HOURS);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT max(observed_hour) FROM venue_funding_observation");
             var results = statement.executeQuery()) {
            if (results.next() && results.getTimestamp(1) != null) {
                return results.getTimestamp(1).toInstant().truncatedTo(ChronoUnit.HOURS);
            }
        }
        return Instant.now().truncatedTo(ChronoUnit.HOURS).minus(30, ChronoUnit.DAYS);
    }
}
