package com.smalistean.propstrategy.database;

import org.flywaydb.core.Flyway;

public final class DatabaseMigrator {

    private DatabaseMigrator() {
    }

    public static void migrate(DatabaseConfig config) {
        Flyway.configure()
                .dataSource(config.url(), config.user(), config.password())
                .load()
                .migrate();
    }
}
