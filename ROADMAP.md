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

## Trend

-   SMA
-   EMA
-   ADX

## Momentum

-   RSI
-   MACD

## Volatility

-   ATR
-   Rolling standard deviation

## Volume

-   Moving average volume
-   Volume spikes
-   Buy/Sell imbalance

## Candlestick Statistics

-   Body %
-   Upper wick %
-   Lower wick %
-   Inside bar
-   Outside bar

## Market Context

-   Distance from previous day high
-   Distance from previous day low
-   Open Interest change
-   Funding rate

------------------------------------------------------------------------

# Phase 5 -- Backtesting Engine

-   Load historical data from PostgreSQL.
-   Execute strategy rules.
-   Simulate trading fees.
-   Simulate slippage.
-   Record every trade.
-   Produce detailed reports.

------------------------------------------------------------------------

# Phase 6 -- Performance Metrics

-   Net Profit
-   Win Rate
-   Average Win
-   Average Loss
-   Profit Factor
-   Maximum Drawdown
-   Sharpe Ratio (optional)
-   Number of Trades

------------------------------------------------------------------------

# Phase 7 -- Strategy Search

-   Automatically test parameter combinations.
-   Reject strategies with excessive drawdown.
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
