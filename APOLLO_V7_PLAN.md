# Apollo V7 — what was missed, and what to do next (2026-08-11)

Written after V6 closed. Every claim about the source below was checked against
`APOLLO_COURSE_SOURCE_NOTES.md` and the corresponding code on the day of writing.

## 1. Where the strategy actually stands

- Out-of-sample, 15 symbols, 2022-01..2023-02: **+$6.53 per trade, P(profit) 55.0%** — no
  demonstrable edge (`APOLLO_V6_OUT_OF_SAMPLE.md`).
- In-sample profit is one half-year out of four (`APOLLO_V6_WALKFORWARD.md`).
- Effective sample is ~15-26 correlated blocks, not 300-600 trades. This bounds what *any* result
  here can establish, and it is the reason two convincing hypotheses have already failed.

## 2. Three verified gaps between code and source

These are not ideas. Each is a place where the course states a rule and the code does something else.

### 2a. The fixed-profile candle selection rule is not implemented

The source is unusually exact here (pp. 25, 33):

> determine the greatest body high and body low of the visually selected horizontal candle cluster,
> then include only candles **fully inside** those body bounds. The **entrance and exit candles are
> excluded**.

`VariableBaseDetectorV5.exactBounds()` computes the body high/low correctly. But
`VolumeProfileFeatureAssemblerV5` then stretches the profile over
`subMap(baseStart, true, currentBar, false)` — **every** bucket from base start through the breakout
candle. So:

- candles that poke outside the body bounds are included, when the source excludes them;
- the entrance candle is included, when the source excludes it;
- the **breakout candle itself** is included — the exit candle, which the source names explicitly.

This is the highest-value gap because it changes **the POC itself**, the single input every entry,
target, and zone decision is derived from. The excluded candles are systematically the volatile ones
at the base edges and the breakout impulse, which carry disproportionate volume — so their inclusion
does not merely add noise, it biases the POC toward the edges and toward the breakout bar.

Note this is *constructive*, not another gate: it changes where the zone is, not whether to trade.

### 2b. The two retest types are collapsed into one

The source describes two distinct base-break retests (pp. 40-41):

- a **shallow edge retest**, more likely when liquidity sits near that edge;
- a **deep retest** that reaches the base POC, *"requiring attention to untraded internal liquidity
  and a wider structural stop"*.

`ApolloV5LiquidityLimitStrategy` has a single `selectedBaseFirstRevisit` flag and enters on first
revisit regardless of depth, with the same stop in both cases. The source says these are different
setups needing different invalidation.

### 2c. The stop is geometric, not structural

`ApolloV5LiquidityLimitStrategy:104` places the stop at
`(baseHigh - baseLow) x stopBaseHeightFraction`, i.e. 25% of base height — a pure geometry number
with no reference to where trading actually happened. The source says invalidation should be
structural, and that *"a volume level may be the more meaningful invalidation point"* (pp. 36, 52-54),
with the deep retest explicitly needing a **wider** stop (2b).

## 3. What the measured data independently says

Two diagnostics from 2026-08-11, both on the frozen config:

| Observation | Value |
| --- | --- |
| Trades reaching take profit | **5.3%** (16 of 301, out-of-sample) |
| Profit source | the arbitrary 96-bar timeout, not the course's target logic |
| Stopped trades that were in profit first | **95%** out-of-sample, **91%** training (top 5) |
| Median favourable excursion of those | 0.37R out-of-sample, 0.57R training |
| Stopped trades reaching >= 1R first | 20% out-of-sample, 27% training |

A fifth to a quarter of losing trades were up by more than they eventually lost.

**Correction (measured after this section was first written):** median stop distance is 1.57% of
entry price out-of-sample and 1.01% in training. These are not razor-tight stops sitting inside
noise, and an earlier characterisation to that effect was wrong. The accurate statement is that
trades travel only ~0.4-0.6R in favour before giving back a full R.

This does not weaken §2c, but it changes its expected direction: today's stop sits below the *entire*
base plus 25% of its height, so a volume-structural stop will most likely be **tighter**, not wider.
Structural placement is the objective; whether it tightens or widens is an empirical outcome and is
deliberately not treated as a design target.

## 4. Why these have a better prior than what failed today

Everything tested on 2026-08-11 and refuted was a **filter**: aligned delta, absorption, acceptance
body quality. Each added a gate on top of an unchanged signal, and each failed the same way - it
removed trades without improving the expectancy of the ones it kept.

The gaps in §2 are **constructive**. They change how the zone and the invalidation are *computed*,
not whether a given setup passes another test. A filter can only redistribute an existing edge; a
better POC or a better stop changes the trade itself.

This is a reason for a modestly better prior, not a prediction of success. The acceptance filter was
also source-mandated and still did nothing.

## 5. Ranked next steps

**1. Implement the profile candle-selection rule (2a).** Highest value, exact source specification,
changes the core input. Predicted effects to register in advance: POC moves; trade count changes in
either direction (this is not a filter); per-trade edge is the test.

**2. Volume-based invalidation plus the shallow/deep retest split (2b + 2c together).** They are one
change: classify the retest by depth, and place the stop beyond the relevant volume structure rather
than at a fixed fraction of base height. Supported by both source and the MFE measurement.

**3. Break-even stop at 1.0R.** Currently disabled by a comment in `engine.properties` that reads
*"Disabled because 0.75R, 1.0R, and 1.5R all reduced BTC RSI/ATR training profit"* — measured on a
**different strategy** and never tested on Apollo. Cheap to test, but it is a band-aid on 2c: if
invalidation moves to structural levels, this question changes shape. Do it after, not before.

**Explicitly deprioritised:** Family C hook-trigger (0 of 20 labelled examples), any further
filters, and re-tuning existing parameters.

## 6. Discipline that applies to all of the above

- Pre-register thresholds **before implementation**, as in `APOLLO_V7_ACCEPTANCE_PREREGISTRATION.md`.
- Judge on the declared universe, block bootstrap, training first then **one** confirmatory run.
- No sweeps. No threshold changes after seeing a result.
- Validation `[2025-02-07, 2025-08-07)` and final test `[2025-08-07, 2026-02-07)` stay closed.

If §5.1 and §5.2 both fail out-of-sample, the honest conclusion is that this mechanisation of the
course does not carry an edge that available data can establish, and the work should stop rather
than continue generating hypotheses.
