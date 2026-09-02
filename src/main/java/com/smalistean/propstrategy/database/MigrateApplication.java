package com.smalistean.propstrategy.database;

/**
 * Applies pending Flyway migrations and exits.
 *
 * <p>Until now migrations were applied as a side effect of whichever importer happened to run next
 * (the hourly LaunchAgents), which meant a freshly written {@code V*.sql} landed on the live database
 * at an unpredictable moment. This gives that step a name so it can be run deliberately, right after
 * the migration is written and reviewed, and so the schema a script is about to write to is known to
 * exist before the script runs.
 *
 * <p>Usage: {@code java -cp target/classes com.smalistean.propstrategy.database.MigrateApplication}
 * with the usual {@code DB_URL} / {@code DB_USER} / {@code DB_PASSWORD} environment.
 */
public final class MigrateApplication {

    private MigrateApplication() {
    }

    public static void main(String[] args) {
        DatabaseMigrator.migrate(DatabaseConfig.fromEnvironment());
        System.out.println("migrations applied");
    }
}
