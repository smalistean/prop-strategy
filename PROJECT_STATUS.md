# Project Status

Last updated: 2026-08-06

## Current position

Phase 1's small Binance Futures API proof and Phase 2's small PostgreSQL
persistence proof are complete. The project is not yet ready for a full
historical import.

## Completed

- The project uses one Maven module and Java 25.
- Binance USD(S)-M Futures is the selected market; Spot is out of scope.
- The Futures REST API retrieves BTCUSDT klines from one year ago.
- Jackson parses all required kline fields.
- PostgreSQL 17.10 is installed locally with Homebrew and runs as a service.
- The local development database is named `prop_strategy`.
- Flyway migration `V1__create_futures_kline.sql` creates one unified
  `futures_kline` table.
- `(symbol, interval, open_time)` is the table's primary key.
- The JDBC repository performs transactional, idempotent batch upserts.
- The persistence example fetched and stored 10 BTCUSDT 1h candles.
- Running the example twice still produced exactly 10 rows.
- The project builds successfully with JDK 25.

## Current database

- PostgreSQL: 17.10
- Service: `postgresql@17`
- Database: `prop_strategy`
- Schema version: Flyway V1
- Sample data: 10 BTCUSDT 1h candles beginning around one year ago

The local defaults use the current macOS username and passwordless local
development authentication. Override them when necessary with `DB_URL`,
`DB_USER`, and `DB_PASSWORD`.

## Verification commands

``` shell
brew services list

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn test

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.database.KlinePersistenceApplication

/opt/homebrew/opt/postgresql@17/bin/psql -d prop_strategy \
  -c "SELECT symbol, interval, COUNT(*) FROM futures_kline GROUP BY symbol, interval;"
```

## Next step

Before importing years of data, add repeatable tests for Binance response
parsing and PostgreSQL upserts, then make the downloader write paginated batches
directly to PostgreSQL with retry and rate-limit handling.

Do not begin the complete historical import until that path is verified on a
small bounded date range.
