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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls the hourly Deribit option-chain buffer out of DynamoDB into {@code deribit_option_quote}.
 *
 * <h2>Why the data lands in two places</h2>
 * DynamoDB records the hours because it is available when a home connection is not - two DNS outages
 * in 24 hours cost 9 hours of quotes that cannot be re-fetched at any price. But every question this
 * dataset exists to answer is analytical: implied forwards, put-call parity, term structure across
 * strikes. Those are aggregates and joins, which DynamoDB does not do. So DynamoDB is a buffer with a
 * TTL and Postgres is the archive, and this is the bridge.
 *
 * <h2>Incremental by default</h2>
 * Starts from the newest hour already in Postgres and walks forward, so a routine run transfers only
 * what is new. Re-running is harmless: the insert is {@code ON CONFLICT DO NOTHING} against the same
 * primary key the recorder writes to.
 *
 * <h2>Completeness is verified, not assumed</h2>
 * The recorder writes a MANIFEST item last, once every chain has landed, naming the underlyings and
 * the item count for that hour. This reads the manifest first and checks what it actually pulled
 * against it. That matters because the two failure modes here are silent by nature: an hour that was
 * never fully written looks like a thin market, and an hour whose TTL expired before it was exported
 * looks like an hour that never existed. Both are reported here rather than discovered months later.
 *
 * <h2>Usage</h2>
 * <pre>
 *   -DdynamoTable=deribit-chain      required; the stack's TableName output
 *   -DexportFrom=2026-08-16T00:00:00Z  optional; defaults to the newest hour already in Postgres
 *   -DawsRegion=eu-central-1         optional; otherwise the SDK's default chain
 * </pre>
 */
public final class DeribitDynamoExportApplication {

    private static final String UPSERT = """
            INSERT INTO deribit_option_quote (
                snapshot_time, instrument_name, underlying, quote_currency, expiry_time, strike,
                option_type, bid_price, ask_price, mark_price, mark_iv, underlying_price,
                index_price, open_interest, volume_24h)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON CONFLICT (snapshot_time, instrument_name) DO NOTHING
            """;

    private DeribitDynamoExportApplication() {
    }

    public static void main(String[] args) throws Exception {
        String table = System.getProperty("dynamoTable");
        if (table == null || table.isBlank()) {
            throw new IllegalStateException("-DdynamoTable is required (the stack's TableName output)");
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

            int hoursWritten = 0;
            int hoursMissing = 0;
            int hoursIncomplete = 0;
            long rows = 0;

            for (Instant hour = from; !hour.isAfter(to); hour = hour.plus(1, ChronoUnit.HOURS)) {
                Manifest manifest = readManifest(dynamo, table, hour);
                if (manifest == null) {
                    // No manifest means the recorder never finished this hour, or the TTL already
                    // reclaimed it. Either way there is nothing complete to fetch, and saying so is
                    // the point - this is exactly the gap that goes unnoticed otherwise.
                    System.out.printf("  %s  MISSING (no manifest)%n", hour);
                    hoursMissing++;
                    continue;
                }
                int written = 0;
                for (String underlying : manifest.underlyings()) {
                    written += copy(dynamo, table, connection, hour, underlying);
                }
                connection.commit();
                rows += written;
                hoursWritten++;
                if (written != manifest.itemCount()) {
                    System.out.printf("  %s  INCOMPLETE: %,d of %,d items%n",
                            hour, written, manifest.itemCount());
                    hoursIncomplete++;
                } else {
                    System.out.printf("  %s  %,d items%n", hour, written);
                }
            }

            System.out.printf("%n%d hours exported, %,d rows. %d missing, %d incomplete.%n",
                    hoursWritten, rows, hoursMissing, hoursIncomplete);
            if (hoursMissing > 0 || hoursIncomplete > 0) {
                System.out.println("Hours reported above are permanent holes unless the recorder is "
                        + "still inside the TTL window and can be re-invoked for them.");
            }
        }
    }

    /** Manifest for one hour: which partitions exist and how many items they should total. */
    private record Manifest(List<String> underlyings, int itemCount) { }

    private static Manifest readManifest(DynamoDbClient dynamo, String table, Instant hour) {
        QueryResponse response = dynamo.query(QueryRequest.builder()
                .tableName(table)
                .keyConditionExpression("snapshot_underlying = :k")
                .expressionAttributeValues(Map.of(":k",
                        AttributeValue.builder().s(hour + "#MANIFEST").build()))
                .build());
        if (response.items().isEmpty()) {
            return null;
        }
        Map<String, AttributeValue> item = response.items().get(0);
        List<String> underlyings = new ArrayList<>();
        // Stored as "BTC=818,ETH=690,..." - the names are what this needs, the counts are the
        // recorder's own record and are re-derived here from what actually transfers.
        for (String pair : item.get("underlying_counts").s().split(",")) {
            if (!pair.isBlank()) {
                underlyings.add(pair.split("=")[0]);
            }
        }
        return new Manifest(underlyings, Integer.parseInt(item.get("item_count").n()));
    }

    /** Copies one hour of one underlying, following pagination to the end. */
    private static int copy(DynamoDbClient dynamo, String table, Connection connection,
                            Instant hour, String underlying) throws SQLException {
        int written = 0;
        Map<String, AttributeValue> startKey = null;
        try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
            do {
                QueryRequest.Builder request = QueryRequest.builder()
                        .tableName(table)
                        .keyConditionExpression("snapshot_underlying = :k")
                        .expressionAttributeValues(Map.of(":k",
                                AttributeValue.builder().s(hour + "#" + underlying).build()));
                if (startKey != null) {
                    request.exclusiveStartKey(startKey);
                }
                QueryResponse response = dynamo.query(request.build());
                for (Map<String, AttributeValue> item : response.items()) {
                    bind(statement, hour, item);
                    statement.addBatch();
                    written++;
                }
                statement.executeBatch();
                // An empty map, not null, is how the SDK signals the last page.
                startKey = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
            } while (startKey != null);
        }
        return written;
    }

    private static void bind(PreparedStatement statement, Instant hour,
                             Map<String, AttributeValue> item) throws SQLException {
        statement.setObject(1, hour.atOffset(ZoneOffset.UTC));
        statement.setString(2, item.get("instrument_name").s());
        statement.setString(3, item.get("underlying").s());
        statement.setString(4, item.get("quote_currency").s());
        statement.setObject(5, Instant.ofEpochMilli(
                Long.parseLong(item.get("expiry_time").n())).atOffset(ZoneOffset.UTC));
        statement.setBigDecimal(6, new BigDecimal(item.get("strike").n()));
        statement.setString(7, item.get("option_type").s());
        setDecimal(statement, 8, item.get("bid_price"));
        setDecimal(statement, 9, item.get("ask_price"));
        statement.setBigDecimal(10, new BigDecimal(item.get("mark_price").n()));
        setDecimal(statement, 11, item.get("mark_iv"));
        setDecimal(statement, 12, item.get("underlying_price"));
        setDecimal(statement, 13, item.get("index_price"));
        setDecimal(statement, 14, item.get("open_interest"));
        setDecimal(statement, 15, item.get("volume_24h"));
    }

    /**
     * An attribute the recorder omitted becomes NULL here.
     *
     * <p>DynamoDB has no column to leave empty, so an absent bid is an absent attribute; Postgres
     * has the column and NULL is the right value for it. Writing 0 instead would turn "no bid" into
     * "a bid of zero", which is a different market.
     */
    private static void setDecimal(PreparedStatement statement, int index, AttributeValue value)
            throws SQLException {
        if (value == null || value.n() == null) {
            statement.setNull(index, Types.NUMERIC);
        } else {
            statement.setBigDecimal(index, new BigDecimal(value.n()));
        }
    }

    /**
     * Resumes from the newest hour already stored, or from {@code -DexportFrom}.
     *
     * <p>Re-exports the newest local hour rather than starting after it: that hour may itself have
     * been transferred while the recorder was still writing, and re-reading it is free under
     * {@code ON CONFLICT DO NOTHING}.
     */
    private static Instant startHour(Connection connection) throws SQLException {
        String override = System.getProperty("exportFrom");
        if (override != null && !override.isBlank()) {
            return Instant.parse(override).truncatedTo(ChronoUnit.HOURS);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT max(snapshot_time) FROM deribit_option_quote");
             var results = statement.executeQuery()) {
            if (results.next() && results.getTimestamp(1) != null) {
                return results.getTimestamp(1).toInstant().truncatedTo(ChronoUnit.HOURS);
            }
        }
        // Empty archive: the TTL window is the most that could possibly be there.
        return Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofDays(90));
    }
}
