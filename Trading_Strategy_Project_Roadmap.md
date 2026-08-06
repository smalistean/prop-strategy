# Trading Strategy Research Project - Data Collection Roadmap

## Objective

Build a historical market database and backtesting framework to develop
statistically validated strategies for a prop trading challenge.

------------------------------------------------------------------------

# Phase 1 -- Download Historical Klines (Highest Priority)

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

-   Store data in PostgreSQL.
-   Create tables per timeframe or one unified table with a timeframe
    column.
-   Add an index on `(symbol, open_time)`.
-   Perform one historical import, then append new candles
    incrementally.

------------------------------------------------------------------------

# Phase 3 -- Download Supporting Market Data

## Required

-   Funding rates
-   Open Interest
-   Global Long/Short Ratio
-   Top Trader Long/Short Ratio
-   Top Trader Position Ratio

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

# Suggested Java Project Structure

``` text
prop-strategy/
│
├── market-downloader/
├── database/
├── feature-generator/
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
