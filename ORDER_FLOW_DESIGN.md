# BTCUSDT Aggregate-Trade Design

## Scope

- Market: Binance USD-M Futures.
- Symbol: BTCUSDT only.
- Dataset: training period `[2023-08-07, 2025-08-07)` UTC.
- Source: official Binance `aggTrades` monthly/daily ZIP archives.
- Raw ZIP files remain outside PostgreSQL and are checksum-verified.
- CSV entries are streamed directly from ZIP files; expanded CSV files are not
  retained and individual aggregate trades are not inserted into PostgreSQL.
- Liquidations, order-book data, and the existing 30-day supporting statistics
  are excluded because equivalent training-period history is unavailable.

## Source semantics

Each source row contains aggregate-trade ID, price, quantity, first and last
underlying trade IDs, transaction time, and `is_buyer_maker`.

- `is_buyer_maker=false`: the buyer crossed the spread, so volume is classified
  as aggressive buy flow.
- `is_buyer_maker=true`: the seller crossed the spread, so volume is classified
  as aggressive sell flow.
- Quote notional is `price * quantity`.
- Underlying trade count is `last_trade_id - first_trade_id + 1`.
- Minute time is the transaction timestamp truncated down to UTC minute.
- Rows outside the exact training boundary are discarded even when their
  containing monthly archive overlaps the boundary.
- Archive rows must not be assumed to be globally chronological. The official
  October 2023 archive interleaves distant time ranges; aggregation therefore
  groups by UTC minute and determines first/last events explicitly.

## Proposed PostgreSQL table

Table name: `futures_agg_trade_minute`

| Column | Proposed type | Meaning |
| --- | --- | --- |
| `symbol` | `VARCHAR(20)` | `BTCUSDT` initially |
| `minute_time` | `TIMESTAMPTZ` | UTC minute, primary-key component |
| `first_event_time` | `TIMESTAMPTZ` | First aggregate trade in the minute |
| `last_event_time` | `TIMESTAMPTZ` | Last aggregate trade in the minute |
| `first_agg_trade_id` | `BIGINT` | First observed aggregate-trade ID |
| `last_agg_trade_id` | `BIGINT` | Last observed aggregate-trade ID |
| `aggregate_trade_count` | `INTEGER` | Number of source aggregate-trade rows |
| `underlying_trade_count` | `BIGINT` | Sum of underlying trade counts |
| `base_volume` | `NUMERIC(38,12)` | Total BTC quantity |
| `quote_notional` | `NUMERIC(38,12)` | Total USDT notional |
| `aggressive_buy_base` | `NUMERIC(38,12)` | Buyer-taker BTC quantity |
| `aggressive_sell_base` | `NUMERIC(38,12)` | Seller-taker BTC quantity |
| `aggressive_buy_quote` | `NUMERIC(38,12)` | Buyer-taker USDT notional |
| `aggressive_sell_quote` | `NUMERIC(38,12)` | Seller-taker USDT notional |
| `base_delta` | `NUMERIC(38,12)` | Buy base minus sell base |
| `quote_delta` | `NUMERIC(38,12)` | Buy quote minus sell quote |
| `first_price` | `NUMERIC(30,12)` | First aggregate-trade price |
| `last_price` | `NUMERIC(30,12)` | Last aggregate-trade price |
| `minimum_price` | `NUMERIC(30,12)` | Minimum trade price |
| `maximum_price` | `NUMERIC(30,12)` | Maximum trade price |
| `buy_vwap` | `NUMERIC(30,12)` nullable | Buyer-taker VWAP |
| `sell_vwap` | `NUMERIC(30,12)` nullable | Seller-taker VWAP |
| `max_aggregate_quote` | `NUMERIC(38,12)` | Largest aggregate-trade notional |
| `large_10k_count` | `INTEGER` | Aggregate trades with notional >= 10k USDT |
| `large_10k_buy_quote` | `NUMERIC(38,12)` | Buyer-taker notional in >=10k trades |
| `large_10k_sell_quote` | `NUMERIC(38,12)` | Seller-taker notional in >=10k trades |
| `large_100k_count` | `INTEGER` | Aggregate trades with notional >=100k USDT |
| `large_100k_buy_quote` | `NUMERIC(38,12)` | Buyer-taker notional in >=100k trades |
| `large_100k_sell_quote` | `NUMERIC(38,12)` | Seller-taker notional in >=100k trades |
| `large_1m_count` | `INTEGER` | Aggregate trades with notional >=1m USDT |
| `large_1m_buy_quote` | `NUMERIC(38,12)` | Buyer-taker notional in >=1m trades |
| `large_1m_sell_quote` | `NUMERIC(38,12)` | Seller-taker notional in >=1m trades |
| `agg_trade_id_gap_count` | `BIGINT` | Missing aggregate IDs inside/before minute |
| `duplicate_count` | `INTEGER` | Duplicate IDs ignored during import |
| `kline_base_volume_difference` | `NUMERIC(38,12)` nullable | Aggregate volume minus 1m kline volume |
| `reconciliation_status` | `VARCHAR(20)` | `MATCHED`, `MISMATCH`, or `KLINE_MISSING` |
| `created_at` | `TIMESTAMPTZ` | Insert timestamp |
| `updated_at` | `TIMESTAMPTZ` | Last upsert timestamp |

Primary key: `(symbol, minute_time)`. No additional time index is required
initially because the primary key supports symbol/range scans.

## Aggregation formulas and invariants

For each minute:

```text
base_volume = sum(quantity)
quote_notional = sum(price * quantity)
aggressive_buy_* = sum(rows where is_buyer_maker = false)
aggressive_sell_* = sum(rows where is_buyer_maker = true)
base_delta = aggressive_buy_base - aggressive_sell_base
quote_delta = aggressive_buy_quote - aggressive_sell_quote
buy_vwap = aggressive_buy_quote / aggressive_buy_base, or NULL when buy base is 0
sell_vwap = aggressive_sell_quote / aggressive_sell_base, or NULL when sell base is 0
```

Required checks:

- All volumes, notionals, counts, and gap counts are non-negative.
- `base_volume = aggressive_buy_base + aggressive_sell_base`.
- `quote_notional = aggressive_buy_quote + aggressive_sell_quote`.
- IDs and event times are monotonic after duplicates are removed.
- Archive order itself need not be monotonic; continuity is calculated from the
  included ID range and source-row count, including the prior archive boundary.
- A repeated aggregate-trade ID with identical content is counted and ignored;
  conflicting content for the same ID fails the import.
- An ID jump contributes the number of absent IDs to `agg_trade_id_gap_count`.
- Upserts make reruns idempotent, but archive-level progress is committed only
  after checksum and row-boundary verification succeed.
- Reconciliation uses a small decimal tolerance defined in code and reports the
  difference; it never silently modifies aggregate-trade totals to match klines.

## Derived features deferred to Step 5

The importer does not store a lifetime cumulative delta because it is
non-stationary and depends on an arbitrary start date. Feature generation will
derive rolling sums over frozen windows, for example 5m, 15m, 1h, and 4h:

- Order-flow imbalance: `quote_delta / quote_notional`.
- Rolling cumulative delta: rolling sum of `quote_delta`.
- Delta acceleration: short-window imbalance minus longer-window imbalance.
- Large-trade imbalance using the stored notional buckets.
- Absorption: extreme sell imbalance with unusually small or positive return.
- Exhaustion: declining aggressive sell notional after an extreme sell-flow
  event, followed by price stabilization.
- Divergence: price direction disagrees with rolling cumulative delta.

Exact windows and entry rules must be frozen in Steps 5–6 before performance is
examined. The training experiment must compare against the existing BTC flat-long
baseline using the same fees, slippage, risk, and minute execution.

## Step 5 implemented feature definitions

Every snapshot uses the current and prior completed 1m records only. Its
`availableAt` is the current minute close and `earliestExecutionTime` is one
millisecond later. Frozen rolling windows are 5m, 15m, 60m, and 240m.

- `orderFlowImbalance(N) = sum(quote_delta,N) / sum(quote_notional,N)`.
- `rollingQuoteDelta(N) = sum(quote_delta,N)`.
- `large100kImbalance(N) = (large_buy_quote - large_sell_quote) /
  (large_buy_quote + large_sell_quote)` over the window.
- `orderFlowCoverage(N) = observed flow minutes / N`.
- `orderFlowQuality(N) = exactly kline-reconciled observed minutes / observed
  flow minutes`. Coverage is separate so a missing flow minute cannot look like
  a quality success.
- `priceReturn(N) = current close / close N minutes ago - 1`.
- `deltaAcceleration(5,60) = imbalance(5) - imbalance(60)`.
- `sellAbsorption(15)` multiplies negative 15m flow imbalance by resistance to
  a downward price move, linearly declining to zero at a -0.5% return, and by
  15m data coverage.
- `sellExhaustion(5,15)` measures the reduction from 15m sell pressure to 5m
  sell pressure, multiplied by price stability that declines to zero at a
  +/-0.2% 5m move.
- `priceFlowDivergence(15)` subtracts 15m flow imbalance from 15m price return
  normalized and capped to `[-1,1]` at +/-0.5%.

The formulas are exploratory inputs, not evidence of an edge. Step 6 must freeze
entry/exit rules and acceptance criteria before inspecting strategy returns.

## Step 6 frozen strategy

Strategy type: `order-flow-exhaustion`. It is long-only and evaluated once per
completed 5m bar. The next 5m bar is the earliest entry opportunity and existing
1m execution rules determine maker fills and protective-order sequencing.

Primary entry requires every condition below:

- 240m flow coverage >= 99% and exact-reconciliation quality >= 75%.
- Close is no more than 2% below the 5m EMA-200.
- 15m order-flow imbalance <= -8%.
- 15m >=100k trade imbalance <= -10%.
- 15m sell-absorption score >= 0.04.
- 5m/15m sell-exhaustion score >= 0.02.
- 5m-versus-60m delta acceleration >= 0.03.
- 15m price/flow divergence >= 0.05.
- 15m price return is between -0.5% and +0.1%.
- At least six 5m bars have passed since the prior entry signal.

Risk and exits are also frozen:

- Stop distance: 1.25 x 5m ATR-14.
- Target distance: 2R.
- Exit when 5m buy imbalance reaches +10%.
- Exit as a failed reversal when 5m sell imbalance reaches -15%.
- Time exit after 12 x 5m bars (one hour).

One predeclared sensitivity run changes only 240m exact-reconciliation quality
from 75% to 95%. It is not an independently tunable strategy.

Acceptance file: `config/backtests/acceptance-order-flow.properties`.

- Net training profit >= 8,000 on the 100,000 initial account.
- Profit factor >= 1.20 and maximum drawdown <= 5%.
- At least 120 completed trades.
- At least three profitable six-month subperiods.
- Largest positive-subperiod contribution <= 50%.
- Average win/loss >= 1.20.
- Net profit remains positive with fees and slippage multiplied by 1.5.

Stop the research branch if the primary candidate fails net profit, trade count,
subperiod stability, or cost stress. Do not search thresholds after seeing the
result. The high-quality sensitivity run is diagnostic only and cannot rescue a
failed primary configuration.

## Success and stopping rule

Importing additional symbols is not justified merely by positive BTC profit.
The BTC order-flow candidate must materially improve evidence by producing more
trades or better subperiod stability while preserving positive cost-stressed
return and acceptable drawdown. If it only produces another low-frequency,
in-sample variation near the current +6.77% baseline, stop this research branch.
