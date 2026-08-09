# Trading Strategy Research Project - Data Collection Roadmap

The prioritized forward plan and stopping rules are maintained in
`NEXT_RESEARCH_PLAN.md`. This roadmap remains the detailed implementation history.

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
-   [x] Aggregate raw historical trades into reusable 15-minute price bins.
-   [x] Persist BTCUSDT training-period volume-at-price bins in PostgreSQL.
-   [x] Generate causal 1-day, 3-day, and 7-day rolling volume profiles.
-   [x] Expose POC, merged high-volume zone, zone share, aggressor delta, and
    POC stability without current-candle look-ahead.

The initial volume-profile strategy parameters were frozen for the v1 reaction runs.
Any later comparison of price-bin width, rolling horizon, or adjacent-bin threshold
must be recorded as a new training experiment rather than rewriting the v1 result.

## Volume-profile reaction experiments

-   [x] Add a stable-zone breakout with the stop behind the complete zone.
-   [x] Add a two-candle false-breakout/reclaim with the opposite zone edge as target.
-   [x] Add a channel-boundary rejection requiring at least 3R to the opposite edge.
-   [x] Run all three on BTCUSDT training data with aggregate-trade maker fills.
-   [x] Reject all three v1 rules; retain the shared level infrastructure.
-   [ ] Add approach-quality, repeated-penetration, and order-flow absorption diagnostics
    before proposing another entry rule. Do not optimize using validation/final data.

## Apollo methodology translation

-   [x] Review all 44 pages, including annotated chart examples.
-   [x] Document source rules separately from mechanical interpretations.
-   [x] Implement base + POC + confirmed break + first-retest entries.
-   [x] Apply the liquidity-zone-plus-25% stop and minimum 3R target.
-   [x] Run frozen BTCUSDT training diagnostics without opening later datasets.
-   [x] Replace rolling POC with an exact profile over each fixed-window detected base;
    reject the result because the fixed base detector admits ordinary trend pauses.
-   [x] Run the causal 4h-map / 15m sweep-reclaim proxy across all symbols; reject it because
    results were negative and unstable across the unselected universe.
-   [x] Run the stricter fresh-level → sweep → reclaim → separate local-break proxy across all
    symbols; retain it only as an insufficient-sample diagnostic, not an edge.
-   [x] Review `Книга 2.0.pdf`, preserve its base/profile/trap rules in the Apollo notes, and run
    its first explicit configurable-assumption set across all symbols; retain the 10-trade result as
    insufficient evidence rather than tuning it after inspection.
-   [ ] Implement variable-length horizontal bases with body containment, limited drift,
    explicit entrance, and a clean volume-supported exit.
-   [ ] Predeclare and test higher-timeframe direction and long-only sensitivities only
    after base construction is credible.

## Three-level range and adverse scratch

-   [x] Construct L1/L2/L3 from confirmed historical pivot clusters.
-   [x] Buy L2, target 30-40% of L2-L3, and retain the structural L1 stop.
-   [x] Activate a persistent maker scratch after 20% adverse L2-L1 movement and
    require a later 1m aggregate-trade fill.
-   [x] Test 15m and a 5m version reaching approximately one trade per day.
-   [x] Test waiting for the adverse sweep and L2 reclaim before entry.
-   [x] Reject the family: all variants have negative raw expectancy and every 5m
    month lost. Do not optimize thresholds.
-   [x] Research frequency through a frozen cross-sectional strategy over multiple
    liquid symbols rather than forcing additional BTCUSDT entries; reject the v1 1h-strength /
    15m-pullback rule because it lost before costs across the full universe.

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
-   [x] Add and reject a frozen long-only cross-sectional strategy: completed 1h relative-strength
    ranking, a BTC market-regime filter, and 15m EMA pullback/reclaim entries across eight symbols.
-   [x] Implement and run a frozen BTC liquidity-sweep reversal proxy with confirmed pivot pools,
    sweep/reclaim, local-break and volume confirmation, an unswept-pool constraint, and a 3R
    opposing-pool target; reject it as too restrictive after it generated zero training entries.
-   [x] Add diagnostic reports by side, exit reason, calendar period, market
    regime, and gross-versus-cost performance.
-   [x] Add and evaluate a cost-adjusted break-even stop using conservative 1m
    sequencing; retain it as an opt-in feature after all tested triggers reduced
    BTC RSI/ATR training profit.
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

# Return-improvement research sequence

The aspirational return target is 5% per month, but candidates must first show
a repeatable net edge without relaxing drawdown, stability, or cost-stress
requirements. Work through these experiments sequentially and record the result
before proceeding:

1.  **Long-only BTC RSI/ATR:** remove the historically damaging short side and
    compare it with the unchanged two-sided baseline.
2.  **Market-regime strategies:** independently enable bull pullbacks/breakouts,
    bear shorts or flat positioning, and sideways mean reversion.
3.  **Multi-timeframe execution:** use 1h regime, 15m setup, 5m entry, and 1m
    maker-fill/protective-order resolution.
4.  **More liquid symbols:** apply frozen logic to BTC, ETH, SOL, BNB, XRP, and
    other sufficiently liquid Futures markets rather than forcing BTC entries.
5.  **Improved exits:** compare partial profit, ATR trailing, lack-of-progress
    time exits, and regime-specific stop/target behavior.
6.  **Portfolio-level risk:** combine independently validated edges with caps
    on total leverage and correlated crypto exposure.

## Experiment results

This file tracks scope and completion only. Detailed metrics, configurations,
and conclusions for every completed experiment are kept together in
`PROJECT_STATUS.md` under **Return-improvement experiment results**.

-   [x] 1. Long-only BTC RSI/ATR
-   [x] 2. Market-regime strategies
-   [x] 3. Multi-timeframe execution
-   [x] 4. More liquid symbols
-   [x] 5. Improved exits
-   [x] 6. Portfolio-level risk

## Order-flow research sequence

The next research direction must introduce information that is not derived only
from OHLCV candles. Historical completeness is mandatory: a signal may enter a
training backtest only when equivalent source data covers the full two-year
training window. Liquidations, live order books, and other forward-only feeds
are deferred unless a reliable full-window archive is obtained.

-   [x] 0. Freeze scope: start with BTCUSDT USD-M Futures aggregate trades for
    `[2023-08-07, 2025-08-07)` only;
    retain raw archives outside PostgreSQL and store compact 1m features in the
    database.
-   [x] 1. Verify Binance archive coverage, file sizes, checksums, timestamp
    format, and gaps for the complete BTCUSDT three-year window. Produce a
    download/storage/runtime estimate before downloading the dataset.
-   [x] 2. Define and review the 1m aggregate-trade schema and deterministic
    aggregation rules. Include aggressive buy/sell quantity and notional,
    delta, cumulative delta, trade counts, large-trade measures, and data-quality
    fields. Apply Flyway only after review.
-   [x] 3. Implement resumable, checksum-verified archive download and streaming
    aggregation. Do not load every raw trade into PostgreSQL.
-   [x] 4. Import BTCUSDT and independently verify time boundaries, continuity,
    totals, duplicates, and reconciliation with kline volumes.
-   [x] 5. Add no-look-ahead order-flow features such as imbalance, delta
    acceleration, absorption, exhaustion, and price/order-flow divergence.
-   [x] 6. Define an order-flow exhaustion strategy and acceptance criteria
    before running it. Freeze parameters and retain existing fees and 1m
    execution rules.
-   [x] 7. Run BTC training diagnostics. Import aggregate trades for additional
    symbols only if BTC shows material improvement without failing stability or
    cost stress.

Funding and the existing three-year candle history may be used. Existing
open-interest/trader-ratio history covers only about 30 days, so it must not be
used as a three-year strategy input. It can remain stored for future forward
research.

Step 1 conclusion: the official archive has all 36 monthly files from 2023-08
through 2026-07 and daily files for 2026-08-01 through 2026-08-05, with checksum
companions. The required 2026-08-06 file was not yet published when audited, so
the exact three-year endpoint is temporarily incomplete by one day. Available
archives total 19.90 GB compressed; a verified sample implies approximately
104.5 GB expanded and 1.57 billion raw aggregate-trade rows. Streaming directly
to 1m aggregation avoids expanded storage and produces at most 1,579,680 feature
rows. Full details and estimates are in `PROJECT_STATUS.md`.

Step 2 conclusion: `ORDER_FLOW_DESIGN.md` freezes the BTC training-only scope,
source-side aggressor semantics, proposed 1m table, exact aggregation formulas,
trade-size buckets, gap/duplicate handling, kline reconciliation, derived-feature
boundary, and stopping rule. No migration has been created or applied.

Step 3 conclusion: Flyway V5 creates the minute-feature and archive-manifest
tables. The importer supports HTTP range resume, SHA-256 verification, ZIP/CSV
streaming, UTC boundary filtering, aggressor aggregation, size buckets,
duplicate/gap detection, batched idempotent upserts, archive completion records,
and kline-volume reconciliation. A real 399,219-row archive produced 1,440
minutes with no duplicates or missing IDs in dry-run mode. No sample rows or
bulk training archives were persisted.

Step 4 conclusion: all 30 BTCUSDT training archives were checksum-verified and
imported. The source contains 996,290,953 archive rows; 4,063,849 boundary rows
were filtered and the remaining 992,227,104 aggregate trades produced 1,052,606
minute rows representing 2,541,354,833 underlying trades. There are no duplicate
or missing aggregate IDs. The 34 calendar minutes without aggregate rows all
have zero kline volume and zero trades. Exact kline-volume reconciliation holds
for 874,678 minutes; 177,928 differ, mostly by small amounts, with only 462 over
10 BTC and a total-window volume difference of about 0.00075%. Keep the explicit
quality status and use aggregate trades as the order-flow source of truth.

Step 5 conclusion: deterministic 5m/15m/60m/240m features now cover order-flow
imbalance, rolling quote delta, >=100k trade imbalance, coverage, reconciliation
quality, price return, delta acceleration, sell absorption, sell exhaustion, and
price/flow divergence. Snapshots become executable only after the source minute
closes, and a regression test proves future flow cannot change an earlier
snapshot. A real six-day preview generated all 8,640 expected snapshots.

Step 6 conclusion: the 5m long-only exhaustion hypothesis, thresholds, ATR/2R
risk, flow/time exits, six-bar entry-signal spacing, 75% primary quality floor,
95% quality sensitivity, and materially stricter acceptance profile are frozen
in code and configuration. Unit tests cover entry, quality rejection, and flow
exit. No training performance was inspected.

Step 7 conclusion: the primary BTC training run failed every acceptance gate and
hit the 10% account drawdown stop after 48 trades: -10.29% return, 0.072 profit
factor, and -$1,849 before costs. The predeclared 95%-quality sensitivity also
hit the drawdown stop: -10.36% return and 0.287 profit factor. Its raw price PnL
was +$1,519, but $11,878 of fees and slippage overwhelmed the small captured
moves; all four independent six-month subperiods lost money. The frozen stopping
rule rejects this strategy branch. Do not tune its thresholds, open validation
or final-test data, or import aggregate trades for more symbols on its behalf.

## Direct Binance BTCUSDC research

This is a separate execution track from prop-account research. BTCUSDC USD-M
Futures launched on 2024-01-03, with its first actual 1m candle at
2024-01-04 12:31 UTC. Use `[2024-02-01, 2026-02-01)` for training, reserve the
next three months for validation and `[2026-05-01, 2026-08-01)` for final test.
The account fee schedule observed on 2026-08-07 is 0 bps maker and 3.6 bps
taker; keep it configurable because Binance can change account/promotional fees.

-   [x] Import and verify BTCUSDC 1m, 5m, 15m, and 1h candles and funding.
-   [x] Run unchanged intraday mean-reversion rules on 15m, 5m, and 1m training
    data with strict maker trade-through and the BTCUSDC fee schedule.
-   [x] Design a new passive-maker candidate after reviewing the baselines,
    freeze it before measuring performance, and apply the high-frequency
    acceptance profile.
-   [x] Import the complete BTCUSDC training aggregate-trade archive, switch
    strict maker trade-through to aggregate min/max prices, and rerun the
    frozen passive-maker candidate.

BTCUSDC conclusion: zero maker fees solve only one part of execution economics.
The unchanged intraday rules lost 30.10% on 15m, 97.92% on 5m, and 100% on 1m
over the full training period. A new frozen passive-maker strategy generated
79,288 fills but also lost 100% with a 0.335 profit factor: its 62.8% win rate
was overwhelmed by average losses roughly five times average wins. All four
six-month subperiods failed for every candidate. Reject both rule families
without threshold tuning. Do not open the reserved validation/final periods.
The aggregate-trade rerun produced the same 79,288 fills and a slightly worse
0.33525 profit factor versus 0.33539 with kline trade-through, confirming that
1m kline ranges were already an accurate fill-crossing proxy for this strategy.

## Structural channel experiment

-   [x] Freeze a 15m channel strategy requiring a prior 96-bar range, at least
    two support and resistance touches, entry within 0.35 ATR of a boundary,
    stop 0.25 ATR beyond the saved structural level, channel width >=6x risk,
    and opposite-boundary reward >=3x risk.
-   [x] Extend strategy decisions and execution to preserve absolute stop and
    target levels across the next-bar maker fill.
-   [x] Run the unchanged strategy on every populated pair using each market's
    training window and fee profile.

The candidate failed on all nine pairs. Profit factors ranged from 0.499 on BNB
to 0.799 on BTCUSDC; returns ranged from -10.28% to -32.94%. Average wins were
roughly 8.5-11.5 times average losses, but win rates were only 5.5-7.2%. The
problem is not reward/risk geometry: the simple rolling-extreme/two-touch rule
frequently mistakes a trending range or temporary pause for a stable channel.
Reject this v1 definition without threshold tuning. A future v2 must define
levels from clustered pivots, require alternating and time-separated boundary
defences, and confirm rejection after the boundary is tested.

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
