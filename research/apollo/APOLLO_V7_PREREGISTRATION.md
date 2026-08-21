# Apollo V7 — pre-registration for the three structural fixes (2026-08-11)

Written **before implementation and before any backtest**. Values fixed here may not be changed to
improve a result. Rationale in `APOLLO_V7_PLAN.md` §2.

## Universe and evidence standard

- **Declared trading universe: the 5 largest symbols by traded volume** — BTC, ETH, SOL, XRP, ADA.
  Chosen by the user as the operating scope. The tier table in `APOLLO_V6_OUT_OF_SAMPLE.md` §"tier
  pattern" is **not** evidence for this choice and its +$123.63/trade and 89% figures are not cited
  as support; they were selected after the fact.
- The **15-symbol unselected universe is reported alongside**, every time, so the top-5 figure can
  never be quoted without the unselected one next to it.
- Block bootstrap, 4-week blocks. Per-trade resampling overstates confidence here.
- **Training window first, then a single confirmatory run** on 2022-01-01..2023-02-07.
- Each fix is tested **individually against baseline**. A combined configuration is reported for
  completeness but is **not** used for selection - picking the best of four is the multiple-
  comparisons failure that produced the aligned-delta result.

## Fix 1 — fixed-profile candle selection (`profileBodyBoundedSelection`, default 0)

Source (pp. 25, 33): include only candles fully inside the cluster's body bounds; exclude the
entrance and exit candles.

When set to 1, the profile is built from candle *i* where:

- `baseStart < i < baseEnd` — excludes the entrance candle at `baseStart`; the exit/breakout candle
  at `baseEnd` is excluded by being outside the range (it is currently included, which is the bug);
- `high(i) <= base.high()` and `low(i) >= base.low()` — fully inside the body bounds, which
  `VariableBaseDetectorV5.exactBounds()` already computes from open/close.

**Minimum qualifying candles: 3.** Below this a profile is not meaningful and the candidate is
rejected rather than silently falling back to the old window. Declared, not tuned.

**Predictions.** POC moves. Trade count may move in **either** direction — this is not a filter, so
neither direction is evidence of a bug, unlike the acceptance test. Test statistic is per-trade edge.

## Fix 2 — structural invalidation and the retest split (`stopMode`, default 0)

Source: invalidation should be structural, *"a volume level may be the more meaningful invalidation
point"* (pp. 36, 52-54); the deep retest needs *"a wider structural stop"* (pp. 40-41).

Retest depth, classified at entry:

- **deep** — the revisit traded into the POC zone `[zoneLow, zoneHigh]`;
- **shallow** — it reached the base boundary but not the zone.

With `stopMode=1`, for a LONG (mirrored for SHORT):

| Retest | Stop |
| --- | --- |
| shallow | `zoneLow - boundaryPenetrationAtr x ATR` |
| deep | `baseLow - boundaryPenetrationAtr x ATR` (wider, per source) |

`boundaryPenetrationAtr` is the existing declared constant (0.10), reused deliberately so no new
number is invented for this test.

**Predictions.** Risk per trade **falls** — today's stop sits below the entire base plus 25% of its
height, so structural placement is tighter. Stop-out rate therefore **rises**. Neither is the test.
The test is per-trade edge and net.

## Fix 3 — break-even at 1.0R (`execution.breakEvenEnabled`)

No code change; the mechanism exists. Currently disabled by a comment in `engine.properties`
attributing the decision to *"BTC RSI/ATR training profit"* — **a different strategy**, never
Apollo. Trigger stays at the already-configured 1.0R; it is not swept.

**Predictions.** Full-R losses fall; some winners are cut to scratch. Net direction unknown - that is
the test.

## Stopping rule

A fix is adopted only if it improves per-trade edge on the declared universe in **both** windows.
Improvement in training alone is a failure, not partial support - `APOLLO_V6_OUT_OF_SAMPLE.md`
records what happened the last time an in-sample gain was treated as promising.

If all three fail, `APOLLO_V7_PLAN.md` §6 applies: stop generating hypotheses rather than continue.

---

# RESULT (2026-08-11): ALL THREE REFUTED IN TRAINING

None reached the out-of-sample window. The stopping rule requires improvement in both windows, so a
training failure ends the test; running the confirmation anyway could only serve as a search for a
rescuing result.

| Config | Trades | Net | Per-trade | vs base | P(profit) |
| --- | ---: | ---: | ---: | ---: | ---: |
| **15 symbols, unselected** | | | | | |
| baseline (frozen V5) | 590 | +$14,000 | +$23.73 | — | 68.3% |
| fix1 body-bounded profile | 683 | -$6,424 | -$9.41 | -33.13 | 40.0% |
| fix2 structural stop | 608 | +$7,520 | +$12.37 | -11.36 | 59.1% |
| fix3 break-even 1R | 583 | +$3,188 | +$5.47 | -18.26 | 55.0% |
| all three combined | 725 | -$41,491 | -$57.23 | -80.96 | 2.8% |
| **top 5, declared universe** | | | | | |
| baseline | 207 | +$22,165 | +$107.08 | — | 88.2% |
| fix1 | 254 | +$24,068 | +$94.76 | -12.32 | 85.5% |
| fix2 | 218 | +$17,991 | +$82.53 | -24.55 | 83.2% |
| fix3 | 208 | +$11,790 | +$56.68 | -50.40 | 74.7% |
| all three | 270 | +$1,273 | +$4.72 | -102.36 | 51.0% |

Registered predictions, checked:

- **fix1** predicted trade count could move either way. It rose, 590 -> 683, confirming the change is
  constructive rather than a filter. Per-trade edge still fell on both universes.
- **fix2** predicted risk per trade falls and stop-out rate rises. Stop-out rate rose 63% -> 67%.
  Per-trade edge fell.
- **fix3** predicted fewer full-R losses and some winners cut to scratch. Stop-out rate fell sharply,
  63% -> 46%, so the mechanism worked exactly as described - and net still fell. The winners it cut
  were worth more than the losers it saved. The `engine.properties` comment that disabled it for a
  different strategy turns out to have been right for Apollo too, for reasons it never stated.

The combined configuration is far worse than any single fix. They compound destructively.

## An invalid first run, corrected

The initial fix1 run reported 590 -> 55 trades at -$143.59 per trade. That was an integration flaw,
not the source rule: `volumeRatio` divides the profile window's total quote by the *preceding* window
of the same bar count, so restricting the numerator's candle set while leaving the denominator whole
deflated every ratio and failed the `minimumBaseVolumeRatio=1.20` gate for reasons unrelated to the
hypothesis. The ratio now always uses the full base window; the body-bounded window governs only
profile geometry. Baseline output was re-verified identical trade-for-trade after the change.

Recorded because the first number was reported before the check, and because a 91% trade collapse is
exactly the kind of result that should trigger an implementation audit rather than a conclusion.

## The heuristic that motivated this work is dead

These three were prioritised on the argument that source-derived changes had a perfect record while
invented ones were 0-for-5. Three source-derived changes have now failed: the third-touch rule, the
acceptance body-quality rule, and the fixed-profile selection rule - the last being the most exact
specification in the entire course text, implemented faithfully and verified constructive.

"Derive it from the source" is no longer a usable prior for what will work here.

## Why no further hypotheses follow

The baseline has **no demonstrated out-of-sample edge**: +$6.53 per trade at P(profit) 55.0%
(`APOLLO_V6_OUT_OF_SAMPLE.md`). Comparing variants against it in-sample largely measures which one
fits a single favourable half-year better, which is why the top-5 column looks healthy in every row
and the unselected column does not.

`APOLLO_V7_PLAN.md` §6 applies. The honest conclusion is that this mechanisation of the course does
not carry an edge that the available data can establish. The remaining constraint is not ideas but
evidence: ~15-26 independent blocks cannot resolve a per-trade edge of this size, and no further
filter, stop rule, or profile refinement changes that arithmetic.

---

# ADDENDUM: source-faithful Family B limit entry (registered before running)

Found by re-reading the Family B text after the seven failures above. The strategy is named
`apollo-v5-liquidity-limit` and **never placed a limit order at the liquidity**.

Source (pp. 24, 26): *"For a high-timeframe liquidity area, place a limit order slightly before the
principal volume and hide the stop behind the entire liquidity zone... an additional buffer of one
quarter of the liquidity-zone height."*

V5 instead waits for a close beyond the zone - a reclaim entry taken *after* price has traded
through. Every other change tested today altered something around an unchanged entry. This changes
the entry itself.

**The entry and stop are a matched pair.** The quarter-of-zone-height buffer is small precisely
because the entry sits just outside the zone. Testing that stop against a reclaim entry (fix4 above)
tested half a rule with the wrong other half, which is recorded as a defect of that test.

## Configuration under test

| Parameter | Value | Source |
| --- | --- | --- |
| `entryMode` | 1 | limit rests slightly before the principal volume |
| entry price | `zoneNearEdge ± entranceDistanceAtr x ATR` | the existing declared 0.25 "a little before POC" distance; no new number invented |
| `stopMode` | 2 | behind the entire zone + 0.25 x zone height |
| `limitOrderLifetimeBars` | 96 | one day on 15m, matching `maximumHoldingBars`; declared, not searched |

The order rests only when genuinely non-marketable (buy strictly below close, sell strictly above),
so no fill can occur at a price the market never offered. It is placed **before** the first revisit,
since a limit must be working before price returns; after the revisit the zone is consumed.

## Predictions

1. **Fill rate below 100%.** Limits that never get touched expire. If every order fills, the
   non-marketable guard is broken.
2. **Better entry price on filled trades**, hence higher reward:risk on the same target and stop.
3. **Trade count falls** - a reclaim close is easier to achieve than a touch of a specific price.
4. Test statistic is per-trade edge on the declared universe, training first.

## Stopping rule

Unchanged: improvement in **both** windows or it is refuted. Given 0-for-7 today, an in-sample gain
here should be treated as weak evidence until the out-of-sample run confirms it.

## ADDENDUM RESULT: REFUTED

| Config | Trades | Net | Per-trade | stop% | TP% | P(profit) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| **15 symbols, unselected** | | | | | | |
| baseline (frozen V5 reclaim) | 590 | +$14,000 | +$23.73 | 63% | 5.1% | 68.3% |
| source Family B limit entry | 868 | -$7,304 | **-$8.41** | 62% | 3.9% | 41.9% |
| **top 5** | | | | | | |
| baseline | 207 | +$22,165 | +$107.08 | 60% | 8.7% | 88.2% |
| source Family B limit entry | 215 | -$13,523 | **-$62.90** | 63% | 3.7% | 16.2% |

Registered predictions:

1. **Fill rate below 100% — confirmed after a correction.** The first run reported `filled=20/20,
   expired=0` on every symbol, which was the declared bug signal and was a real defect: orders were
   counted only at fill, and `placeResting` rebuilt the order whenever the zone drifted, resetting
   its lifetime so it could never expire - a trailing order, not a resting one. Fixed by leaving a
   working order alone and counting it at placement. Corrected fills: BTC 20/20, ETH 36/37, SOL 34/39.
2. Better entry price on filled trades - holds by construction; the entry sits outside the zone.
3. **Trade count falls — WRONG, and instructively so.** It rose, 590 -> 868. The reclaim close was
   doing substantial filtering work that the source's limit entry does not do: a limit fills on a
   touch, and price that reaches a zone frequently continues through it. BTC's win rate fell to 10%.

Take-profit rate fell further, 5.1% -> 3.9%, so the better entry price did not bring targets into
reach either.

## The pattern across all eight tests

| # | Change | Direction relative to source | Result |
| --- | --- | --- | --- |
| 1 | aligned delta | invented | refuted out-of-sample |
| 2 | absorption delta | invented | untradeable frequency |
| 3 | acceptance body quality | **toward source** | no effect |
| 4 | body-bounded profile | **toward source** | worse |
| 5 | structural stop | **toward source** | worse |
| 6 | break-even 1R | invented | worse |
| 7 | source-exact zone stop | **toward source** | worse |
| 8 | Family B limit entry | **toward source** | much worse |

**Every single move toward the source made results worse.** V5 outperforms the specification it
claims to implement, and it does so through its deviations: a reclaim close instead of a limit, a
base-height stop instead of a zone-height one.

The straightforward reading is that V5's in-sample performance is an artefact of accumulated fitting
to this window, not of capturing the course's edge. Its deviations are not bugs that cost
performance - they are the fitted parts, and the source-faithful version is what the strategy looks
like without them: roughly break-even to negative.

That is consistent with `APOLLO_V6_OUT_OF_SAMPLE.md`, where the fitted version showed +$6.53 per
trade at P(profit) 55.0% on unseen data.

## What this does not establish

That the course method does not work for its author. A discretionary trader selects which levels to
mark, which symbols and regimes to avoid, and when a chart is unclear. None of that is in these 15
symbols traded mechanically and continuously. What is established is narrower and firmer: **this
mechanisation does not carry a measurable edge, and moving it closer to the written specification
makes it worse rather than better.**

---

# ADDENDUM: regime x delta interaction — REFUTED (2026-08-11)

Hypothesis: the zone aggressor delta predicts continuation when it runs with prevailing flow and is
absorbed when it runs against it, so its sign should depend on market regime. Motivated by the delta
being +$94.97/trade in a bull-run training window and -$31.89 out-of-sample in the 2022 bear.

Regime imported from Binance `metrics` daily archives (7.4M rows, 15 symbols, 5-minute, 2021-12
onward), defined before implementation as 30-day open-interest change against 30-day price change:
rising OI + rising price = new longs, rising OI + falling price = new shorts, falling OI = unwind.
The regime series is coherent rather than noisy - multi-month episodes tracking the 2022 bear,
post-FTX deleveraging, the 2024 run and the late-2025 reversal.

The strategy was **unchanged** in every run - the frozen baseline, ignoring both new fields. Each
accepted decision dumped its delta and regime; trades were joined offline. Nothing was fitted.

Gap = aligned per-trade minus opposed per-trade, top 5 symbols:

| Regime | prehistory | training | postfinal | predicted |
| --- | ---: | ---: | ---: | --- |
| new longs (+1) | -316.37 | -336.29 | **+242.97** | positive |
| new shorts (-1) | **+439.55** | -20.18 | -660.93 | negative |

Neither leg holds its sign in all three windows, which was the declared bar. The 2022 bear window
that motivated the hypothesis is itself where the new-shorts leg goes the wrong way.

Sample sizes make even the agreeing cells weak: postfinal cells hold 3-9 trades, and its +$1,069
per-trade unwind figure rests on three of them.

**Consistent across windows:** opposed (absorption) beats aligned in 5 of 9 cells. This agrees with
`APOLLO_V6_OUT_OF_SAMPLE.md`, where absorption survived out-of-sample at +$55.58/trade while aligned
inverted to -$31.89. The predeclared hypothesis keeps outliving the post-hoc one - but absorption
remains untradeable at roughly two trades per month across the whole universe.

Ninth tested hypothesis, ninth refutation. Reserved validation and final-test windows remain closed.
