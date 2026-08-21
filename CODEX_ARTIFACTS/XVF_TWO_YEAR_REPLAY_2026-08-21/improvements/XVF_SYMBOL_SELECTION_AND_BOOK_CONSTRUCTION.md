# XVF symbol selection and book construction

**Created by:** OpenAI Codex  
**Analysis date:** 2026-08-21  
**Scope:** research-only improvement note; no production file was changed.

## Decision

The next book-construction improvement should be deliberately narrow:

1. keep **20 fixed equal-weight slots** as a maximum, not a quota;
2. keep **at most one cross-venue pair per canonical base**;
3. reject a genuinely new pair unless its three-day funding-signal proxy covers that pair's
   planned entry and exit fees;
4. build the remaining book subject to **actual free collateral per venue**, instead of ranking
   first and discovering the constraint from an order rejection;
5. apply liquidity, participation, instrument-rule and step-size checks before a candidate consumes
   a slot; and
6. leave unused slots in cash. Do not divide their capital among the survivors.

This can be implemented without claiming that basis is predictable. A later expected-net scorer
needs point-in-time basis and execution features that do not exist in the strict replay. Until then,
the original adjusted funding spread remains the rank signal; known fees and feasibility are gates,
not a new alpha model.

## Evidence boundary

Every new measured claim in this note uses only:

- `generated/candidates_production_like.csv`;
- `generated/funding_cutoff_daily.csv`; and
- the decision-grade strict replay in `xvf-strict-capital-policy-sim.py` and
  `xvf-production-like-sim.py`.

The replay has three venues: Binance, Bybit and Hyperliquid. Each independent one-year slice starts
with USD 1,500 on each venue, USD 4,500 total. Its baseline uses 20 slots, USD 112.50 per leg, a
uniform three-day rebalance, exact-pair retention, full-rank backfill after capital skips, and final
taker liquidation. It models realised funding and commissions. It does **not** model a basis-price
path, slippage, adverse maker selection, maker non-fills, forced delistings, liquidation, transfers
in flight, or live step-size rejection.

The CSV contains 8,052 candidate-cutoff rows across 241 rebalance cutoffs. It has no duplicate
`(cutoff, base)` and no same-venue pair. That means the strict input already enforces one candidate
per canonical base and two distinct venues. It exports only the **widest gross-spread pair** for a
base, however; it does not preserve the losing venue-pair alternatives that a fee-net optimizer
would need.

Statements marked **Measured** below are results from those artifacts. Statements marked
**Proposed** are architecture or policy and have not been validated by a live fill replay.

## What the current code does

| Concern | Current behavior | Remaining selection gap |
| --- | --- | --- |
| Base uniqueness | `bestCrossVenuePair` emits one candidate per normalized base and excludes same-venue combinations. | Preserve this as an explicit optimizer constraint. |
| Pair choice | Chooses the largest adjusted funding spread for the base. | A slightly narrower pair can have lower fees and higher fee-net value; alternative pairs are discarded too early. |
| Entry threshold | One global `spread > 20%` rule. | The fee break-even differs materially by venue pair and route. |
| Book cap | Reporting takes top 20; execution walks the uncapped ranks and backfills after an operational skip. | Good fallback behavior, but it is greedy and unaware of venue-wide collateral opportunity cost. |
| Position size | `capital / (20 x 2)` stays fixed even when the book is sparse. | Preserve this. Resizing survivors would introduce concentration and a different strategy. |
| Venue capital | Every gateway exposes `availableCapital()`, but entry selection does not use it. Insufficient margin is discovered from maker rejection. | Query usable free collateral before selection and reserve it in the book optimizer. |
| Liquidity | Signal requires USD 500k weekly quote volume; execution caps a leg at 1% of the thin leg's weekly volume and skips below 50% of target. | The strict replay does not model capped half-sized legs, and the selection score is not scaled when a leg is capped. |
| Instrument sizing | Both legs use their own venue prices; `size()` checks positive size, minimum notional and at most 1% post-rounding notional imbalance. | `MIN_STEPS_PER_LEG = 100` is declared but is not checked by `size()`. |
| Fees | Production configuration has generic maker/taker constants, not the strict replay's venue-specific fee table. | Selection needs the account's actual fee tier and the route that will really be used. |

`availableCapital()` is also too ambiguous for an optimizer today: Binance and Bybit adapters return
wallet balance, while Hyperliquid returns account value plus spot USDC. A safe selector needs
`freeCollateralAfterOpenOrders`, existing initial margin, and a separately declared operational
reserve, not three superficially similar wallet numbers.

## Fee break-even filtering

### Measured fee hurdle

The strict fee policy assumes the current thinner-venue maker route and taker exits on both legs.
For a three-day hold:

```text
gross proxy, bps = adjusted annual spread in % x 3 / 365 x 100
break-even annual % = known round-trip bps x 365 / (3 x 100)
```

| Venue pair | Planned entry, bp | Taker exit, bp | Round trip, bp | Three-day break-even annual spread |
| --- | ---: | ---: | ---: | ---: |
| Binance–Bybit | 8.1 | 14.5 | 22.6 | **27.50%** |
| Binance–Hyperliquid | 6.3 | 9.0 | 15.3 | **18.62%** |
| Bybit–Hyperliquid | 11.8 | 14.5 | 26.3 | **32.00%** |

The global 20% threshold is already above fee break-even for Binance–Hyperliquid. It is below
break-even for Binance–Bybit and Bybit–Hyperliquid.

Among rows ranked inside the desired top 20, the proportion clearing the pair-specific hurdle was:

| Independent slice | Binance–Bybit | Binance–Hyperliquid | Bybit–Hyperliquid | All top-20 rows |
| --- | ---: | ---: | ---: | ---: |
| 2024-08-21 to 2025-08-21 exclusive | 348 / 732 (47.5%) | 585 / 585 (100.0%) | 126 / 375 (33.6%) | **1,059 / 1,692 (62.6%)** |
| 2025-08-21 to 2026-08-21 exclusive | 1,546 / 1,756 (88.0%) | 213 / 213 (100.0%) | 270 / 352 (76.7%) | **2,029 / 2,321 (87.4%)** |

These are candidate-cutoff rows, not independent trades; retained positions recur across cutoffs.
They show why the hurdle must be pair-specific, not how many live orders would fill.

### Measured replay effect

The strict simulator applies the fee hurdle only to genuinely new entries because a retained
position's entry fee is already sunk.

| Policy | Prior net | Prior return | Prior average positions | Recent net | Recent return | Recent average positions |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline 20% threshold | +USD 93.57 | +2.08% | 12.78 | +USD 212.02 | +4.71% | 11.25 |
| Pair-specific maker-fee hurdle | **+USD 107.91** | **+2.40%** | 9.47 | **+USD 260.87** | **+5.80%** | 8.03 |

The filter improved funding-minus-fees net in both independent slices while holding fewer pairs.
It reduced gross funding as intended and removed more commissions. It does not establish true
profitability because basis and live execution remain absent.

### Proposed production rule

For a **new** entry, calculate fees from the actual account tier and selected execution route:

```text
knownCostBps = entryMakerOrTakerBps(short leg)
             + entryMakerOrTakerBps(long leg)
             + plannedExitBps(short leg)
             + plannedExitBps(long leg)

feeExcessBps = grossFundingProxyBps - knownCostBps
```

Require `feeExcessBps > 0` before selection. Persist all terms and the rejection reason. Do not
hard-code the table above into signal logic: changing maker routing or fee tier changes the hurdle.

For an exact retained pair, do not charge entry again and do not close it merely because it would
fail a *new-entry* hurdle. Initially preserve the strict replay's retention rule. A later switching
model should compare keeping with closing-and-replacing after charging both turnover legs.

The raw adjusted spread is still only a proxy for future funding; the current source documentation
already says it over-reads forward realization. Therefore the fee hurdle is a defensible minimum,
not proof of positive expected net.

## Position count and concentration

### Measured sensitivity

For this note, the strict simulator was rerun at caps 5, 10, 15 and 20. Total starting capital stays
USD 4,500 and remains equally split across venues. Leg notional is **rescaled** to
`4500 / (2 x N)`, so a complete N-pair book is still 1x gross and each pair is `1/N` of capital.
This deliberately tests the concentration/cap choice; it is not the proposed behavior when a
20-slot live book happens to have empty slots.

| Cap N | USD/leg | Pair weight at full book | Baseline prior / recent return | Fee-filter prior / recent return | Fee-filter average pairs prior / recent |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 5 | 450.00 | 20.0% | -1.38% / +0.65% | -1.08% / +1.99% | 4.50 / 3.74 |
| 10 | 225.00 | 10.0% | +0.06% / +3.50% | +0.54% / +4.64% | 7.62 / 6.28 |
| 15 | 150.00 | 6.7% | +1.33% / +4.53% | +1.84% / **+6.01%** | 9.10 / 7.70 |
| 20 | 112.50 | 5.0% | **+2.08% / +4.71%** | **+2.40%** / +5.80% | 9.47 / 8.03 |

The baseline improved through 20 in both slices. Under the fee filter, 20 led the prior slice and
15 led the recent slice by only 0.21 percentage points. That is not a stable case for changing the
configured cap to 15. The sensitivity also mixes breadth, per-pair notional, venue-capital blocking
and turnover fees; it is not a clean rank-alpha estimate.

### Proposed concentration policy

- Retain `POSITIONS = 20` as the maximum.
- Keep slot notional fixed at `capital / 40`, even when only eight candidates pass. Empty slots stay
  cash; eight survivors must not become eight 12.5%-of-capital bets.
- Allow zero to twenty entries. Never open a fee-negative or infeasible symbol merely to fill a
  quota.
- Enforce one pair per canonical base across quote variants and multiplier aliases.
- Do not add a pair-type, sector, issuer or chain cap yet. The strict input has none of the taxonomy
  or dependence measurements needed to set one honestly.
- Report actual pair count, largest pair weight, venue leg concentration and unused cash for every
  book.

## Venue-capital-aware selection

### Measured constraint

The desired top-20 venue demand was highly uneven even in the strict three-venue replay:

| Slice | Binance desired legs median / p90 / max | Bybit | Hyperliquid |
| --- | ---: | ---: | ---: |
| Prior | 12 / 16 / 18 | 10 / 15 / 17 | 8 / 15 / 20 |
| Recent | 17 / 19 / 20 | 18 / 20 / 20 | 5 / 7 / 9 |

With USD 1,500 fixed on every venue, the baseline held only 12.78 and 11.25 pairs on average. The
recent slice recorded 4,106 capital skips. Thus the nominal top 20 and the fundable book are
different objects.

The strict transfer-only counterfactual was also unstable: it moved prior return from 2.08% to
1.86%, but recent return from 4.71% to 8.95%. That does not support transferring every time target
demand moves. Construct within standing balances first; treat infrequent collateral transfer as a
separate policy with its own costs, delay, cooldown and reserve.

### Proposed constrained selector

At one fixed slot notional, convert each venue's safely usable collateral into integer leg slots:

```text
capacitySlots[v] = floor(
    (freeCollateral[v] - operationalReserve[v] - feeReserve[v])
    / requiredInitialMarginPerLeg[v]
)
```

Then solve the small deterministic 0/1 problem:

```text
maximize  sum(x[i] * feeExcessBps[i])

subject to
  sum(x[i]) <= 20
  sum(x[i] for candidates using venue v) <= capacitySlots[v]
  sum(x[i] for candidates with canonical base b) <= 1
  x[i] = 0 for every failed freshness, liquidity, fee, rule or sizing gate
```

With three venues and at most twenty slots, a pure-Java dynamic program over the three venue-slot
capacities is small and reproducible. It is preferable to "sort, try, and learn from insufficient
margin" because a greedy high-ranked pair can consume two scarce venue slots while a different
combination produces a larger feasible fee-net book.

For the first implementation, use `feeExcessBps` only as the objective among candidates that already
pass the existing funding-signal rules. Do not multiply position size by spread. Equal sizing is a
separate frozen strategy choice.

Persist a decision row for every considered candidate with:

- gross rank and adjusted spread;
- chosen venue pair and planned maker route;
- gross proxy, entry fee, exit fee and fee excess;
- requested notional and required margin on each venue;
- each hard-gate result;
- optimizer-selected boolean; and
- stable tie-break order.

That makes "why did rank 23 enter while rank 4 did not?" answerable after the fact.

## One pair per base, but choose it at the right stage

One-per-base is correct for concentration and is already satisfied by the strict CSV. The ordering
of operations needs improvement:

```text
current:
  all legs for base -> widest gross pair -> fee/feasibility checks later

proposed:
  all legitimate cross-venue pairs for base
    -> pair-specific fee and feasibility data
    -> best eligible fee-net pair for that base
    -> global venue-capital optimizer
```

This requires the signal layer to retain every ordered cross-venue alternative long enough to score
it. The strict CSV cannot measure the benefit because its SQL uses `DISTINCT ON (..., base)` before
export and retains only the gross winner. Until a new no-lookahead export is created, this is a
proposed architecture change, not a measured return improvement.

An existing live pair must be identified by the exact base, direction, venues and venue symbols.
"Either symbol is already open" is not enough to decide whether a pair is retained, reversed,
partially present, or belongs to something else.

## Liquidity and step constraints

### Supported now

The strict candidate export already applies a point-in-time approximation of the USD 500k weekly
quote-volume floor to both legs. Production also has live instrument rules, each venue's own price,
minimum notional, post-rounding imbalance, and an uncapped rank list for backfill.

### Proposed hard-gate order

For each prospective full-sized slot:

1. funding data passes venue and per-series freshness/completeness;
2. two distinct venues and one verified canonical asset mapping;
3. both contracts are live linear perpetuals in the configured collateral asset;
4. each leg's weekly quote volume is at least USD 500k and the volume observation is fresh;
5. full target notional is no more than 1% of the thinner leg's weekly quote volume;
6. each rounded quantity is at least the venue minimum quantity and notional;
7. each rounded quantity contains at least `MIN_STEPS_PER_LEG` native quantity steps;
8. the two rounded USD notionals differ by no more than 1%;
9. the live book is fresh and a capped hedge can reach the requested amount within the execution
   slippage limit; and
10. both venues have reserved collateral for the leg and its fees.

The missing step guard is mechanically checkable:

```text
makerQty / makerStepSize >= 100
takerQty / takerStepSize >= 100
```

The strict replay assumes every accepted leg is exactly USD 112.50. Production currently allows a
liquidity cap down to 50% of the slot. That creates unequal weights absent from the replay. The
evidence-aligned first policy is to skip unless the **full** slot fits. If half-sized legs are kept,
actual notional must enter both the optimizer and replay, and effective-position concentration must
be reported.

## Features required for basis-aware expected-net scoring

No expected basis return is calculated in this note. The strict replay contains no point-in-time
cross-venue price path, so treating basis as zero or importing a single average drag would not make
symbol-level ranking basis-aware.

A defensible scorer needs these new immutable features at each signal cutoff:

1. **All venue-pair alternatives per base**, not only the widest gross pair.
2. **Funding cash-flow forecast by stamp** over the intended hold: current projected rate, interval,
   next funding time and uncertainty for each leg. The trailing seven-day signal remains a feature,
   not the cash-flow forecast itself.
3. **Contract-normalized entry basis:** synchronized mark/mid/index prices for both legs, multiplier
   and quote normalization, with source timestamps.
4. **Historical basis state:** current level, change, volatility, tail excursions and pair-specific
   conditional forward change over the exact holding horizon, built with no lookahead.
5. **Execution telemetry:** maker-fill probability, partial-fill ratio, post-only rejects,
   1/5/30-second maker markout, hedge latency, taker slippage by venue/symbol/size, and abandoned
   opportunities.
6. **Account-specific costs:** actual fee tier, chosen route, funding/settlement currency conversion,
   collateral transfer cost and latency.
7. **Turnover state:** whether the exact pair is already held, so entry cost is sunk and a switch is
   charged old-pair exit plus new-pair entry.
8. **Risk state:** available collateral, initial/maintenance margin, liquidation distance and a
   declared penalty for model uncertainty and concentrated venue exposure.

The eventual score, in pair-level basis points on one leg's notional, should have an auditable
decomposition:

```text
expectedNetBps = expectedFundingBps
               + expectedBasisPnlBps
               - expectedEntryExecutionBps
               - expectedExitExecutionBps
               - expectedTransferBps
               - riskAndUncertaintyPenaltyBps
```

The global selector would maximize expected net USD subject to the same base, venue-capital,
liquidity, size and concentration constraints. It should first run in shadow mode beside the
fee-only selector. A basis feature should not control live symbols until its forecasts are calibrated
out of sample and the full realized decomposition reconciles funding, basis, fees and slippage.

## Recommended implementation sequence

1. **Shadow fee gate:** add per-venue/account fee schedules and log pair-specific fee excess without
   changing orders.
2. **Close the mechanical gaps:** enforce 100-step sizing, fresh rule/volume timestamps, full-slot
   liquidity, and exact-pair identity.
3. **Expose actual capacity:** replace ambiguous wallet balance with free collateral, used margin,
   open-order margin and declared reserve; take one consistent snapshot before construction.
4. **Retain pair alternatives:** score all legitimate venue pairs within a base, then enforce one
   selected pair per base.
5. **Run the constrained selector in paper mode:** compare its chosen book with strict greedy
   backfill and persist every rejection/selection reason.
6. **Replay the exact proposed policy:** include actual per-leg notional and retained-pair turnover.
   Do not infer improvement from deployment alone.
7. **Add basis and execution features in shadow:** only after the fee/capital book is deterministic
   and reproducible.

Promotion criteria should include more than headline return: funding, entry/exit fees, basis P&L,
fill ratio, turnover, average and minimum pair count, effective positions, largest pair, venue
utilization, capital skips, rule/step skips, and unresolved/missing settlement rows.

## Reproducing the position-count sensitivity

This command changes only in-memory simulator constants and reads only the two strict generated CSVs:

```bash
python3 - <<'PY'
import importlib.util
import sys
from pathlib import Path

path = Path("CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/xvf-strict-capital-policy-sim.py")
spec = importlib.util.spec_from_file_location("strictsim", path)
sim = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = sim
spec.loader.exec_module(sim)

candidates, funding = sim.load_inputs(
    path.parent / "generated/candidates_production_like.csv",
    path.parent / "generated/funding_cutoff_daily.csv",
)
policies = (
    sim.Policy("baseline"),
    sim.Policy("maker_fee_filter", filter_mode="maker_roundtrip"),
)

for positions in (5, 10, 15, 20):
    sim.POSITIONS = positions
    sim.LEG_NOTIONAL = 4500.0 / (2 * positions)
    for policy in policies:
        results = [
            sim.simulate(candidates, funding, start, end, policy)
            for start, end, _label in sim.PERIODS
        ]
        print(
            positions,
            policy.name,
            *(f"{result.return_pct:.4f}%/{result.avg_positions:.4f}" for result in results),
        )
PY
```

The output field for each slice is `return / average positions`. The command does not test basis or
live fills and should not be interpreted as doing so.
