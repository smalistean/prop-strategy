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
-   [x] Download and verify at least three years for ETHUSDT at every selected
    interval.
-   [ ] Download later symbols only after BTCUSDT/ETHUSDT research justifies
    expanding the universe.

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
-   [x] Reuse historical and incremental persistence for ETHUSDT.

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
-   [x] Repeat funding and all retained supporting datasets for ETHUSDT.

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
-   [x] Model post-only maker entries and ordinary exits using subsequent 1m
    candles. Require price to trade through the limit; keep protective stops as
    taker orders and count unfilled orders as missed trades.

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

-   [x] Add a Donchian breakout strategy with prior-candle price channels,
    volume confirmation, ATR risk, an asymmetric target, and a channel/time
    exit.
-   [x] Store its parameters separately from the execution-engine settings.
-   [x] Define machine-readable acceptance criteria before parameter search.
-   [x] Report the four six-month training subperiods and reject candidates whose
    result depends on one exceptional section.
-   [x] Stress the candidate with fees and slippage multiplied by 1.5.
-   [x] Run and reject the initial Donchian baseline on training data.
-   [x] Add and reject a volatility-compression breakout baseline using the
    previous candle's Bollinger-bandwidth percentile, ATR expansion, and
    volume confirmation.
-   [x] Add and reject an RSI/ATR mean-reversion baseline with an EMA 200 trend
    filter, volatility-expansion guard, and RSI mean exit.
-   [x] Add and reject an intraday flat-market mean-reversion baseline designed
    for higher frequency with RSI 7, EMA 20, ATR, and real maker fills.
-   [x] Add diagnostic reports by side, exit reason, calendar period, market
    regime, and gross-versus-cost performance.
-   [ ] Automatically test controlled parameter combinations.
-   [ ] Compare frozen candidates on validation before opening the final test.
-   [ ] Keep strategies that remain profitable across different market
    conditions.

## Strategy acceptance criteria

Two profiles are tracked separately and selected with
`-DacceptanceConfig=<file>` for every strategy run on the training dataset:

-   `config/backtests/acceptance-high-frequency.properties` requires at least
    1,460 filled trades over two training years, approximately two per day.
-   `config/backtests/acceptance-low-frequency.properties` requires at least
    60 filled trades as a basic evidence floor for strategies expected to trade
    less than once per day. This is the default profile.

A candidate passes only when every check in its selected profile passes:

-   Net profit is positive and profit factor is at least 1.10.
-   Maximum drawdown is no more than 10%, and the trade count reaches the
    selected profile's minimum.
-   At least three of the four six-month training subperiods are profitable.
-   No single positive subperiod supplies more than 60% of total positive
    subperiod profit.
-   Average win divided by average loss is at least 1.20.
-   Net profit remains positive with fees and slippage multiplied by 1.5.

The initial parameters failed seven of eight checks: -9.05% return, 0.435
profit factor, 10.07% maximum drawdown, 33 trades, one profitable subperiod,
and negative stressed-cost profit. Only average win/loss ratio passed. This is
a rejected baseline; validation and final-test data remain unopened.

The initial volatility-compression parameters also failed seven of eight
checks: -9.89% return, 0.271 profit factor, 10.14% maximum drawdown, 25 trades,
one profitable subperiod, and negative stressed-cost profit. Only average
win/loss ratio passed. It is also rejected without opening validation or final
test data.

The initial RSI/ATR mean-reversion parameters failed seven of eight checks,
but improved materially on the breakout baselines: -2.55% return, 0.755 profit
factor, 3.80% maximum drawdown, 34 trades, and two profitable subperiods. It
completed the full training period and passed the drawdown criterion. Modeled
fees and slippage were 5,985.26, making execution-cost diagnostics a priority.

## Initial taker-only diagnostic findings

-   EMA pullback, Donchian breakout, and volatility-compression breakout have
    negative price PnL before costs. Maker execution cannot rescue their
    current signals.
-   RSI/ATR mean reversion has positive zero-cost PnL of 3,431.80, but loses
    5,985.26 to the current taker-fee and slippage model.
-   RSI/ATR longs made 3,553.43 net while shorts lost 6,106.89. Flat-regime raw
    PnL was positive, but current costs reduced it to -580.12 net.
-   The original optimistic maker counterfactual motivated a real 1m execution
    model; its values are superseded by the results below.

## One-minute maker execution

-   Maker orders are offset 1 bps from the next 15m open and live for five
    minutes.
-   A buy fills only when a later 1m low is strictly below its limit; a sell
    fills only when a later 1m high is strictly above its limit. A touch does
    not count, and expired entries become missed trades.
-   Take-profit limits use maker fees. Stops use taker fees and adverse
    slippage. Strategy exits try maker first and fall back to taker after five
    minutes.
-   BTC RSI/ATR becomes +1.05% net with 32 of 35 entries filled, but still
    fails acceptance: only one profitable subperiod, 32 trades, concentrated
    profit, and -134.51 stressed-cost PnL.

## Intraday frequency baseline

The initial intraday flat-market strategy failed immediately on both symbols:

-   BTC stopped at maximum drawdown with -9.73%, 48 completed trades, 0.346
    profit factor, and negative raw PnL. Its four independent subperiod runs
    produced 277 trades in total.
-   ETH stopped at maximum drawdown with -10.22%, 51 completed trades, 0.320
    profit factor, and negative raw PnL. Its four independent subperiod runs
    produced 300 trades in total.
-   This is only about 0.4 trades per day when measured across independent
    subperiods, far below the desired two per day. Loosening the entry rules is
    not justified because the existing trades already lack a pre-cost edge.

## ETHUSDT comparison

-   All initial strategy configurations fail shared training acceptance on
    ETHUSDT as well.
-   EMA pullback and Donchian remain negative before costs.
-   With real maker fills, ETH volatility compression remains -3,325.16 net
    and fails acceptance.
-   With real maker fills, ETH RSI/ATR produces 770.56 zero-cost PnL and
    -1,069.35 net PnL. It also fails acceptance.
-   BTC RSI/ATR favors longs; ETH RSI/ATR shorts earned 1,316.63 while ETH
    longs lost 2,386.83. Strategy direction must therefore be
    symbol-specific or driven by a validated regime rule.

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
