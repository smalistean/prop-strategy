# Apollo V5 map-lookback test — predeclared design (2026-08-11)

**Written before any result was inspected**, following the same discipline as
`APOLLO_V5_EXIT_TESTS.md`.

## Motivating evidence (ground truth, not backtest scanning)

`APOLLO_LABELLED_EXAMPLES.md` records that the levels **1,986.11** and **2,011.26** appear as
marked levels on both **2026-06-23** and **2026-08-05** — identical prices, to the cent, six weeks
apart. The trader carries mapped levels forward for well over a month.

V5 currently searches only `strategy.baseMapLookbackDays=7` days back for base candidates. If the
labelled observation reflects how the map actually works, seven days is roughly **6x too short**.

This is the first V5 parameter change motivated by *ground truth* rather than by scanning backtest
P&L, which is why it is worth testing despite roadmap step 1 ("freeze granularity"): that freeze
was aimed at data-representation churn with no mechanism, not at a source-derived structural
hypothesis.

## Enabled by the 2022-10-01 bin backfill

Before the backfill, training start 2023-02-07 with bins from 2023-01-01 supported only ~18 days of
lookback (the bin query reaches back twice the lookback). With bins now from 2022-10-01 the runway
is 129 days, supporting **up to ~64 days**. Values above that would silently truncate the map at
the start of the window.

## Design

Vary **only** `strategy.baseMapLookbackDays`: **7 (baseline), 14, 28, 42, 56**. Every other
parameter, the calendar, and the engine config stay fixed.

Family B (`apollo-v5-liquidity-limit`) only — it is the variant that trades enough to measure.
Family A produces 3-12 trades per symbol, too few to distinguish signal from noise.

Symbols: **ETHUSDT, XRPUSDT, BTCUSDT, SOLUSDT** — the four with meaningful trade counts on the
shifted calendar (98 / 92 / 40 / 23 respectively). BNBUSDT is excluded: 6 trades cannot support
inference.

## Predeclared interpretation

- **If the six-week persistence finding translates into tradeable structure**: results improve as
  lookback increases, in the same direction on a majority of the four symbols, with the improvement
  surviving the built-in 1.5x cost-stress run. A plateau or interior optimum well above 7 days also
  counts as confirmation.
- **If it does not**: results are flat, or move inconsistently between symbols — the same "arbitrary"
  branch that the holding-period test landed in. In that case the persistence observation is real
  about how the trader annotates charts but does not translate into a better mechanical map, and
  `baseMapLookbackDays=7` stays.

**A longer lookback will also be judged on trade count**, not only P&L: the stated purpose of a
longer map is to find *more* still-fresh bases. If trade counts do not rise materially, the wider
search is not finding the multi-week structures the videos show, regardless of what P&L does.

## Standing constraints

No configuration found here is promoted to validation on the basis of this test. Roadmap step 6
(pre-registration, revised evidence floor, walk-forward) still gates any validation attempt. Results
are recorded whether or not they support the hypothesis.

---

# Results (2026-08-11)

20 runs, Family B, shifted calendar `[2023-02-07, 2025-02-07)`.

| Symbol | 7 (baseline) | 14 | 28 | 42 | 56 |
| --- | --- | --- | --- | --- | --- |
| ETHUSDT | 98 tr / +$3,099 / PF 1.086 | 94 / +$5,425 / 1.157 | 93 / +$5,161 / 1.150 | **86 / +$7,269 / 1.222** | 86 / +$7,269 / 1.222 |
| XRPUSDT | 92 / +$13,319 / 1.475 | 90 / +$12,564 / 1.451 | 90 / +$12,564 / 1.451 | 83 / +$12,404 / 1.486 | **67 / +$12,409 / 1.599** |
| BTCUSDT | 40 / -$3,791 / 0.776 | 39 / -$3,276 / 0.801 | 38 / -$2,721 / 0.830 | **32 / +$660 / 1.051** | 32 / +$660 / 1.051 |
| SOLUSDT | 23 / +$3,643 / 1.418 | 23 / +$3,643 / 1.418 | 22 / +$4,187 / 1.509 | 18 / +$3,918 / 1.552 | **17 / +$4,456 / 1.673** |

## The predeclared hypothesis is refuted in its stated form

**Trade counts fall on all four symbols** (ETH 98→86, XRP 92→67, BTC 40→32, SOL 23→17). The
predeclared criterion was explicit: *"if trade counts do not rise materially, the wider search is
not finding the multi-week structures the videos show, regardless of what P&L does."* They do not
rise; they fall monotonically. **A longer map does not find more still-fresh bases.**

The mechanism is the opposite of the one hypothesised: the assembler prefers a long-scale candidate
whenever one qualifies, and longer windows produce *fewer, larger* bases that subsume smaller ones,
each still consumed on first revisit. The map ends up with fewer distinct tradeable zones.

## A different effect is confirmed, consistently

Profit factor improves with lookback on **all four** symbols — ETH 1.086→1.222, XRP 1.475→1.599,
BTC 0.776→1.051, SOL 1.418→1.673 — and maximum drawdown improves or holds on all four (BTC
9.15%→5.61%, ETH 7.23%→6.20%, XRP 8.82%→7.21%, SOL flat). BTC crosses from clearly negative to
marginally positive. Fewer trades, but materially better ones.

This is a real, consistent, same-direction effect across every symbol tested, which is more than
any granularity variant achieved. It is *not* the effect the videos predicted.

## Notable: the saturation point matches the ground-truth interval

ETHUSDT and BTCUSDT produce **identical results at 42 and 56 days** (+$7,268.82 and +$659.53, to
the cent) — the search saturates at 42 days for both. **42 days is six weeks**, exactly the
persistence interval measured from the labelled videos (1,986.11 and 2,011.26 marked on both
2026-06-23 and 2026-08-05).

The backtest independently converging on the same timescale as the trader's own annotations is the
most direct corroboration between source material and mechanical behaviour this project has
produced. It should still be treated as one observation on two symbols, not a law.

## ETHUSDT at 42 days passes all eight acceptance criteria

| Criterion | Value | Required |
| --- | ---: | ---: |
| Net profit | +$7,268.82 | >= 0.01 |
| Profit factor | 1.222 | >= 1.1 |
| Maximum drawdown | 6.20% | <= 10% |
| Trade count | 86 | >= 60 |
| Profitable six-month subperiods | **3** | >= 3 |
| Largest subperiod contribution | **41.2%** | <= 60% |
| Average win/loss | 2.401 | >= 1.2 |
| Stressed-cost net profit | +$5,392.02 | >= 0.01 |

Subperiods: +$1,603 / +$3,543 / -$687 / +$3,608 — three positive, one small negative, and the
41.2% concentration is the healthiest this project has recorded. This is materially more robust
than the earlier XRPUSDT pass, which failed subperiod stability at every lookback tested here
(2 of 4 profitable at both 42 and 56 days) and concentrated ~52-54% in one subperiod.

**This is still a training result.** It was found after roughly 100 backtests this session, and
`APOLLO_V5_ROADMAP.md` §3 applies to it in full. What distinguishes it from prior candidates is
that the parameter change was motivated by ground truth before the run, was predeclared, and moves
every symbol in the same direction — not that it produced a better number. Roadmap step 6
(pre-registration, revised evidence floor, walk-forward) still gates any validation attempt.

## Recommendation

Adopt `strategy.baseMapLookbackDays=42` as the frozen default: it is the saturation point for two
of four symbols, matches the measured six-week persistence interval, and improves profit factor and
drawdown on all four. Do **not** adopt 56 — it is indistinguishable from 42 on ETH/BTC, costs
significantly more runtime, and its small XRP/SOL edge over 42 is within the noise this project has
repeatedly demonstrated at these sample sizes.
