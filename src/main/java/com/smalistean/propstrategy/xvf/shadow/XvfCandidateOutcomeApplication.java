package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;

import java.time.Clock;
import java.util.List;

/** One-shot scheduled capture of due XVF shadow outcomes. Public APIs only; no trading keys. */
public final class XvfCandidateOutcomeApplication {

    private XvfCandidateOutcomeApplication() {
    }

    public static void main(String[] args) {
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        int limit = Integer.getInteger("xvfOutcomeLimit", 100);
        int toleranceSeconds = Integer.getInteger("xvfOutcomeCaptureToleranceSeconds", 600);
        PostgresXvfCandidateOutcomeRepository repository =
                new PostgresXvfCandidateOutcomeRepository(database);
        XvfCandidateOutcomeService service = new XvfCandidateOutcomeService(
                repository,
                List.of(new BinanceXvfVenueSnapshotSource(), new BybitXvfVenueSnapshotSource(),
                        new HyperliquidXvfVenueSnapshotSource()),
                Clock.systemUTC(), toleranceSeconds);

        List<XvfCandidateOutcome> outcomes = service.captureDue(limit);
        long complete = outcomes.stream()
                .filter(value -> value.captureStatus() == XvfCandidateOutcome.CaptureStatus.COMPLETE)
                .count();
        System.out.printf("XVF outcomes: %d attempts, %d complete, %d incomplete%n",
                outcomes.size(), complete, outcomes.size() - complete);
        System.out.println("No orders placed.");
    }
}
