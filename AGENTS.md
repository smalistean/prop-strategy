# Prop Strategy Engineering Rules

These rules apply to the entire repository. Update this file when a technical
decision changes so it remains the default source of truth for future work.

## Development approach

- Work incrementally and keep each step small enough to run and verify from the
  terminal.
- Do not implement later roadmap phases before the current phase has been
  reviewed and verified.
- Prefer simple, explicit solutions over adding frameworks prematurely.
- Keep this as a single Maven module. Preserve separation between downloading,
  persistence, feature generation, strategy logic, backtesting, statistics,
  and visualization through Java packages.
- Run the relevant Maven tests or build after each implementation change.
- Do not commit or push unless explicitly requested.

## Java

- Use Java 25.
- Use the JDK installed at `/opt/homebrew/opt/openjdk@25` when the default JDK
  is older.
- Use Maven for dependency and build management.
- Use modern Java features when they improve readability.
- Use `BigDecimal` for prices, quantities, monetary amounts, and calculations
  where floating-point rounding would be inappropriate.
- Use `Instant` for exchange timestamps and store/interpret them as UTC.
- Use Lombok where it removes repetitive boilerplate and keeps the code clear,
  such as builders, constructors, loggers, and conventional DTO accessors.
- Do not use Lombok when a Java record or a small explicit implementation is
  clearer.

## Binance market data

- This project targets Binance USD(S)-M Futures, not Binance Spot.
- Use documented Futures endpoints under `https://fapi.binance.com`.
- Use REST for historical downloads. Add WebSocket streams later for live,
  incremental updates.
- Public market-data requests must not require or embed API credentials.
- Use Java `HttpClient` and Jackson unless requirements justify adopting a
  dedicated Binance SDK.
- Handle request timeouts, HTTP errors, Binance error responses, rate limits,
  retries, and pagination explicitly.
- Keep API response DTOs separate from database entities when their shapes or
  responsibilities differ.

## Database

- Use PostgreSQL as the database.
- Use migrations for schema changes; do not rely on automatic schema creation
  in production workflows.
- Design and review the schema before implementing persistence.
- Use appropriate uniqueness constraints so repeated market-data downloads are
  idempotent.
- Index columns used to locate time-series data, including symbol, interval,
  and open time as appropriate.
- Store exchange timestamps in UTC using PostgreSQL timestamp types with clear
  timezone semantics.
- Do not introduce another database or a CSV-based persistence path as the
  primary architecture unless explicitly requested.

## Quality and safety

- Validate external input such as symbols, intervals, time ranges, and limits.
- Add unit tests for parsing and business logic, and integration tests for
  database behavior when persistence is introduced.
- Avoid tests that depend directly on the live Binance API; use fixtures or a
  mock HTTP server for repeatable automated tests.
- Never commit API keys, database passwords, connection strings containing
  secrets, downloaded market datasets, or generated build artifacts.
- Read secrets from environment variables or an approved secret-management
  mechanism when authenticated APIs are introduced.
