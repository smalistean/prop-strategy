# Equity-perp dividend reopening — one-look result

**Revealed:** 2026-09-13 11:16 UTC.

**Verdict:** **FAIL. Close board #17. Do not retune the trigger, clock or horizon on these
events, and do not authorize shadow or live capital.**

This was the single retrospective reveal declared in
`DIVIDEND_REOPENING_PREREGISTRATION.md`. The declaration, outcome script, population and all
235 official Binance archive checksums were frozen before any archive body was downloaded or
opened. The result failed economic, sample, execution, attrition, stability and concentration
conditions. It is not an insufficient-information result.

## Primary result

Of 50 frozen current-regime events, 34 had usable pre-entry signals and six crossed the fixed
50 bp threshold on six dates. One triggered DIS event had no active exit bar from 20:18 through
20:23, leaving five executed trades on five dates.

| Metric | Frozen requirement | Observed |
|---|---:|---:|
| Signal attrition | <= 20% | **32%** (16/50; all inactive trade bars) |
| Trigger dates | >= 12 | **6** |
| Triggered execution failures | 0 | **1** |
| Gross mean before cost | diagnostic | **-4.7 bp** |
| Date-weighted net mean at 25 bp | >= +25 bp | **-29.7 bp** |
| Event-weighted net mean at 25 bp | >= +25 bp | **-29.7 bp** |
| Median net at 25 bp | diagnostic | **-46.3 bp** |
| Date-declustered t-statistic | >= 2.0 | **-0.81** |
| Profitable executed dates | diagnostic | **1/5** |
| Net mean at 13 / 40 bp cost | 40 bp must be non-negative | **-17.7 / -44.7 bp** |

Each executed date contained one trigger, so date- and event-weighted primary means coincide.

| Event | Adjusted gap | Fade | Net at 25 bp |
|---|---:|---|---:|
| 2026-06-03 QCOM | +68.65 bp | short | +93.68 bp |
| 2026-06-10 TSM | +53.84 bp | short | -46.27 bp |
| 2026-06-29 DIS | +52.63 bp | short | execution failure |
| 2026-07-05 MU | +119.92 bp | short | -125.39 bp |
| 2026-07-09 ORCL | +69.03 bp | short | -4.31 bp |
| 2026-08-16 NVO | -87.52 bp | long | -66.27 bp |

## Controls and stability

No triggered event had a same-symbol, same-weekday, same-regime, same-sign control inside the
frozen 0.8-1.25 absolute-gap caliper. The nearest ratios ranged from 0.216 to 1.622, so the
matched-control gate was correctly unevaluable and the screen also failed its eight-date
minimum. This does suggest that the triggered boundary displacements were unusual relative to
same-clock controls; it does **not** imply a profitable reversal.

The standalone 50 bp fade on all usable non-event controls triggered 25 times across 16 dates
and also lost: -3.2 bp date-weighted and -6.0 bp event-weighted after 25 bp cost.

Stability checks were worse than the headline:

- removing QCOM, the only positive trade and 100% of positive P&L, produced -60.6 bp;
- pre- and post-2026-07-16 means were -20.6 bp and -66.3 bp;
- the alternative `P_minus` dividend proxy produced exactly the same triggers and returns;
- the non-decision five-minute appendix was -14.8 bp, while the 30-minute appendix was only
  +2.2 bp with t = 0.07. Neither can alter the verdict or become a new rule on this dataset.

## Integrity and artifacts

The parser checksum-verified all 235 available archives (172,591,677 compressed bytes), read
7,674,351 rows, found no duplicate timestamps, and reconstructed the complete 50-event / 310-
control ledger from source before writing the result.

| Artifact | SHA-256 |
|---|---|
| Outcome-blind code freeze | `9f7c5cfe367f37d2784215cfc8021c3bb14a89656a5421fb6ead1d9c85dc32fb` |
| Download manifest | `0fd0e4013a0eb22ef8b3b69f2f3dbdf1be769e7ed023f4a2d5f16cd94de02bce` |
| Complete ledger | `45bf347242ad8ec5a15b1becbc56edb845b25e3ebff968792ccd34fc2837347f` |
| Frozen result | `0f1d004e01be9f38a323a9965b38a99faf11f4e0b674a4cecc907bf564a22554` |

Machine-readable artifacts are under `research/dividend-reopening-*`; raw downloaded archives
remain ignored under `data/dividend-reopening/`.

## Decision

The dividend adjustment does create a clean, causal clock and occasionally large residual
displacements, but the declared post-reopening fade did not forecast the next 15 minutes. Close
#17 without threshold or horizon mining. Proceed to board #19's outcome-blind delisting-event
ledger; any later dividend-reopening hypothesis requires a genuinely new mechanism and embargoed
data, not a re-cut of these 50 events.
