# Project Status

Last updated: 2026-08-06

## Current position

The BTCUSDT kline pipeline is complete for the current scope: three years of
1m, 5m, 15m, and 1h data plus incremental closed-candle synchronization. ETH
is explicitly deferred. Phase 3 is complete for BTCUSDT: three years of
funding rates and the full Binance-retained window of 5m supporting statistics
are stored. The agreed initial Phase 4 BTCUSDT 15m feature slice is complete
and ready to feed the backtester. Phase 5 now has a configurable, extensible
backtesting engine and one deliberately unoptimized EMA-pullback baseline.
Phase 6 metrics and chronological dataset controls are complete; validation and
final-test results remain unopened.

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
- The production importer writes 1,000-row batches directly to PostgreSQL.
- Imports are resumable when existing rows form a complete prefix; sparse or
  gapped data causes a safe idempotent restart from the requested beginning.
- Binance calls are paced and retry transient I/O failures, HTTP 418/429, and
  HTTP 5xx responses with backoff.
- Sixteen parsing, pagination, cursor, and interval-alignment unit tests pass.
- Three complete years of BTCUSDT were imported and verified for all four
  selected intervals.
- The incremental sync resumes after each interval's latest database candle,
  excludes the currently open candle, and verifies the final timestamp.
- An incremental run appended 52 new closed candles: 42 x 1m, 8 x 5m, and
  2 x 15m; 1h was already current.
- Flyway migrations V2 and V3 create `futures_funding_rate` and preserve
  unavailable historical mark prices as SQL `NULL`.
- The funding-rate importer is paginated, paced, retryable, idempotent, and
  resumes after the latest stored funding event.
- Three years of BTCUSDT funding-rate history were imported and verified.
- Binance limits open-interest and trader-ratio REST history to approximately
  one month. The top-trader account and position endpoints also require a
  Binance API key.
- Flyway migration V4 creates separate open-interest and typed trader-ratio
  tables with idempotent primary keys.
- Supporting-statistics pagination runs backward because Binance returns the
  newest records up to `endTime`; this behavior was verified against the live
  API and prevents older pages from being skipped.
- The complete rolling 30-day BTCUSDT window is stored at 5m resolution for
  open interest, global account ratio, top-trader account ratio, and top-trader
  position ratio. Each dataset has zero non-5m gaps.
- The project builds successfully with JDK 25.
- Recent BTCUSDT 15m candles load from PostgreSQL in chronological order.
- `FeatureGenerator` calculates EMA 20/50, Wilder RSI 14, Wilder ATR 14,
  20-period return volatility and volume ratio, candle return, and candle-shape
  percentages in one chronological pass.
- `FeatureRow` records candle close availability and the earliest next-candle
  execution time. Warm-up ends only after 50 candles.
- Funding, open interest, and trader ratios align using the latest observation
  at or before candle close; missing context remains `null`.
- A live preview generated 151 feature rows from 200 recent 15m candles and
  printed the latest 10 with all Futures context populated.
- Twenty-eight tests cover downloads, cursors, formulas, warm-up, chronology,
  no-look-ahead alignment, long/short execution, fees, stops, and funding.
- Engine and strategy configuration live in separate tracked properties files.
- A strategy registry selects factories by type; each factory owns typed
  parameters, and each strategy declares parameterized feature requirements.
- `ParameterizedFeatureGenerator` supports arbitrary EMA, RSI, and ATR periods
  requested by a strategy without changing `FeatureSnapshot`.
- Strategy decisions express long/short entries with stop and target distances,
  explicit exits, or holds.
- Signals derived at candle close fill no earlier than the next candle open.
- The execution engine supports long and short positions, risk sizing, leverage
  caps, conservative same-bar stop/target ordering, adverse slippage, two-sided
  taker fees, funding cash flows, time/strategy exits, and prop-rule termination.
- Trade records separate gross PnL, entry/exit fees, funding, slippage costs,
  net PnL, and exit reason.
- The first database-backed run used 3,000 BTCUSDT 15m candles and 2,951
  post-warm-up feature bars. It produced 49 trades and stopped at the configured
  maximum drawdown: -9.95% return, 36.73% win rate, and 0.477 profit factor.
  This rejects the default parameters as a strategy candidate while validating
  the end-to-end engine path.
- Tracked UTC periods are training `[2023-08-07, 2025-08-07)`, validation
  `[2025-08-07, 2026-02-07)`, and final test
  `[2026-02-07, 2026-08-07)`.
- Range loading includes only the preceding candles required for indicator
  warm-up; warm-up bars cannot trade or affect period metrics.
- Final-test mode fails closed unless `-DconfirmFinalTest=true` is supplied.
- Phase 6 reports net profit/return, win rate, win/loss counts, average win and
  loss, expectancy, profit factor, drawdown, trade count, fees, funding,
  slippage costs, and prop termination.
- The untouched training-period run evaluated 70,154 bars. The baseline hit
  maximum drawdown after 27 trades with -10.62% net return, 18.52% win rate,
  -393.20 expectancy, and 0.167 profit factor. Fees were 6,479.38 versus only
  47.69 positive funding PnL, confirming the baseline should be rejected.
- Validation and final-test periods have not been run.

## Current database

- PostgreSQL: 17.10
- Service: `postgresql@17`
- Database: `prop_strategy`
- Schema version: Flyway V4
- BTCUSDT 1m: 1,578,282 rows
- BTCUSDT 5m: 315,656 rows
- BTCUSDT 15m: 105,218 rows
- BTCUSDT 1h: 26,304 rows
- Total Futures klines: 2,025,460 rows
- BTCUSDT funding rates: 3,288 rows
- Funding-rate window: 2023-08-07 through 2026-08-06
- Historical funding rows without a Binance mark price: 256 (stored as NULL)
- BTCUSDT 5m open-interest statistics: 8,640 rows
- BTCUSDT 5m global account ratios: 8,640 rows
- BTCUSDT 5m top-trader account ratios: 8,640 rows
- BTCUSDT 5m top-trader position ratios: 8,640 rows
- Supporting-statistics window: 2026-07-07 18:35 UTC through 2026-08-06
  18:30 UTC
- `futures_kline` table and indexes: approximately 386 MB after import
- Import window: 2023-08-06 through the last closed candle on 2026-08-06
- Full import runtime: 20 minutes 56 seconds

Local credentials belong to the dedicated `prop_strategy_app` role and are
stored in the ignored `.env` file. The application reads `DB_URL`, `DB_USER`,
and `DB_PASSWORD` from its process environment. Binance credentials are stored
separately in ignored `.env.binance`; Phase 3 only sends the API key to the two
read-only market-data endpoints that require it and never uses the secret key.

## Verification commands

``` shell
brew services list

set -a
source .env
set +a

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn test

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.database.KlinePersistenceApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcHistoricalImportApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcIncrementalSyncApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcFundingRateImportApplication

set -a
source .env.binance
set +a

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcSupportingMarketDataImportApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.feature.BtcFeaturePreviewApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DengineConfig=config/backtests/engine.properties \
  -DstrategyConfig=config/backtests/ema-pullback.properties \
  -DbacktestDataset=TRAINING

PGPASSWORD=$DB_PASSWORD /opt/homebrew/opt/postgresql@17/bin/psql \
  -h localhost -U "$DB_USER" -d "$DB_NAME" \
  -c "SELECT symbol, interval, COUNT(*) FROM futures_kline GROUP BY symbol, interval;"
```

## Next step

Begin Phase 7 by designing one materially different strategy and a controlled
parameter-search runner. Report results for the complete training period and
its four six-month subperiods. Keep validation closed until candidate rules and
search ranges are fixed, and keep final test closed until after validation.
