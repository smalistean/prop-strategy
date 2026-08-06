package com.smalistean.propstrategy.database;

public record DatabaseConfig(String url, String user, String password) {

    public static DatabaseConfig fromEnvironment() {
        return new DatabaseConfig(
                envOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/prop_strategy"),
                envOrDefault("DB_USER", System.getProperty("user.name")),
                envOrDefault("DB_PASSWORD", "")
        );
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
