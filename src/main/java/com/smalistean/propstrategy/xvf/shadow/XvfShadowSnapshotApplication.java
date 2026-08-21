package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Captures one XVF shadow decision and exits. Reporting only: this package has no order gateway.
 *
 * <pre>
 *   DB_URL=... DB_USER=... DB_PASSWORD=... \
 *   java -DxvfCapital=10000 -DxvfCodeRevision=$(git rev-parse HEAD) ...XvfShadowSnapshotApplication
 *
 *   Optional declared per-venue capital (all three are required when any is supplied):
 *   -DxvfShadowCapital.binance=4000
 *   -DxvfShadowCapital.bybit=2500
 *   -DxvfShadowCapital.hyperliquid=3500
 * </pre>
 */
public final class XvfShadowSnapshotApplication {

    private XvfShadowSnapshotApplication() {
    }

    public static void main(String[] args) {
        XvfShadowConfiguration configuration = XvfShadowConfiguration.fromSystemProperties();
        LocalDate today = LocalDate.now(configuration.productionZone());
        LocalDate asOf = LocalDate.parse(System.getProperty("xvfAsOf", today.toString()));
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);

        PostgresXvfSignalRepository repository = new PostgresXvfSignalRepository(database);
        XvfShadowSnapshotService service = new XvfShadowSnapshotService(
                date -> {
                    XvfSignalEngine.requireFreshFunding(database, date);
                    return XvfSignalEngine.evaluate(database, date);
                },
                new PostgresXvfFundingSnapshotSource(database),
                List.of(
                        new BinanceXvfVenueSnapshotSource(),
                        new BybitXvfVenueSnapshotSource(),
                        new HyperliquidXvfVenueSnapshotSource()),
                new XvfShadowDecisionPlanner(),
                repository::insert,
                configuration,
                Clock.systemUTC());

        XvfSignalRun run = service.capture(asOf);
        long scorable = run.candidates().stream()
                .filter(candidate -> candidate.scoreStatus() == XvfSignalRun.ScoreStatus.SCORABLE)
                .count();
        long shadowSelected = run.candidates().stream()
                .filter(candidate -> candidate.ranks().shadowBookRank() != null)
                .count();
        System.out.printf("XVF shadow run %s: %s, %d alternatives, %d scorable, %d selected%n",
                run.signalRunId(), run.captureStatus(), run.candidates().size(), scorable,
                shadowSelected);
        System.out.println("No orders placed.");
        if (run.captureStatus() != XvfSignalRun.CaptureStatus.COMPLETE) {
            throw new IllegalStateException("XVF shadow capture is " + run.captureStatus()
                    + ": " + run.failureDetail());
        }
    }
}
