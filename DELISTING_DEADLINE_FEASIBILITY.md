# Delisting-deadline basis — outcome-blind feasibility result

**Decision date:** 2026-09-13  
**Idea board:** #19  
**Verdict:** **FAIL AT THE DATA/HEDGEABILITY GATE — DO NOT OPEN PRICE DATA**

## Question

When Binance announces a fixed restriction and automatic-settlement deadline for a USDⓈ-M
perpetual, can an expensive perpetual be shorted against a long position in the same asset on spot
and held as the basis converges?

Only the paired trade was in scope. A naked delisting short was never admissible because Binance
can change leverage, margin, funding, index constituents and price protection, while final-hour
liquidations may reach ADL. Binance also recommends closing before trading ceases. The current
general procedure permits only position-reducing futures orders in the final 30 minutes and settles
remaining positions at the average of 1,800 one-second index observations, charging the taker fee.

Official mechanics:

- <https://www.binance.com/en/support/faq/detail/dd60dfbf654d4055aa6b217ea6d5ddba>
- example notice: <https://www.binance.com/en/support/announcement/detail/ba5d61807b474b0ca9f40250e7fa782c>
- 2024-11-11 settlement-method change: <https://www.binance.com/en-AE/support/announcement/detail/4bcabddf0e81423ebca242e185bf157d>

## What was checked without outcomes

The source audit inventoried all 431 titles in Binance's official Delisting catalog before the
2026-09-13 UTC cutoff. It fetched all 76 bodies selected by a deterministic explicit-futures title
rule, including `USDⓈ-M ... Perpetual Contract ... Postponed` titles that do not contain the word
`Futures`. This is a complete ledger for that declared catalog/title scope, **not** a claim to cover
every futures auto-settlement mechanism: rebrand/token-swap notices outside the Delisting catalog
are excluded because the underlying identity changes.

The first parser version incorrectly left OMGUSDT at its original 2024-12-16 deadline. The catalog
contains two later official postponements. The corrected ledger records all three clocks, marks the
first two superseded, and excludes the final 2025-01-31 clock because its notice does not state an
exact replacement new-position restriction. No OMG event reaches the hedge audit.

The corrected terms ledger contains:

- 75 actual futures-delisting articles and 146 parsed contract rows;
- 84 USDT contract rows;
- 74 rows with an exact settlement clock, exact restriction clock, at least six hours of notice and
  no unresolved revision;
- 32 independent settlement clocks;
- 59 rows / 26 clocks in the current 30-minute settlement regime;
- 15 rows / 6 clocks in the legacy one-hour regime, retained only as an appendix.

Twelve unresolved settlement lines are all from 2022 through early 2023. None can enter the exact-
clock cohort or the current-regime gate. Mutable current CMS bodies remain a limitation; an article
that discloses an in-place clock revision without sufficiently timestamped replacement terms is
excluded rather than reconstructed by assumption.

Next, the archive checker used HTTP `HEAD` plus Binance's tiny official `CHECKSUM` objects. It did
not open any market-data ZIP. For every terms-eligible event it tried both the monthly object and the
exact UTC daily fallback for six streams:

1. futures 1-minute trade klines;
2. futures 1-minute mark-price klines;
3. futures 1-minute index-price klines;
4. futures aggregate trades;
5. same-asset spot 1-minute trade klines;
6. same-asset spot aggregate trades.

Spot mapping was frozen before requests: exact same symbol by default, mechanically remove a
leading `1000` with a recorded 1,000-token multiplier, no hand-selected aliases, and no single-asset
mapping for the BLUEBIRD, FOOTBALL or DEFI index contracts. Object existence is only an upper bound;
it does not prove that the exact event minute exists inside the archive.

## Result

The current-rule population fails the pre-price minimum of 12 independent settlement clocks:

| Gate | Result |
|---|---:|
| Source contracts | 59 |
| Independent settlement clocks | 26 |
| Contracts with all six archive streams | **9** |
| Independent clocks with all six streams | **6** |
| Contract coverage | **15.3%** |
| Required independent clocks | **12** |
| Gate | **FAIL** |

The nine upper-bound events are KDA, FLM, PERP, QUICK, SXP, FIS, REI, VOXEL and AI. FLM/PERP
share one deadline and FIS/REI/VOXEL share another, leaving only six independent clocks. Daily
objects rescued no additional current event. Fifty current events lack one or both same-asset spot
streams; AERGO also lacks the required futures objects. The legacy regime has 12 contracts but only
five independent clocks and cannot be pooled across a different settlement formula.

Execution evidence is worse than the six-clock count suggests: an official futures `bookTicker`
daily archive exists for only two of all 74 terms-eligible events, both legacy index contracts, and
for **zero** current-rule events. Klines and aggregate trades are not proof that a two-leg market
order of a specified size could fill. Therefore timestamp parsing cannot turn this into an
execution-valid sample.

## Decision

Board #19's Binance-perpetual/Binance-spot implementation is closed at feasibility. No price,
basis, volume, funding or return field was read, and no retrospective P&L screen will be run. The
idea failed because the historical paired trade cannot be reconstructed across enough independent
modern deadlines, not because an observed return was negative.

Searching other venues after this failure is not an automatic rescue. It would require a separately
frozen venue priority, token/unit mapping, historical delisted-instrument status, synchronized
two-leg execution data and one-leg failure rule. Reopen #19 only if such a source adds enough data
to reach at least 12 current-regime independent clocks **before** any outcome is inspected. Do not
choose the venue with the best surviving return after the fact.

## Reproducibility

Official archive layout and checksum convention:
<https://github.com/binance/binance-public-data>

| File | SHA-256 |
|---|---|
| `scripts/audit-binance-futures-delisting-events.py` | `365cb6c834efdcc5c02e3a71c063b72e080f549757a2f304f89874814fd279d7` |
| `research/binance-futures-delisting-source-audit-2026-09-13.json` | `e75e01651f17bc40e5514ccfa75a7e8c0ce8f05cc060950d9f306a10f511a14e` |
| `scripts/build-binance-futures-delisting-event-ledger.py` | `8d3ad5c6cbabaa5827c6053c5dcb61a19d3c6510fe7fe2c7c6814dc8aea88ce2` |
| `research/binance-futures-delisting-event-ledger-2026-09-13.json` | `33a7d8c7173b8e81cf2d0e490a25f321200d503d61f7b5eeb1752d31189a0927` |
| `scripts/check-binance-futures-delisting-archives.py` | `3970e2f439285a594f81118053c25dff0b2b6f45649954bbdcb1fad4f57df1ed` |
| `research/binance-futures-delisting-archive-availability-2026-09-13.json` | `b6161c8384c1deee88a5a96527ab903b1baa0104af72568b1fe107ce58589f12` |
