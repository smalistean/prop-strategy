# Project Status

Last updated: 2026-08-06

## Current position

The BTCUSDT and ETHUSDT historical pipelines now contain at least three years
of 1m, 5m, 15m, and 1h data, with reusable incremental closed-candle
synchronization for both. Phase 3 is complete for both symbols: three years of funding
rates and the full Binance-retained window of 5m supporting statistics are
stored. The agreed initial Phase 4 BTCUSDT 15m feature slice is complete
and ready to feed the backtester. Phase 5 now has a configurable, extensible
backtesting engine and one deliberately unoptimized EMA-pullback baseline.
Phase 6 metrics and chronological dataset controls are complete. Phase 7 now
has three materially different strategy candidates and a shared automated
acceptance gate; all initial training baselines were rejected. The RSI/ATR
candidate is the closest so far. Validation and final-test results remain
unopened.

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
- ETHUSDT was imported from 2023-08-06 UTC through the latest closed candle and
  verified for all four intervals: 1,579,518 x 1m, 315,903 x 5m, 105,301 x
  15m, and 26,325 x 1h.
- The incremental sync resumes after each interval's latest database candle,
  excludes the currently open candle, and verifies the final timestamp.
- An incremental run appended 52 new closed candles: 42 x 1m, 8 x 5m, and
  2 x 15m; 1h was already current.
- Flyway migrations V2 and V3 create `futures_funding_rate` and preserve
  unavailable historical mark prices as SQL `NULL`.
- The funding-rate importer is paginated, paced, retryable, idempotent, and
  resumes after the latest stored funding event.
- Three years of BTCUSDT funding-rate history were imported and verified.
- ETHUSDT funding history contains 3,291 verified events from 2023-08-06
  through 2026-08-06.
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
- ETHUSDT also has 8,640 rows each for 5m open interest, global account ratio,
  top-trader account ratio, and top-trader position ratio.
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
- Forty-three tests cover downloads, cursors, formulas, warm-up, chronology,
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
- A Donchian breakout candidate uses prior-candle entry/exit channels, volume
  confirmation, ATR-based stops, a configurable reward/risk target, and a
  maximum holding period. Its rolling channels and volume baseline explicitly
  exclude the current candle to prevent look-ahead.
- Acceptance thresholds are tracked independently from strategy and engine
  parameters and run by default for every training backtest. The evaluator
  checks overall profitability, profit factor, drawdown, trade count, four
  six-month subperiods, concentration of profits, average win/loss ratio, and
  a 1.5x fee/slippage stress run.
- The initial Donchian training run evaluated 70,163 bars and failed seven of
  eight acceptance checks: -9.05% return, 0.435 profit factor, 10.07% maximum
  drawdown, 33 trades, and only one profitable subperiod. Its 1.956 average
  win/loss ratio passed, but stressed net profit was -9,727.29. The baseline is
  rejected without opening validation or final-test data.
- The volatility-compression breakout requires a low previous-candle
  Bollinger-bandwidth percentile followed by a range break, ATR expansion, and
  above-average volume. Its initial training run evaluated 70,087 bars and
  failed seven of eight checks: -9.89% return, 0.271 profit factor, 10.14%
  maximum drawdown, 25 trades, and one profitable subperiod. Average win/loss
  ratio was 1.424, while stressed net profit was -10,706.52. It is rejected.
- The RSI/ATR mean-reversion strategy trades fresh RSI extreme crossings only
  with the EMA 200 trend, rejects fast ATR expansion, uses an ATR stop, and
  exits at RSI mean reversion or trend failure. It completed all 70,004
  training bars with -2.55% return, 0.755 profit factor, 3.80% drawdown, and 34
  trades. Two subperiods were profitable. It passed only the drawdown criterion
  and is rejected, but is the strongest baseline so far. Fees were 4,275.18
  and modeled slippage was 1,710.07.
- A reusable diagnostic report now separates raw price PnL, funding, fees,
  slippage, and net PnL; groups trades by side, exit reason, month, and prior
  24-hour trend regime; and reports holding time, MFE, MAE, consecutive losses,
  break-even cost, and execution-model details.
- The taker-only diagnostics showed that RSI/ATR had a positive raw edge and
  justified implementing maker execution rather than assuming every limit
  filled.
- The engine now resolves maker orders against 1m candles. It requires strict
  trade-through, expires entries after five minutes, charges 2 bps maker fees,
  uses maker targets, and keeps stops at 5 bps taker fees plus 2 bps slippage.
  Strategy exits try maker and use a timed taker fallback. Fill and expiry
  counts are reported.
- BTC RSI/ATR now returns +1.05% with 32/35 maker entries filled, 3.25% maximum
  drawdown, and 1.125 profit factor. It still fails acceptance because it has
  only 32 trades, one profitable subperiod, concentrated profit, and negative
  stressed-cost PnL.
- Acceptance has separate frequency profiles. The high-frequency profile
  requires at least 1,460 filled trades over two training years (approximately
  two per day). The default low-frequency profile requires at least 60 trades
  for strategies expected to trade less than daily. Both retain the same
  profitability, drawdown, subperiod-stability, win/loss, and cost-stress gates.
- A new intraday flat-market mean-reversion baseline uses RSI 7, EMA 20 slope
  and deviation, ATR expansion filtering, ATR protection, short holding time,
  and real maker execution. It was rejected on both symbols. BTC reached
  maximum drawdown at -9.73% after 48 trades; ETH reached it at -10.22% after
  51. The four separately restarted subperiods totaled only 277 BTC and 300 ETH
  trades, around 0.4 per day, and raw PnL was negative before costs.
- With real maker fills and conservative protective-order sequencing, ETH
  compression is -3,325.16 net. ETH RSI/ATR has +770.56 zero-cost PnL and
  -1,069.35 net. Both fail acceptance.
- Directional behavior differs by symbol: BTC RSI/ATR longs were profitable
  and shorts were poor; ETH RSI/ATR shorts earned 1,317.49 and longs lost
  2,386.83. This argues against hard-coding one directional bias
  across symbols.
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
- ETHUSDT 1m: 1,579,518 rows
- ETHUSDT 5m: 315,903 rows
- ETHUSDT 15m: 105,301 rows
- ETHUSDT 1h: 26,325 rows
- Total ETHUSDT Futures klines: 2,027,047 rows
- BTCUSDT funding rates: 3,288 rows
- ETHUSDT funding rates: 3,291 rows
- Funding-rate window: 2023-08-07 through 2026-08-06
- Historical funding rows without a Binance mark price: 256 (stored as NULL)
- BTCUSDT 5m open-interest statistics: 8,640 rows
- BTCUSDT 5m global account ratios: 8,640 rows
- BTCUSDT 5m top-trader account ratios: 8,640 rows
- BTCUSDT 5m top-trader position ratios: 8,640 rows
- ETHUSDT 5m open-interest statistics: 8,640 rows
- ETHUSDT 5m global account ratios: 8,640 rows
- ETHUSDT 5m top-trader account ratios: 8,640 rows
- ETHUSDT 5m top-trader position ratios: 8,640 rows
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
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.EthHistoricalImportApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcIncrementalSyncApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.EthIncrementalSyncApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcFundingRateImportApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.EthFundingRateImportApplication

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

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DstrategyConfig=config/backtests/donchian-breakout.properties \
  -DbacktestDataset=TRAINING

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DstrategyConfig=config/backtests/volatility-compression-breakout.properties \
  -DbacktestDataset=TRAINING

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DstrategyConfig=config/backtests/rsi-atr-mean-reversion.properties \
  -DbacktestDataset=TRAINING \
  -Ddiagnostics=true

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DstrategyConfig=config/backtests/intraday-flat-mean-reversion.properties \
  -DbacktestDataset=TRAINING \
  -Ddiagnostics=true

PGPASSWORD=$DB_PASSWORD /opt/homebrew/opt/postgresql@17/bin/psql \
  -h localhost -U "$DB_USER" -d "$DB_NAME" \
  -c "SELECT symbol, interval, COUNT(*) FROM futures_kline GROUP BY symbol, interval;"
```

## Next step

Do not increase the rejected intraday strategy's frequency by simply loosening
thresholds: its current raw trades already lose. Next, test a training-only
long-biased BTC RSI/ATR flat-regime variant and add controlled parameter search.
Keep validation and final test closed.
