# Prop challenge — plan for discussion

**Author:** Claude
**Date:** 2026-08-25 10:10 UTC, updated 12:38 UTC
**Status:** Phases 0 and 1 are built and tested. Phases 2 and 3 remain proposals.

---

## 1. What "done" means

Pass Stage 1: reach **+4,000 USDT** on the 50,000 account before losing 5,000 total or 2,500 in any
single day, over at least 7 trading days, with unlimited calendar time.

Not "find a good BTC strategy." Not "maximise return." The objective function is a **probability**:

```
maximise  P(equity reaches +4,000 before it reaches -5,000, and no day loses 2,500)
```

This is the central methodological point of the whole plan, and it is not what any existing backtest
in this repo optimises. A strategy returning 30%/year with 15% drawdowns **fails** this challenge. A
strategy returning 12%/year with 4% drawdowns **passes** it. Ranking by return actively selects the
wrong strategies here.

## 2. Why this instead of continuing XVF

| | XVF | Prop challenge |
|---|---:|---:|
| Capital | $4,500 | 50,000 USDT |
| Leverage | 1× | 1:5 |
| Measured result | +0.80% / +7.05% per year | needs +8% **once** |
| Time pressure | ongoing | none |
| Currently used | fully deployed | **777 of 5,000 drawdown** |

The structural difference is edge-per-trade against cost-per-trade. XVF fought a 22.6 bp round trip
with a signal realising ~10 bp — no room existed. BTC moves 2–4% intraday against ~4.5 bp taker cost.
The ratio of available move to transaction cost is roughly 100:1 instead of 0.5:1. The signal does not
need to be better than XVF's; it needs to be pointed somewhere costs do not consume it.

With unlimited time, this is a risk-sizing problem rather than a return problem. At 0.25–0.5% risk per
trade, +8% needs roughly 16–32 net winning units, while −10% needs a catastrophic streak at the same
sizing. **The only thing that reliably fails this challenge is oversizing.**

## 3. What already exists (most of it)

| Component | State |
|---|---|
| `BacktestEngine`, `BacktestDataset`, `PortfolioSimulator` | Built |
| `PropRuleEngine` — daily loss, total drawdown, profit target | Built; corrected in Phase 0, see §4 |
| `PositionSizer` — risk-fraction sizing off entry/stop distance | Built |
| `Account`, `Trade`, `ExecutionModel` | Built |
| `StrategyRegistry` — 24 factories, **13 eligible** (see §5 Phase 2) | Built, tested |
| BTC 1-minute klines | **2,638,394 rows, 2021-08 → 2026-08** |
| BTC 5m / 15m / 1h / 1d | Built out to 2026-08 |

Five years of 1-minute BTC data and a working prop-rule backtester is a strong starting position. This
plan is mostly *correcting and re-pointing* what exists, not building from scratch.

## 4. Finding: `PropRuleEngine` did not implement this challenge's rules — **fixed in Phase 0**

Kept as the record of why the engine changed. The code below is the state before Phase 0.

```java
BigDecimal drawdownPct = account.peakBalance().subtract(equity, MC)
        .divide(account.peakBalance(), MC)
        .multiply(BigDecimal.valueOf(100), MC);
```

That is a **trailing** drawdown measured from peak equity. `PROP_CHALLENGE_RULES.md` states the
opposite:

> "Maximum Loss" is measured from the 50,000 initial balance (**static, not trailing**, based on
> stage-1 usage shown as 72/5,000).

`account.initialBalance()` is read into `initial` but used only for the profit target, never for the
drawdown. The consequence is one-directional: a trailing rule is strictly harsher than a static one, so
the current engine **rejects strategies that would actually pass**. Every historical result produced
with it is pessimistic by an unknown margin.

Two further items to settle before trusting it:

- **Daily-loss reset boundary.** The engine resets `dayStartBalance` at the **UTC** day boundary. The
  rules document does not state the firm's boundary. If it is exchange-local or broker-local, every
  daily-loss evaluation is misaligned by hours, which matters a great deal for an overnight position.
- **Equity vs balance.** The engine checks `equity` (mark-to-market). Whether the firm measures
  realised balance or floating equity decides whether an open losing position can breach the limit
  before it is closed. These are materially different rules.

Both were questions for the firm's terms rather than something to assume. They are answered in §9:
the boundary is UTC, and the limits do include floating equity — which is why the corrected engine
keeps checking mark-to-market equity against the loss limits while reading realised balance for the
profit target.

## 5. Phases

Each phase ends in a decision, not a deliverable.

### Phase 0 — Correct the rule engine — **DONE 2026-08-25 11:50 UTC**

`PropRuleEngine` now implements the three bases separately, as §4 and §9 established they differ:

- Maximum Loss: static, from `initialBalance`, replacing the trailing `peakBalance()` basis.
- Maximum Daily Loss: from the day's running peak equity, resetting at the UTC boundary. This is the
  conservative reading of the unconfirmed §9.3 question — an unverified generous assumption is how a
  real breach gets missed in simulation.
- Profit Target: realised `balance()`, and only evaluated while flat. Floating profit no longer fires it.

`PropRuleEngineTest` (8 cases) covers each distinction directly, plus two checkpoints reproducing the
account's real states — 72.98 used before the discretionary short, 776.99 used after closing it.
`BacktestEngineTest`'s 10 cases still pass unchanged.

### Phase 1 — Challenge-constrained evaluation harness — **DONE 2026-08-25 12:20 UTC**

`ChallengeHarness` replays a strategy from many start dates over identical-length windows and reports
the outcome distribution. `RandomEntryStrategy` is the null control, and
`ChallengeHarnessApplication` wires both to real data.

Three design points worth keeping:

- **Equal windows.** Every attempt gets the same calendar duration rather than running to the end of
  the data, so a 2021 attempt and a 2026 attempt are comparable. Duration is a swept dimension, not a
  fact about the rules, which place no calendar limit.
- **The control is measured, not chosen.** Entry rate, stop distance and reward:risk are read off the
  strategy's own trades. A control that trades at a different rate is a second strategy, and the
  comparison would then be about frequency rather than quality.
- **`TARGET_HIT_BUT_TOO_FEW_TRADING_DAYS` is its own outcome**, so a "pass" driven by one outsized
  trade can never be silently counted as satisfying §9.5.

Supporting change: the ~200 lines of strategy-specific feature wiring inside `BacktestApplication`
moved to `BacktestBarAssembler` so both entry points build bars identically. Verified
behaviour-preserving — an `ema-pullback` backtest produced byte-identical output before and after.

**First result (`ema-pullback`, BTCUSDT 15m, 2021-08 → 2026-08, 118 attempts of 180 days):**

| risk/trade | P(pass) | P(fail) | best of 5 random controls |
|---|---:|---:|---:|
| 0.5% | 2.5% | 97.5% | 2.5% pass / ~46% fail |
| 0.1% | 0.0% | 34.7% | 0.0% pass / 0.8–2.5% fail |

It does not clear the Phase 1 gate. At matched trade rate and geometry, random entry fails far less
often than the strategy does, which means the strategy has negative edge rather than no edge. An
independent full-window backtest confirms it: 695 trades, 36.7% win rate, −7.23/trade expectancy,
and **2,507 of the 5,024 total loss is fees**. At roughly 550 trades per 180-day window, cost alone
decides the outcome.

This is one candidate of thirteen (see Phase 2), and it is the config's default rather than a
promising one — it is reported here as evidence the instrument discriminates, not as a verdict on
the registry.

**Gate, restated for Phase 2:** does *any* strategy in the registry show `P(pass)` materially above
its own matched random control? Without that control a positive number is uninterpretable, which is
the same reason the ranked-vs-random test mattered for XVF.

Two things the first run already establishes about how to read Phase 2's output:

- **`UNRESOLVED` share is informative, not missing data.** Random entry at 0.1% risk left 99% of
  attempts unresolved; `ema-pullback` left 65%. A strategy that resolves more attempts than its
  control is not necessarily better — it may just be reaching a verdict faster, and the verdict is
  usually failure.
- **The window edge effect is negligible in practice.** `barsBeforeFirstTrade` ran 5–72 bars out of
  17,280, so truncated strategy history at the start of a window delays almost nothing.

### Phase 2 — Rank the eligible candidates honestly — **DONE 2026-09-01 18:09 UTC: GATE NOT CLEARED**

Executed per `PHASE2_PREREGISTRATION.md` (design frozen before running). Ten candidates evaluated
over two non-overlapping cohorts against their own matched random controls; `liquidity-sweep-reversal`
NOT EVALUATED (zero trades on 15m and 5m — config too restrictive, not evidence about the idea).
**No candidate beat its control in both cohorts; across all 27 runs exactly one passing attempt was
produced anywhere** (structural-channel cohort B, 5.7% vs its control's 7.5%). Per §2.5 the answer is
stop. Structural finding: high-frequency candidates (94–383 trades/attempt) breach max-loss (order-flow
100%, donchian 68–74%); low-frequency ones (2–27) never reach the target in 180 days. On BTC 15m/5m at
this cost model the middle is empty — the same cost identity that closed Gerchik.


#### 2.1 The candidate set is 13, not 24

`StrategyRegistry.defaults()` holds 24 factories and 24 distinct implementation classes, so nothing
here is literally one class wearing different parameters. But a registry entry is not a hypothesis,
and eleven of the 24 belong to two families this repo has already tested and closed:

| Excluded family | Entries | Verdict already recorded |
|---|---:|---|
| Apollo / POC lineage — `base-poc-retest`, `variable-base-poc`, `v4-base-poc-continuation`, `v5-base-poc-continuation`, `v5-liquidity-limit`, `higher-timeframe-liquidity-sweep`, `ordered-liquidity-sequence-v3` | 7 | `APOLLO_V6_OUT_OF_SAMPLE.md`: *"The pre-declared stopping rule has triggered. V6 closes."* Baseline **+$6.53/trade, P(profit) 55.0% — "indistinguishable from a coin flip"**, with +$14,000 traced to a single half-year. `APOLLO_V5_PREREGISTRATION.md`: *"candidate rejected at Gate 1."* |
| Gerchik levels — `level`, `false-breakout`, `bounce`, `breakout` | 4 | `GERCHIK_V2_PREREGISTRATION.md`: *"these three mechanisations carry no edge on crypto perpetuals."* Cause is structural, not parametric — a ~0.43×ATR stop by construction against ~13 bp round-trip cost. *"Net P(profit) is 0.0% for every model in every universe."* |

Re-running these would not be an independent test. The v3→v4→v5→v6 lineage exists *because* each
version was measured on this same BTC history and found wanting; the successors were shaped by those
results. Feeding them back through a new metric and keeping whichever scores best is selecting on
data they were already fitted to, and it would manufacture a winner whether or not one exists.

They are excluded as **closed**, not as **bad**. If a reason ever appears to reopen one — a
different instrument, a different timeframe, a corrected cost model — it reopens on its own
pre-registration, not by quietly rejoining a ranking run.

That leaves 13 candidates, of which `ema-pullback` is already measured and failed:

| # | Candidate | Status | Data it needs |
|---|---|---|---|
| 1 | `ema-pullback` | **measured — failed the gate** | 15m |
| 2 | `donchian-breakout` | untested | 15m |
| 3 | `volatility-compression-breakout` | untested | 15m |
| 4 | `rsi-atr-mean-reversion` | untested | 15m |
| 5 | `structural-channel` | untested | 15m |
| 6 | `three-level-range` | untested | 15m / 5m |
| 7 | `intraday-flat-mean-reversion` | untested | 15m |
| 8 | `liquidity-sweep-reversal` | untested | 15m |
| 9 | `passive-maker-mean-reversion` | untested | 15m + 1m trade-through |
| 10 | `volume-profile-level` | untested — no research doc mentions it at all | 15m + profile bins |
| 11 | `multi-timeframe-flat-long` | untested | 5m + 15m + 1h |
| 12 | `order-flow-exhaustion` | untested | 5m + order-flow features |
| 13 | `cross-sectional-long-pullback` | untested, **and probably out of scope** — it ranks across symbols, and §6 fixes one position at a time | multi-symbol |

So the realistic run is **11 untested single-symbol candidates**, with #13 either dropped or
explicitly rescoped before it is run.

#### 2.2 Config variants are a swept dimension, not entrants

The config directory duplicates the same idea one level further down:

| Strategy type | Config files |
|---|---:|
| `rsi-atr-mean-reversion` | 9 (`long-only`, `regime-bull-long`, `regime-bear-short`, `regime-flat-long`, `regime-flat-long-exit`, …) |
| `apollo-ordered-liquidity-sequence-v3` | 16 (excluded family, listed to show the scale) |
| `three-level-range` | 3 |
| `volume-profile-level` | 3 |

Nine variants of one mean-reversion idea are nine draws on one hypothesis. Running all nine and
keeping the best is the exact failure mode the XVF calibration spent two days avoiding.

**Rule:** one pre-declared primary config per candidate. Every other variant is reported in the same
table as a sensitivity column, and a candidate is judged on its primary config only. A variant
beating the primary is a finding to pre-register and test separately, never a substitution made
after seeing the scores.

#### 2.3 Multiple-comparison budget, stated before running

Eleven candidates against their own random controls, at a per-candidate false-positive rate around
5%, expects **~0.55 spurious winners** — better than even odds that the top of the table is noise if
a single pooled comparison decides it.

This is what the two-cohort rule is actually for. Requiring a candidate to beat its control on two
non-overlapping periods *independently* takes the per-candidate rate to roughly 0.05² = 0.25%, and
the expected spurious count across eleven to **~0.03**. That is the difference between a ranking
that means something and one that does not, so the cohort split is a correctness requirement here,
not extra caution.

#### 2.4 Method

- **Two non-overlapping time cohorts** — 2021-08→2024-02 and 2024-02→2026-08. A candidate must clear
  its own matched control on **both**, not on the pooled average.
- **Leave-best-period-out** — remove the single best-performing quarter and re-check. Apollo's
  recorded failure was exactly this shape: the whole apparent edge sat in one half-year.
- **Risk-fraction sweep** (0.1% / 0.25% / 0.5%) as a reported dimension, not a fitted parameter.
  **0.25% is the pre-declared primary**; the others are reported only. §6 caps the live figure at
  0.5%, and Phase 1 showed a negative-edge strategy fails 97.5% of attempts there, so the sweep is
  measuring how much sizing masks or exposes a real difference, not searching for a flattering one.
- **Realistic fills** — taker cost, and slippage scaled to the 1-minute bar's range rather than a flat
  constant. Phase 1 makes the reason concrete: fees were 2,507 of `ema-pullback`'s 5,024 total loss,
  so cost modelling decides these outcomes rather than colouring them.
- **Report `UNRESOLVED` share alongside `P(pass)`** — see the note above; resolving faster is not
  performing better.

#### 2.5 Gate

If no candidate beats its own matched control on both cohorts with leave-best-period-out intact,
**stop**. Given §2.1 and §2.3, that is a live possibility rather than a formality: two of this
repo's three prior strategy families already reached exactly that verdict, and the third failed
today. The honest answer arrives cheaply and should be taken when it arrives.

### Phase 3 — Forward paper run, then live at reduced size

Whatever survives runs paper for a defined period against live data before it touches the account, then
live at **half** the risk fraction the simulation recommends. The first live objective is not the
profit target — it is the minimum 7 trading days at a size that cannot breach anything.

## 6. Risk controls, decided now rather than under pressure

- **Hard per-trade risk cap: 0.5%** (250 USDT). At that size, breaching the 5,000 total limit takes 20
  consecutive full-stop losses.
- **Daily stop at 1,000 USDT** (40% of the firm's 2,500). Stop trading for the day on touch. The firm's
  limit should never be approached, let alone tested.
- **No averaging down. No moving stops.** Both are the standard mechanism by which small losses become
  the total limit.
- **One position at a time** initially — this is a single-instrument challenge and correlated
  simultaneous entries are just leverage under another name.
- The 1:5 cap is a ceiling, not a target. Sizing comes from the risk fraction and the stop distance,
  and leverage is whatever falls out of that.

## 7. What would make me say stop

Stated in advance so it is not renegotiated later:

1. No candidate beats its own matched random control on both time cohorts in Phase 2 with
   leave-best-period-out intact.
2. The best `P(pass)` is not clearly above the null baseline from Phase 1.
3. Live drawdown reaches 1,500 USDT (30% of the allowance) — stop, re-measure, do not "trade back".
4. The firm's answers in §9 turn out materially harsher than modelled (e.g. floating-equity drawdown on
   a trailing basis), invalidating Phase 2's results.

## 8. Honest assessment of the odds

Most prop challenges fail, and mostly through oversizing rather than bad signals. Passing yields a
funded 50,000 account — real, but not transformative, and Stage 2 and Stage 3 follow.

What makes this better than the base rate, if anything does: unlimited time removes the pressure that
causes oversizing, the account is essentially untouched so the full allowance is available, and the
measurement discipline needed to avoid fooling yourself already exists in this repo and was exercised
hard over the last two days.

What does not improve with any of that: whether a tradeable edge exists in the eligible candidates at
all. The base rate inside this repo is discouraging — of the four strategy families measured against
a stated bar, Apollo closed on its own stopping rule, Gerchik was traced to a structural cost
failure, XVF's edge was real but too thin to survive fees, and `ema-pullback` failed today.
Phase 2 answers that, and it can very reasonably answer "no".

## 9. Open questions — resolved 2026-08-25 11:40 UTC

1. Maximum Loss / Maximum Daily Loss include **floating equity**, not realised balance only — confirmed
   from the firm's own dashboard tooltips.
2. Daily reset boundary: **UTC**, ~99% confidence per the user, not yet checked against the firm's
   written terms but treated as settled for modelling purposes.
3. Daily loss basis (day-start balance vs day's peak equity) not confirmed by the firm either way — the
   engine should model the **more conservative** of the two (from peak equity within the day), since an
   unverified generous assumption is exactly how a real breach gets missed in simulation.
4. No restriction on holding through funding or weekends; execution must be **manual**, no bots.
5. "Minimum 7 trading days (needs 5)": the **4,000 USDT profit target must not come from a single
   trade** — it has to be spread across at least 5 distinct trading days. This requirement is already
   satisfied (5+ trading days have occurred on the account); it does not gate anything going forward,
   but Phase 1's harness should still track trade-day count per simulated run so a "pass" concentrated
   in one or two days is visible as not meeting this rule.
6. Manual only (no bots) — same fact as #4, listed here originally as a separate question.
7. Not BTC-only — 127-symbol list in `PROP_CHALLENGE_RULES.md`.
8. Preserving the account is the priority (the $500 re-attempt cost is real); failing is an acceptable
   outcome of a careful attempt, not the goal.

All eight are now settled enough to build against. Nothing here blocks Phase 0.

## 10. What I would not do

- Not options. `deribit_option_quote` has 1.5M rows but only **385 snapshots across seven years** —
  roughly weekly. Usable for slow regime context, nowhere near dense enough to trade.
- Not XVF-style funding capture. Wrong instrument, wrong account structure, and the economics were
  measured and found thin.
- Not a new strategy from scratch before Phase 2. Thirteen are eligible and only one has been
  evaluated against the actual objective. Building a fourteenth before measuring those is how the
  last two days happened.
- Not a re-run of Apollo or Gerchik under a new metric. Both were closed on pre-declared stopping
  rules, and Gerchik's failure was traced to a structural cost identity rather than to parameters.
  Reopening either requires its own pre-registration and a stated reason, not inclusion in a ranking
  sweep.
