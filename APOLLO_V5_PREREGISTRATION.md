# Apollo V5 — pre-registered validation test

**Locked 2026-08-11, before any validation data was read.** Roadmap step 6.

This document exists because the candidate below was selected after roughly 100 training backtests
in a single session (`APOLLO_V5_ROADMAP.md` §3). With that much searching behind it, an unstructured
validation run would not be interpretable: a pass could be the luckiest of 100 tries, and a fail
would burn a one-shot resource while teaching little. Everything that could otherwise be decided
*after* seeing the result is fixed here in advance.

## The candidate

| Field | Value |
| --- | --- |
| Strategy | `apollo-v5-liquidity-limit` (Family B) |
| Strategy config | `config/backtests/apollo-v5-liquidity-limit-btc.properties` |
| Engine config | `config/backtests/engine.properties` |
| Symbol | **ETHUSDT** — single symbol, no substitutions |
| Interval | 15m |
| `baseMapLookbackDays` | 42 |
| `pocBinAtrFraction` | 0.1 |
| `maximumBoundaryTouches` | 999 (disabled) |
| `internalWaveMinimumShare` | 0 (disabled) |
| `maximumHoldingBars` | 96 |
| `minimumRewardRisk` | 3 |
| Volume-profile step | ETHUSDT = 10 (`VolumeProfilePriceSteps`) |

Training result being carried forward: 86 trades, +$7,268.82, PF 1.222, 6.20% max drawdown, 29
winners / 57 losers, 3 of 4 profitable subperiods, 41.2% largest-subperiod concentration.

**No parameter in this table may change before the validation run.** If any does, this
pre-registration is void and must be rewritten and re-dated.

## Gate 1 — evidence strength on existing data (no new data)

Run before touching validation; uses only training trades already spent.

**1a. Bootstrap on trade PnLs.** Resample the 86 training trades with replacement 10,000 times.
Report the 5th percentile of total net PnL.

- **Pass:** 5th percentile > 0 — i.e. the result stays profitable in at least 95% of resamples.
- **Fail:** otherwise. The apparent edge is not distinguishable from the luck of which 29 winners
  happened to land.

**1b. Winner-count floor.** The existing acceptance profile requires >= 60 *fills*. ETHUSDT has 86
fills but only **29 winners**, and all profit comes from those. Replace with **>= 25 winning
trades** as the operative floor for this payoff shape.

- **Pass:** 29 >= 25.
- Recorded for transparency: this bar is met, but only just. It is set at 25 rather than tuned to
  clear 29 comfortably.

**1c. Walk-forward stability within training.** Roll a 6-month evaluation window across the
training span in 3-month steps (7 windows). All windows are inside data already spent, so this
costs nothing held-out.

- **Pass:** at least 5 of 7 windows profitable, and no single window worse than -10% (the drawdown
  termination limit).
- **Fail:** otherwise — the edge is period-specific rather than persistent.

**All three of 1a, 1b, 1c must pass to proceed to Gate 2.** If any fails, validation is not opened
and the candidate returns to research.

## Gate 2 — the validation run (one shot)

Only if Gate 1 passes in full. Dataset `VALIDATION` = **`[2025-02-07, 2025-08-07)`**, run exactly
once with `-DbacktestDataset=VALIDATION`.

Pass requires **all** of:

| Criterion | Threshold |
| --- | --- |
| Net profit | > 0 |
| Profit factor | >= 1.10 |
| Maximum drawdown | <= 10% |
| Winning trades | >= 8 (scaled from the 25-winner floor for a 6-month window vs. 24-month training) |
| Stressed-cost net profit (1.5x) | > 0 |
| Termination | not `MAX_DRAWDOWN` |

**Explicitly forbidden after seeing the validation result**, regardless of outcome:
- Substituting a different symbol (XRPUSDT, SOLUSDT, BTCUSDT or any other).
- Changing any parameter in the candidate table and re-running validation.
- Re-running validation with a different date sub-range.
- Treating a partial pass ("net profit positive but PF 1.05") as success.

A fail means the candidate is rejected and research resumes on training data only. The final-test
window `[2025-08-07, 2026-02-07)` stays closed either way; it is reserved for a candidate that has
already cleared validation.

---

# Gate 1 results (2026-08-11) — **FAILED. Validation was not opened.**

## 1a. Bootstrap — FAIL

10,000 resamples of the 86 training trades with replacement, seed 20260811 (fixed in advance so the
result is reproducible and cannot be silently re-rolled):

| Statistic | Value |
| --- | ---: |
| 5th percentile | **-$10,921.15** |
| Median | +$7,130.76 |
| 95th percentile | +$26,883.87 |
| Resamples profitable | **73.8%** |

**Required: 5th percentile > 0. Actual: -$10,921.** Roughly **one resample in four loses money**.
The observed +$7,268.82 sits inside a distribution wide enough that it is not distinguishable from
luck at the 95% level.

Cause is visible in the trade composition: 29 winners averaging +$1,381 against 57 losers averaging
-$575. The result depends on a small number of large winners landing; resampling which ones land
swings the total from -$11k to +$27k.

## 1b. Winner-count floor — PASS

29 winning trades against a floor of 25. Met, but not comfortably.

## 1c. Walk-forward stability within training — PASS

Seven 6-month windows, 3-month steps, all inside already-spent training data:

| # | Window | Trades | Net | PF | Max DD |
| --- | --- | ---: | ---: | ---: | ---: |
| 1 | 2023-02-07 → 2023-08-07 | 12 | +$1,602.57 | 1.427 | 3.32% |
| 2 | 2023-05-07 → 2023-11-07 | 15 | **-$3,910.14** | 0.377 | 5.00% |
| 3 | 2023-08-07 → 2024-02-07 | 13 | +$2,072.65 | 1.466 | 2.32% |
| 4 | 2023-11-07 → 2024-05-07 | 12 | +$3,363.13 | 1.679 | 3.51% |
| 5 | 2024-02-07 → 2024-08-07 | 11 | **-$2,033.32** | 0.386 | 2.47% |
| 6 | 2024-05-07 → 2024-11-07 | 16 | +$6,149.56 | 1.993 | 4.06% |
| 7 | 2024-08-07 → 2025-02-07 | 13 | +$3,509.79 | 1.917 | 2.24% |

**5 of 7 profitable** (floor: 5) and worst drawdown 5.00% against a -10% limit. Passes.

## Verdict: candidate rejected at Gate 1; validation remains unopened

The pre-registration requires all three sub-gates. 1a fails, so **the validation window
`[2025-02-07, 2025-08-07)` was not read** and remains available for a future candidate.

### Why 1a and 1c disagree, and why 1a is the binding one

They measure different things and both results are real:

- **1c asks "does it work in different periods?"** — 5 of 7 windows profitable says the behaviour
  is not confined to one lucky stretch. That is genuine and encouraging.
- **1a asks "how much does the total depend on which particular trades landed?"** — with a payoff
  shape of few large winners against many small losers, the answer is: enormously. Even a strategy
  that is positive on average can show a 26% chance of an overall loss across a two-year sample of
  this size.

Passing 1c while failing 1a is the signature of **a possibly-real edge measured with too little
evidence**, not of a spurious one. The distinction matters for what comes next: the problem is not
that the strategy is wrong, it is that 29 winners cannot establish it.

### Implication: more evidence, not more tuning

The productive responses are the ones that raise the number of independent winning observations:

1. **More symbols.** ETHUSDT alone gives 29 winners. If the same frozen config produces a
   comparable payoff shape on several symbols, the pooled winner count rises without any parameter
   change. XRPUSDT/SOLUSDT/BTCUSDT results already exist at 42 days and could be pooled.
2. **More history.** The 2022-10-01 backfill extended data below the current training start;
   the training window could begin earlier for more trades on the same config.
3. **Higher trade frequency** — but only via a source-grounded change (e.g. Family C), never by
   loosening `minimumRewardRisk` or the volume gates to manufacture fills.

What is explicitly *not* indicated: re-tuning parameters until the bootstrap passes. That would
optimise against the evidence test itself and destroy its meaning.

## Note on final-test cleanliness

Under the previous calendar, `PROJECT_STATUS.md` recorded that final-test independence had been lost
for Apollo work informed by the August 2026 examples. The 2026-08-11 calendar shift moved final test
to `[2025-08-07, 2026-02-07)`, and all video-derived material is from February 2026 onward — i.e.
**after** the new final-test window. No Apollo work to date has read data inside the new final-test
range. It is therefore genuinely clean again, and should be protected accordingly.
