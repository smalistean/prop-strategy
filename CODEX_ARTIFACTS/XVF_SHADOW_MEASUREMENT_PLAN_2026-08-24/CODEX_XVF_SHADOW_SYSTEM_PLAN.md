# PLAN — XVF Shadow Measurement System

**Document type:** implementation and measurement plan  
**Prepared by:** OpenAI Codex  
**Date:** 2026-08-24  
**Purpose:** turn the existing XVF shadow decision capture into a small, auditable system that can
measure which changes improve net return on total capital.

## Executive decision

The next version should be SQL-first, with Java used only where PostgreSQL is the wrong tool:
capturing point-in-time public API data and writing it atomically.

The existing Java shadow subsystem already performs the difficult point-in-time acquisition work.
It collects funding inputs, normalized contract metadata, bid/ask, mark, depth, venue rules and
candidate scores, then writes an immutable decision ledger. That should be retained and repaired,
not expanded into another general trading platform.

The missing economic feedback loop should be implemented mostly in PostgreSQL:

```text
scheduled decision
       |
       v
existing Java capture  --->  xvf_signal_run / xvf_signal_candidate
                                      |
                                      | wait until the 72-hour horizon
                                      v
minimal exit-market capture ---> outcome facts
                                      |
settled funding observations ---------+
                                      |
                                      v
                         versioned SQL measurements
                                      |
                                      v
             baseline / maker / capital / leverage reports
```

The first useful delivery is not an execution engine. It is one trustworthy table that reconciles,
for every candidate, expected funding, realized funding, relative-price P&L, fees, simulated
execution cost and return on total capital.

## 1. Questions the system must answer

The system exists to answer a short list of economic questions with measured data:

1. How much of the annualized funding signal is actually received over the three-day holding
   period?
2. How much return is lost or gained through relative-price, or basis, movement?
3. After complete entry and exit costs, which venue route has the highest realized net return?
4. Does making Bybit the maker improve results after non-fill and fallback costs?
5. Which symbols add repeatable net return and which only display a large funding signal?
6. How much capital is unused because it is located at the wrong venue?
7. Which fixed capital distribution improves return on total capital without being dependent on one
   historical period?
8. Does leverage improve return after fees, basis risk, margin reserve and venue-capital constraints?

Every implementation choice should map directly to one of these questions. If it does not produce a
required fact or measurement, it is not part of the first measurement release.

## 2. What currently exists

The current shadow subsystem is not empty. It already provides:

- immutable `xvf_signal_run` and `xvf_signal_candidate` records;
- the production date, UTC cutoff, code revision, configuration hash and funding watermarks;
- all evaluated cross-venue alternatives and baseline/shadow ranks;
- multiplier-aware price normalization, including contracts such as `1000PEPE`;
- public bid/ask, mark/index references, instrument rules and order-book depth;
- evaluation of both possible one-maker/one-taker entry routes;
- fee, depth, slippage and venue-capital gates;
- atomic persistence and database constraints that prevent later mutation; and
- unit and PostgreSQL integration tests for the ledger and planner.

These are useful acquisition and audit foundations. The current shadow code correctly carries
`baseUnitsPerContract`; the raw-price multiplier problem found in the separate live entry-basis
filter should not be attributed to this shadow calculation.

## 3. What is still missing

### 3.1 Capture reliability

Venues are fetched concurrently, but symbols within Binance, Bybit and Hyperliquid are fetched
serially. A large universe can therefore make the earliest quotes older than the configured
30-second maximum before planning starts. The run cutoff is currently assigned after the market
requests finish, while the signal calculation occurred earlier.

The capture must instead record an explicit scheduled decision time, capture start and capture end,
retain every response/source timestamp, enforce a maximum capture window, and use bounded concurrent
symbol requests with venue-specific rate limits. It must not pretend the venues were observed at one
instant; it should measure and report the actual skew.

Scheduler retries also need an idempotency key. The existing schema intentionally allows multiple
runs at a cutoff, which is useful for audit experiments but insufficient for an automatic job unless
the scheduled attempt identity is recorded.

### 3.2 A stateful shadow book

Each current run constructs a fresh target book. It does not load the previous shadow target and
classify exact pairs as `OPEN`, `RETAIN`, `CLOSE` or `REVERSE`.

This matters economically. A retained pair has already paid its entry fee and should not be charged
another round trip. A replacement consumes turnover, and venue capital is not released until the old
leg is closed. Without transitions, fee and capital-return measurements will not match the production
three-day reconciliation process.

The first implementation should derive transitions in SQL by ordering decision runs and comparing
the exact normalized pair key:

```text
(base, short venue, short venue symbol, long venue, long venue symbol)
```

There is no need for a separate Java portfolio engine to calculate these transitions.

### 3.3 Realized outcomes

The ledger records a prediction but never returns after 72 hours to record the outcome. A separate
append-only outcome record is required because the decision ledger itself should remain immutable.

For each scorable candidate, the outcome record needs:

- decision run and candidate identity;
- planned exit timestamp and actual capture timestamp;
- exit bid, ask, mark/index and sufficient depth for both legs;
- base-units-per-contract and exact venue symbols;
- source timestamps and cross-venue skew;
- data-quality status and explicit missing reasons; and
- references to the settled funding observations inside the holding interval.

Derived P&L should remain in versioned SQL views where formulas can be reviewed. Raw exit facts
should not be overwritten when a formula changes.

### 3.4 Maker-fill evidence

The current planner treats a maker price at the touch as executable. A point-in-time order book
cannot establish whether a post-only order would fill, how long it would wait, its queue position or
the price move immediately after the fill.

The first report should therefore show three explicit cases rather than one false-precision result:

1. **Optimistic maker:** the maker fills at the submitted price.
2. **Trade-through maker:** the maker fills only if subsequent public trades move strictly through
   the limit during the configured order lifetime.
3. **Fallback:** the maker does not fill and the pair is either skipped or crossed as taker,
   according to the tested policy.

Public data can support the trade-through approximation. Exact queue-position simulation is not
available from ordinary public snapshots. It should not be claimed as measured.

### 3.5 Calibrated expected return

The current planner repeats the latest pending funding rate for every funding timestamp in the
72-hour horizon. This is a declared forecast assumption, not evidence that later rates will remain
unchanged. The expected basis-capture factor and risk penalty both default to zero.

After outcomes exist, SQL should calculate prediction error by venue pair, funding interval, signal
age, entry-basis bucket and symbol. Only then should a calibrated expected-funding or expected-basis
term influence selection.

### 3.6 Capital allocation rather than a declared split

The current shadow planner accepts an equal or manually supplied venue allocation and greedily takes
positive expected-net candidates. It does not search capital distributions or variable sizes.

The first capital experiment does not need an optimizer service. PostgreSQL can generate a coarse
grid of fixed allocations, for example in USD 250 increments or 5-percentage-point increments,
replay each decision under venue constraints, and report net return and unused capital. A compact
analysis script is justified only if sequential capital allocation becomes materially clearer than
a recursive SQL query.

## 4. Measurement contract

### 4.1 Unit of analysis

The raw unit is one candidate at one decision cutoff. Portfolio results are calculated separately by
policy and rebalance cycle. This preserves candidates rejected by the baseline so alternative symbol,
route and capital rules can be evaluated without recollecting history.

### 4.2 Time rules

- `decision_at` is the scheduled production decision timestamp in UTC.
- Entry market facts must carry their own source or request interval timestamps.
- The intended horizon is exactly `planned_hold_hours`, currently 72 hours.
- Exit facts are collected at the horizon with a declared tolerance.
- Settled funding includes timestamps strictly after entry and at or before exit, using one documented
  boundary convention everywhere.
- Missing entry, exit or funding data remains missing. It is never converted to zero return.
- A late outcome remains visible as late; it is not silently treated as an on-time executable price.

### 4.3 Price normalization

All cross-venue prices must first become price per canonical base unit:

```text
normalized_price = venue_contract_price / base_units_per_contract
```

Symbol aliases and multipliers must be resolved by exact venue symbol, not only by a normalized base
name. Ambiguous mappings are rejected and counted in the data-quality report.

### 4.4 Executable entry and exit prices

For a short leg:

- taker entry sells through bids;
- maker entry rests at a declared maker price;
- taker exit buys through asks.

For a long leg:

- taker entry buys through asks;
- maker entry rests at a declared maker price;
- taker exit sells through bids.

Depth-based VWAP should be calculated for the simulated order notional. Mid or mark is useful for
basis diagnostics but must not replace executable bid/ask in the net-return calculation.

### 4.5 Exact simulated P&L

For equal USD leg notional `N`, using normalized entry prices:

```text
short_base_quantity = N / short_entry_price
long_base_quantity  = N / long_entry_price

short_price_pnl_usd = short_base_quantity * (short_entry_price - short_exit_price)
long_price_pnl_usd  = long_base_quantity  * (long_exit_price - long_entry_price)
```

Funding uses the actual settled funding rate at each venue timestamp and the correct side sign:
positive funding is received by the short and paid by the long. For a simulated position, the rate
is applied to the position notional at the settlement timestamp using the best available historical
mark. If settlement marks are unavailable, the initial-notional approximation must be labelled and
reported separately.

```text
net_pnl_usd = short_price_pnl_usd
            + long_price_pnl_usd
            + short_funding_pnl_usd
            + long_funding_pnl_usd
            - entry_fees_usd
            - exit_fees_usd
            - explicit_slippage_usd
```

Two denominators must be retained:

```text
return_on_total_capital = net_pnl_usd / starting_total_capital
return_on_used_capital  = net_pnl_usd / capital_committed_to_the_pair_or_book
```

The primary decision metric is return on total capital. Return on used capital is diagnostic and
must not hide collateral stranded at another venue.

### 4.6 Basis diagnostics

Entry and exit basis are diagnostics around the exact two-leg price P&L:

```text
entry_basis_bps = ln(short_entry_price / long_entry_price) * 10,000
exit_basis_bps  = ln(short_exit_price  / long_exit_price)  * 10,000
```

For a short-short-venue/long-long-venue pair, convergence approximately contributes
`entry_basis_bps - exit_basis_bps`. The exact leg P&L above remains authoritative because percentage
returns and notionals are not perfectly symmetric after prices move.

## 5. Minimal database addition

The preferred addition is one append-only outcome table keyed by decision candidate:

```text
xvf_signal_candidate_outcome
  signal_run_id
  evaluation_order
  horizon_hours
  target_exit_utc
  captured_at
  capture_status
  short_exit_snapshot JSONB
  long_exit_snapshot JSONB
  funding_watermarks JSONB
  data_issues JSONB
  formula_inputs_version
```

The uniqueness key should prevent two successful 72-hour outcomes for the same candidate and
horizon while still allowing a failed attempt followed by an explicit retry record. The precise
retry representation should be chosen before the migration is written; it must not require updating
or deleting the original attempt.

PostgreSQL views then calculate:

- candidate expected versus realized components;
- exact-pair book transitions;
- baseline and challenger portfolio returns;
- capital usage by venue;
- maker-fill sensitivity;
- symbol and venue-pair contribution; and
- leverage stress scenarios.

## 6. SQL deliverables

The measurement release should contain a small, named set of queries rather than another application:

1. `01_xvf_shadow_capture_health.sql`
   - complete/partial/failed runs, capture duration, stale quotes, skew and missing fields.
2. `02_xvf_shadow_candidate_outcomes.sql`
   - one row per candidate with funding, price P&L, fees, slippage and net P&L.
3. `03_xvf_shadow_book_transitions.sql`
   - `OPEN`, `RETAIN`, `CLOSE`, `REVERSE`, turnover and fee treatment per cycle.
4. `04_xvf_shadow_policy_comparison.sql`
   - unchanged baseline versus fee-aware/basis-aware shadow ranking.
5. `05_xvf_shadow_capital_grid.sql`
   - equal thirds and coarse fixed allocations with used and stranded venue capital.
6. `06_xvf_shadow_maker_sensitivity.sql`
   - current routing, Bybit-maker, cheapest feasible maker, all-taker and fill/fallback bounds.
7. `07_xvf_shadow_symbol_contribution.sql`
   - observations, median, tails, funding, basis, fees and net contribution by symbol and pair.
8. `08_xvf_shadow_leverage_stress.sql`
   - 1.00x, 1.25x, 1.50x and 2.00x with venue reserve and drawdown reporting.

Each query should expose its assumptions in output columns. A missing-data policy, maker-fill model
or fee schedule should never exist only in a comment or in application memory.

## 7. Policy scenarios to measure

### 7.1 Control

- current production ranking;
- equal venue capital;
- current fixed leg size and maximum 20 positions;
- exact-pair retention;
- current maker/taker route assumptions; and
- no leverage change.

### 7.2 Bybit-maker

When Bybit is one leg, place Bybit on the maker side and the other venue on the taker side. Report the
optimistic fill, trade-through fill and fallback cases separately. This policy advances only if the
fee saving survives conservative non-fill and adverse-selection assumptions.

### 7.3 Fee-aware route

Evaluate both one-maker routes and select the route with lower expected complete execution cost.
Do not select on headline fee alone; include depth, slippage and the maker fallback case.

### 7.4 Symbol selection

Rank candidates using measured expected net rather than annualized funding spread alone. Report
results by symbol with a minimum observation count and walk-forward logic. A symbol is not excluded
because it lost in one small sample, and it is not preferred because of one large winner.

### 7.5 Capital distribution

Use equal thirds as the control and evaluate a coarse allocation grid. Choose broad stable regions,
not the single historical maximum. Report the number and expected value of candidates blocked at
each venue, because this explains why an allocation helped or hurt.

### 7.6 Leverage

Apply leverage only to the best unleveraged policy. Scale quantities, fees, depth demand and basis
P&L together. Enforce a per-venue free-margin reserve and report reserve breaches and liquidation
sensitivity. Funding-only scaled return is not a valid leverage result.

## 8. Implementation sequence and Codex effort

The estimates below are active Codex work in this repository, not human staffing estimates and not
calendar time waiting for live outcomes.

### Checkpoint A — first useful measurement pipeline

| Step | Work | Output | Codex effort |
|---|---|---|---:|
| A1 | Confirm formulas, timestamps and missing-data policy | reviewed measurement contract | 0.5–1 hour |
| A2 | Repair capture timing and bounded venue concurrency | reliable entry/exit facts plus tests | 2–3 hours |
| A3 | Add append-only 72-hour outcome persistence | migration, repository path and tests | 3–4 hours |
| A4 | Add basic expected-versus-realized SQL | one auditable outcome row per candidate | 1–2 hours |

**Checkpoint A total:** approximately **7–10 active hours**.

A hard review should occur after the first three hours, once capture integrity and the exact outcome
schema are visible. Further work should use the accepted schema rather than expanding the domain
model speculatively.

### Checkpoint B — capital-return comparisons

| Step | Work | Output | Codex effort |
|---|---|---|---:|
| B1 | Derive stateful book transitions and capital use | SQL retention/reconciliation report | 3–5 hours |
| B2 | Add capital, symbol, route and leverage scenarios | separate SQL comparisons | 3–5 hours |

**Checkpoint B total:** approximately **6–10 active hours**.

### Checkpoint C — operation and verification

| Step | Work | Output | Codex effort |
|---|---|---|---:|
| C1 | Schedule in AWS and add failure visibility | scheduled job, idempotency and CloudWatch alert | 2–4 hours |
| C2 | End-to-end verification | fixtures, database test and inspected live dry runs | 2–3 hours |

**Checkpoint C total:** approximately **4–7 active hours**.

### Total

- First useful expected-versus-realized measurement: **7–10 hours**.
- Full plan through capital/leverage reports and AWS operation: **17–27 hours**.
- Trade-through maker approximation: add **3–5 hours** if suitable trade data already exists.
- Dedicated higher-frequency quote/trade collector: add **8–12 hours** instead.

The first closed cohort becomes available 72 hours after collection starts. Around ten
non-overlapping three-day cycles, or roughly 30 calendar days, provide a preliminary view. Twenty to
thirty cycles, roughly 60–90 days, provide a more defensible forward comparison. Daily overlapping
captures can diagnose the model but should not be counted as independent three-day portfolio cycles.

## 9. Acceptance checks

### Capture

- A representative universe finishes inside the declared capture window.
- Every market fact has a source/request timestamp and exact venue symbol.
- Cross-venue skew and stale data are reported, not silently accepted.
- Scheduler retries cannot create an indistinguishable duplicate production attempt.
- A venue or symbol failure produces a partial/failed fact record rather than a fabricated value.

### Outcome calculation

- Multiplier contracts reconcile to the same canonical-base price scale.
- Missing funding or prices never become zero.
- Funding interval boundaries are verified with hand-calculated fixtures.
- Entry and exit fees occur only on actual book transitions.
- Component dollars sum exactly to reported net P&L.
- Return on total capital uses the complete starting capital denominator.
- Re-running a versioned SQL query over unchanged facts produces identical results.

### Scenario comparison

- Baseline and challenger use the same candidate facts and observation horizon.
- Both annual periods and forward shadow cycles are reported separately.
- Results include observation counts, median, tails, drawdown and concentration.
- Maker policies show fill/fallback sensitivity.
- Capital policies report unused capital and venue-blocked candidates.
- Leverage policies include scaled costs, margin reserve and basis stress.

## 10. Decision rules

An improvement is eligible for paper adoption only when it:

- increases basis-inclusive net return on total capital, not only gross funding;
- remains positive after complete fees and conservative execution assumptions;
- improves more than one period or forward cohort rather than one isolated month;
- is not explained by one symbol, venue pair or outlier;
- remains directionally useful at nearby thresholds or allocation points; and
- has sufficient complete observations to distinguish a result from missing-data selection.

Leverage is evaluated last. A leveraged policy does not advance merely because multiplying a
funding-only return produces a larger number.

## 11. Why I did not start with SQL

### What I was trying to solve

I interpreted the request to move forward as a request for a durable, production-quality shadow
decision-audit system. I optimized for exact timestamps, immutable provenance, external API schema
validation, atomic candidate membership, typed monetary values, route scoring and future production
reproducibility.

Some of that reasoning was technically valid. Plain PostgreSQL is not the right component for:

- calling Binance, Bybit and Hyperliquid public APIs;
- coordinating bounded concurrent HTTP requests;
- measuring request/source timestamps and cross-venue skew;
- parsing inconsistent external JSON schemas;
- applying exchange symbol and contract-multiplier rules; or
- assembling one atomic point-in-time fact package from several external sources.

That acquisition boundary justifies a small Java collector.

### Where the decision became wrong

I treated the need for a collector as justification for building the whole measurement process as a
large Java architecture. Those are different problems.

The user's actual decision was whether there was enough measurable room to improve capital return.
That required a baseline, a few controlled alternatives and transparent component P&L. It did not
first require a full domain model, repository abstraction, generalized planner and extensive audit
infrastructure.

The specific mistakes were:

1. **I chose the wrong unit of delivery.** I delivered a subsystem when the useful unit was a
   measurement table and a plan.
2. **I optimized for auditability before proving economic value.** Immutable capture is useful, but
   it does not by itself answer whether maker routing, capital allocation or leverage helps.
3. **I moved calculations out of the database unnecessarily.** Funding outcomes, pair retention,
   fees, capital use, symbol attribution and scenario comparisons are naturally set-based and easier
   to inspect as SQL.
4. **I combined data acquisition, prediction and portfolio selection.** Only acquisition had a
   strong reason to be Java at this stage.
5. **I did not establish a time and scope checkpoint before implementation.** I should have returned
   after the first small query/result and obtained agreement before expanding the work.
6. **I treated architectural completeness as progress toward the economic answer.** The shadow
   ledger became more complete while the essential expected-versus-realized feedback loop remained
   absent.

This was my scoping error, not a requirement imposed by the data or by PostgreSQL.

### The corrected boundary

The appropriate split is now:

| Concern | Correct home |
|---|---|
| Public API collection, timing and schema parsing | minimal Java capture code |
| Immutable raw decision and exit facts | PostgreSQL tables |
| Funding and basis reconciliation | versioned SQL |
| Pair retention and turnover | versioned SQL |
| Fee, symbol and venue attribution | versioned SQL |
| Capital-allocation grid | SQL first; compact script only if sequential constraints require it |
| Charts or presentation | generated from final SQL result, after measurements exist |
| Live order execution | separate execution system, considered only after evidence |

This boundary preserves the valuable part of the existing work—the exact external snapshot—while
making every economic assumption visible in short queries another reviewer or AI model can validate.

## 12. Expected final result

After implementation and enough forward observations, one scorecard should show, for every policy
and period:

- settled funding P&L;
- exact two-leg relative-price P&L;
- maker and taker fees;
- slippage/fallback sensitivity;
- net P&L and return on total capital;
- average used and unused capital by venue;
- new, retained, closed and reversed pairs;
- contribution by symbol and venue pair;
- maximum drawdown and worst outcomes; and
- missing or late observation counts.

That scorecard—not the annualized funding headline and not the size of the implementation—decides
whether Bybit-maker routing, a different capital distribution, symbol filtering or leverage should
move forward.
