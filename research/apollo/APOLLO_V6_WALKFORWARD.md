# Apollo V6 — rolling-origin walk-forward on existing data (2026-08-11)

Run while the Phase 0 import was still downloading, on the Phase 1a trade logs, with no new
backtests. Two findings, one about the delta hypothesis and one about the V5 baseline itself. The
second is the more important, and it is unfavourable.

## Method

Every Apollo figure to date is in-sample: configurations were chosen while looking at the same two
years they are scored on. Re-scoring the same choice on sub-periods does not repair that, because
the choice already saw those sub-periods.

What *can* be measured honestly on existing data is the **selection procedure**. At each fold
boundary, pick the configuration that looks best using only data before the boundary, then score it
on the following three months, which that choice could not have seen. Expand the selection window
and repeat. Pooling the test folds answers the question that matters: does choosing a delta
threshold on past data help on future data?

Test folds are sliced from full-window runs rather than re-run per fold. This is deliberate and is
the more faithful emulation: a real walk-forward standing at time *t* has all history before *t*
available to the 42-day base map, which is exactly what a full-window run provides. Re-running from
a truncated start would instead handicap the map and understate performance.

**Caveat:** position size is a fraction of running equity, so a trade's magnitude depends on the
path before it within its run, and slicing cannot undo that. It distorts magnitudes, not the sign of
a comparison, and it applies identically to every configuration compared.

**Contamination that remains:** this controls for *which* configuration is chosen, not for *which
configurations were on the menu*. The aligned hypothesis and the -0.03 value were both invented
after looking at this window. A genuinely clean test still requires the 2021-22 data.

## Finding 1 — the procedure beats the baseline out-of-sample

| Test fold | Chosen on past data | OOS net | Baseline OOS net |
| --- | --- | ---: | ---: |
| 2024-02 .. 2024-05 | absorption +0.03 | -$1,299 | -$1,464 |
| 2024-05 .. 2024-08 | aligned -0.03 | +$8,726 | -$1,842 |
| 2024-08 .. 2024-11 | aligned -0.03 | +$9,848 | +$6,483 |
| 2024-11 .. 2025-02 | aligned -0.03 | -$9,553 | -$4,108 |

Pooled across the four test folds:

| | Trades | Net | Per-trade | Block-bootstrap P(profit) |
| --- | ---: | ---: | ---: | ---: |
| procedure-selected | 131 | +$7,722 | **+$58.95** | 64.0% |
| always baseline | 344 | -$931 | **-$2.71** | 48.0% |
| always aligned -0.03 *(hindsight)* | 157 | +$15,582 | +$99.25 | 75.8% |

The delta filter helps on data its selection had not seen: +$58.95 against -$2.71. That is the
first evidence for the delta idea that is not purely in-sample.

Two qualifications on it. **Selection is unstable** — the first fold chose absorption, and lost
money doing so; the gap between +$58.95 and the hindsight +$99.25 is precisely the cost of not
knowing in advance which hypothesis was the right one. And **64.0% is far below the plan's 90%
stopping-rule threshold.**

## Finding 2 — the V5 baseline edge is one six-month window

| Half-year | Baseline trades | Baseline net | Per-trade | Aligned -0.03 net | Per-trade |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2023-02 .. 2023-08 | 84 | +$234 | +$3 | +$1,747 | +$55 |
| **2023-08 .. 2024-02** | 162 | **+$14,697** | **+$91** | +$7,173 | +$104 |
| 2024-02 .. 2024-08 | 161 | -$3,306 | -$21 | +$15,287 | +$212 |
| 2024-08 .. 2025-02 | 183 | +$2,375 | +$13 | +$295 | +$3 |

**The entire +$14,000 headline is one half-year.** The remaining eighteen months net **-$697**
combined. The +$23.73 per-trade figure that `APOLLO_V6_PLAN.md` §1 treats as V5's standing result,
and that every subsequent comparison is measured against, is not a persistent edge — it is one
favourable regime (the late-2023 rally) plus noise.

This reframes several earlier conclusions:

- The 70.2% bootstrap P(profit) was never measuring what it appeared to. Resampling trades from a
  sample whose profit is concentrated in one period mostly re-measures that period.
- The §2 diagnosis — "a signal-selection problem, not a cost problem" — still holds, but is
  understated. It is not only that some symbols lose; it is that on most of the calendar the
  strategy has no edge on any symbol.
- Phase 0's stated purpose ("if the out-of-sample per-trade edge is materially negative, V6 stops")
  now has a live chance of triggering.

The aligned configuration looks better on this axis: positive in **all four** half-years rather than
one. That is a real robustness advantage and the strongest argument for it so far. But its most
recent half-year is +$3 per trade — it decays in the same window the baseline does, which suggests
both are exposed to whatever changed in late 2024 rather than the filter being independent of it.

## What this changes

The out-of-sample test on 2021-10..2022-10 is now the decisive question for **both** the delta
hypothesis and V5 itself. That period is a bear market, unlike anything in the current window, and
Finding 2 makes regime dependence the leading explanation for everything measured so far.

Prediction registered before that data is available: if the edge is regime-dependent rather than
structural, aligned -0.03 will *not* be materially profitable on 2021-22. If it is profitable there,
the regime explanation is wrong and the effect is real.

## Supporting tooling

`BacktestConfigurationLoader` gained `-DdatasetStart` / `-DdatasetEnd` so a fold does not need its
own engine file. The override is constrained rather than free-form: it may address only the selected
dataset's own window, or the prehistory before `data.trainingStart` that no window claims. Any
overlap with validation or final-test is rejected unless that dataset type was explicitly selected,
so a mistyped fold boundary cannot silently read reserved data.
