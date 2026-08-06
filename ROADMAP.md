# Trading Strategy Research Project - Data Collection Roadmap

## Objective

Build a historical market database and backtesting framework to develop
statistically validated strategies for a prop trading challenge.

## Market

-   Use Binance USDⓈ-M Futures market data, not Binance Spot data.
-   Use the Futures REST API (`https://fapi.binance.com`) for historical
    downloads.
-   Add Futures WebSocket streams later for incremental live updates.

------------------------------------------------------------------------

# Phase 1 -- Download Historical Klines (Highest Priority)

Start with a small terminal preview of historical Futures klines. Once the
response and fields are verified, design the PostgreSQL schema and persistence.

## Progress

-   [x] Confirm Binance USDⓈ-M Futures as the market-data source.
-   [x] Retrieve and print a small historical kline sample.
-   [x] Parse every required kline field.
-   [x] Import and verify three years of BTCUSDT for 1m, 5m, 15m, and 1h.
-   [x] Add production pagination, retries, pacing, and resumable batches.
-   [ ] Download at least three years for ETHUSDT and any later symbols
    (explicitly deferred while the BTCUSDT vertical slice is built).

## Symbols

-   BTCUSDT
-   ETHUSDT
-   (Later) SOLUSDT
-   (Later) BNBUSDT

## Timeframes

-   1m
-   5m
-   15m
-   1h

## History

-   At least **3 years**

## Fields

-   Open time
-   Open
-   High
-   Low
-   Close
-   Volume
-   Quote asset volume
-   Number of trades
-   Taker buy base volume
-   Taker buy quote volume

------------------------------------------------------------------------

# Phase 2 -- Store Data

-   [x] Use PostgreSQL 17 and manage schema changes with Flyway.
-   [x] Create one unified kline table with an interval column.
-   [x] Enforce uniqueness on `(symbol, interval, open_time)`.
-   [x] Insert a small Futures sample with an idempotent upsert.
-   [x] Perform the complete BTCUSDT historical import.
-   [x] Append new closed BTCUSDT candles incrementally.

------------------------------------------------------------------------

# Phase 3 -- Download Supporting Market Data

## Required

-   [x] Funding rates: store three years for BTCUSDT and support incremental
    reruns.
-   [x] Open Interest: store the latest available month at 5m resolution.
-   [x] Global Long/Short Ratio: store the latest available 30 days at 5m
    resolution.
-   [x] Top Trader Long/Short Ratio: store the latest available 30 days at 5m
    resolution; requires a Binance API key.
-   [x] Top Trader Position Ratio: store the latest available 30 days at 5m
    resolution; requires a Binance API key.

Binance limits the four statistical datasets above to the latest month or
30 days.

## Optional

-   Mark Price
-   Index Price

------------------------------------------------------------------------

# Phase 4 -- Generate Features

Start with BTCUSDT 15m. Add indicators only when a strategy needs them instead
of calculating the entire original wish list upfront.

## Timing contract

-   [x] A feature row is available only when its candle has closed.
-   [x] Supporting data must have a timestamp at or before that candle close.
-   [x] A signal based on a feature row may execute no earlier than the next
    candle.
-   [x] Warm-up candles do not produce feature rows or signals.

## Initial price and volume slice

-   [x] Load BTCUSDT 15m candles chronologically from PostgreSQL.
-   [x] Candle return percentage.
-   [x] EMA 20 and EMA 50.
-   [x] Wilder RSI 14 and ATR 14.
-   [x] Rolling 20-period volatility and volume ratio.
-   [x] Body, upper-wick, and lower-wick percentages.

## Futures context

-   [x] Align the latest known funding rate without look-ahead bias.
-   [x] Align 5m open interest and calculate its percentage change.
-   [x] Align global, top-account, and top-position trader ratios.
-   [x] Preserve missing context as missing rather than inventing values.

## Delivery

-   [x] Generate features in memory initially; do not persist a feature table.
-   [x] Add deterministic tests for formulas, warm-up, and timestamp alignment.
-   [x] Print a small recent feature sample in the terminal.
-   [x] Keep the feature output directly usable by the Phase 5 backtester.

ADX, MACD, previous-day levels, pattern flags, multi-timeframe context, and
feature persistence remain candidates for later strategies.

------------------------------------------------------------------------

# Phase 5 -- Backtesting Engine

## Extensibility and configuration

-   [x] Keep engine settings separate from strategy-specific settings.
-   [x] Select strategies through a registry/factory by `strategy.type`.
-   [x] Let each strategy parse and validate its own typed configuration.
-   [x] Use parameterized feature keys so indicator periods are configurable.
-   [x] Let each strategy declare its required features.
-   [x] Use rich enter/exit/hold decisions instead of shared BUY/SELL rules.

## Execution and accounting

-   [x] Load historical BTCUSDT candles from PostgreSQL.
-   [x] Execute a close-derived signal no earlier than the next candle open.
-   [x] Support long and short positions with one position at a time.
-   [x] Size positions by configured equity risk and cap configured leverage.
-   [x] Execute ATR stops, reward/risk targets, strategy exits, and final exits.
-   [x] Assume the stop occurs first when stop and target touch in one candle.
-   [x] Apply adverse slippage and taker fees on both entry and exit.
-   [x] Apply funding cash flows while a Futures position is open.
-   [x] Track gross PnL, fees, funding, slippage costs, and net PnL per trade.
-   [x] Mark equity each candle and enforce configured prop challenge limits.

## Baseline

-   [x] Add a configurable EMA pullback continuation strategy.
-   [x] Run a database-backed BTCUSDT 15m end-to-end backtest.
-   [x] Produce a performance report and inspect individual trades.

The baseline validates the engine; it is not considered a profitable strategy.
New strategy types should add their own factory, typed config, required feature
keys, and tracked experiment file without changing the execution engine.

------------------------------------------------------------------------

# Phase 6 -- Performance Metrics

## Dataset discipline

-   [x] Training: `[2023-08-07, 2025-08-07)` UTC.
-   [x] Validation: `[2025-08-07, 2026-02-07)` UTC.
-   [x] Final test: `[2026-02-07, 2026-08-07)` UTC.
-   [x] Load pre-period candles only as indicator warm-up; exclude them from
    trading and metrics.
-   [x] Lock final-test execution behind explicit `confirmFinalTest=true`.

## Reported metrics

-   [x] Net profit and return percentage.
-   [x] Win rate and winning/losing trade counts.
-   [x] Average win, average loss, and expectancy per trade.
-   [x] Profit factor.
-   [x] Maximum absolute and percentage drawdown.
-   [x] Number of trades.
-   [x] Total fees, funding PnL, and modeled slippage cost.
-   [x] Prop-rule termination status.
-   [ ] Sharpe ratio (optional; defer until return sampling is specified).

------------------------------------------------------------------------

# Phase 7 -- Strategy Search

-   Automatically test parameter combinations.
-   Reject strategies with excessive drawdown.
-   Report the four six-month training subperiods and reject candidates whose
    result depends on one exceptional section.
-   Compare frozen candidates on validation before opening the final test.
-   Keep strategies that remain profitable across different market
    conditions.

------------------------------------------------------------------------

# Phase 8 -- Prop Challenge Validation

Verify that the strategy: - Never exceeds the daily loss limit. - Never
exceeds the maximum drawdown. - Can realistically reach the profit
target. - Works in bull, bear, and sideways markets.

------------------------------------------------------------------------

# Suggested Java Package Structure

``` text
prop-strategy/
│
└── src/main/java/com/smalistean/propstrategy/
    ├── marketdownloader/
    ├── database/
    ├── feature/
    ├── backtester/
    ├── strategy/
    ├── statistics/
    └── visualization/ (optional)
```

------------------------------------------------------------------------

# Future Enhancements

-   Machine learning feature ranking
-   Walk-forward optimization
-   Monte Carlo analysis
-   Portfolio of multiple strategies
-   Risk-based position sizing
