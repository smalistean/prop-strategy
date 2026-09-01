# Phase 2 — candidate ranking: pre-registration

**Author:** Claude · **Frozen:** 2026-09-01 17:47 UTC, before any Phase 2 run was executed.
**Status:** design locked; results appended below the line.
**Parent:** `PROP_CHALLENGE_PLAN.md` §2 (this document pins the parts §2 left as method rather
than parameters). Harness: `ChallengeHarnessApplication` (built and validated in Phase 1).

## Candidates (11) and their pre-declared primary config

Every other config file for the same strategy type is a **sensitivity column, not an entrant**
(§2.2). Where a strategy has several configs, the primary is named here *before* any run.

| # | Strategy type | Primary config | Other configs (sensitivity only) |
|---|---|---|---|
| 1 | donchian-breakout | `donchian-breakout.properties` | — |
| 2 | volatility-compression-breakout | `volatility-compression-breakout.properties` | — |
| 3 | rsi-atr-mean-reversion | `rsi-atr-mean-reversion.properties` | 8 regime/long-only variants |
| 4 | structural-channel | `structural-channel.properties` | — |
| 5 | three-level-range | `three-level-range.properties` | `-5m`, `-reclaim-5m` |
| 6 | intraday-flat-mean-reversion | `intraday-flat-mean-reversion.properties` | — |
| 7 | liquidity-sweep-reversal | `liquidity-sweep-reversal-v1.properties` | — |
| 8 | passive-maker-mean-reversion | `passive-maker-mean-reversion.properties` | — |
| 9 | volume-profile-level | `volume-profile-breakout.properties` | `-channel`, `-false-breakout` |
| 10 | multi-timeframe-flat-long | `multi-timeframe-flat-long.properties` | — |
| 11 | order-flow-exhaustion | `order-flow-exhaustion.properties` | `-high-quality` |

**Excluded, per `PROP_CHALLENGE_PLAN.md` §2.1** (closed families — re-running them under a new
metric would select on data they were already fitted to): 7 Apollo/POC entries, 4 Gerchik entries.
**Also excluded:** `ema-pullback` (measured in Phase 1, failed) and `cross-sectional-long-pullback`
(ranks across symbols; §6 holds one position at a time).

## Run parameters (identical for every candidate)

- **Engine:** `config/backtests/engine.properties` — BTCUSDT 15m, the documented default, used
  unchanged for all candidates so the comparison differs only in strategy (same instrument, same
  windows, same fee/slippage model).
- **Challenge rules:** balance 50,000; maxDailyLoss 2,500; maxTotalLoss 5,000; profitTarget 4,000
  (the real Stage 1 figures).
- **Risk fraction:** **0.25% primary** (§2.4). 0.1% / 0.5% are a reported sweep dimension only,
  run *after* the primary verdict and never used to select a winner.
- **Attempts:** every 14 days, each lasting 180 days, minimum 5 trading days (Phase 1 settings).
- **Control:** 5 random-entry seeds per candidate, geometry (entry rate, stop distance,
  reward:risk) measured off that candidate's *own* trades — the harness does this automatically.

## Cohorts (the multiple-comparison defence)

Two **non-overlapping** windows; a candidate must beat its own matched control in **both**:

- **Cohort A:** 2021-08-01 → 2024-02-01
- **Cohort B:** 2024-02-01 → 2026-08-01

Rationale (§2.3): 11 candidates at a ~5% per-candidate false-positive rate expects ~0.55 spurious
winners — worse than a coin flip that the table's top is noise. Requiring both cohorts
independently takes the per-candidate rate to ≈0.05² = 0.25% and the expected spurious count
across 11 to ≈0.03.

## Pre-declared decision rules

1. **Gate (§2.5):** a candidate advances only if `P(pass) > best-of-5 random controls` in **both**
   cohorts. If none does, **Phase 2 stops and the answer is "no second leg"** — an acceptable and
   expected outcome (Apollo closed, Gerchik closed, XVF too thin, ema-pullback failed).
2. **Leave-best-period-out:** any candidate clearing rule 1 is re-run with its single best quarter
   removed; it must still beat its control. Applied only to survivors.
3. **Zero-trade / near-zero-trade results are reported as NOT EVALUATED, not as failures.** A
   strategy needing 5m or multi-timeframe data may not trade meaningfully on the 15m default
   engine; that is a configuration mismatch, not evidence about the idea. Such candidates are
   listed for a separate, properly-paired run rather than being counted as losers.
4. **`UNRESOLVED` share is reported alongside `P(pass)`** — resolving attempts faster is not
   performing better (Phase 1 note).
5. No candidate is promoted to live money by this phase. The best possible Phase 2 outcome is a
   candidate that earns a **Phase 3 paper run**.

## Honest expectations, stated in advance

This repo's base rate is discouraging by design of its own method: four strategy families measured
against a stated bar, four failures. The plan itself rates "nothing clears" a live possibility.
Phase 2 is worth running because it is **pure compute at zero capital risk** and it resolves the
question either way — a candidate worth paper-trading, or documented confirmation that the weekend
fade is the entire portfolio.

---

## Results (appended after execution)

**Executed 2026-09-01 17:50–18:09 UTC** via `scripts/phase2-run.sh` (22 runs) plus 5 re-runs on the
5m engine for candidates that require it. Raw output in `phase2_out/` (gitignored).

### The table

`P(pass)` = strategy vs best-of-5 matched random controls, per cohort.

| Candidate | Cohort A | Cohort B | trades/attempt | Verdict |
|---|---|---|---|---|
| donchian-breakout | 0.0 / **5.7** | 0.0 / **5.7** | 225–312 | fails both |
| volatility-compression-breakout | 0.0 / 0.0 | 0.0 / 0.0 | 77 | fails both |
| rsi-atr-mean-reversion | 0.0 / 0.0 | 0.0 / 0.0 | 5 | fails both |
| structural-channel | 0.0 / **11.3** | **5.7** / **7.5** | 27–41 | fails both (closest) |
| three-level-range | 0.0 / 0.0 | 0.0 / 0.0 | 67 | fails both |
| intraday-flat-mean-reversion | 0.0 / 0.0 | 0.0 / 0.0 | 242 | fails both |
| passive-maker-mean-reversion | 0.0 / 0.0 | 0.0 / 0.0 | 383 | fails both |
| volume-profile-level | 0.0 / **1.9** | 0.0 / **1.9** | 162 | fails both |
| multi-timeframe-flat-long *(5m)* | 0.0 / 0.0 | 0.0 / 0.0 | **2** | inactive — never resolves |
| order-flow-exhaustion *(5m)* | 0.0 / 0.0 | 0.0 / 0.0 | 94 | **100% FAILED_MAX_LOSS** |
| liquidity-sweep-reversal | — | — | **0** | **NOT EVALUATED** (0 trades on 15m *and* 5m) |

### Gate verdict: **no candidate clears. Phase 2 stops** (rule 1, §2.5)

**Across all 27 runs, exactly one produced a single passing attempt** — structural-channel in
cohort B at 5.7%, and its own random control scored 7.5% in the same window. Every other candidate
passed **zero** attempts in **both** cohorts. Nothing reaches rule 2 (leave-best-period-out), which
applies only to survivors; there are none.

Two honest qualifications:

- **For 14 of 27 runs the comparison was degenerate (0.0% vs 0.0%)** — neither strategy nor control
  ever passed, so those cells carry no discriminating power on their own. The informative fact is
  the aggregate: **not one strategy passed a challenge attempt anywhere**, while controls
  occasionally did (1.9–11.3%).
- **`liquidity-sweep-reversal` is genuinely NOT EVALUATED** (rule 3): zero trades on both the 15m
  and 5m engines — its v1 config (`minimumRewardRisk=3.0` with tight sweep/volume tolerances) is
  too restrictive to fire. That is a configuration fact, not evidence about the idea. It remains
  open for a properly-paired run; nothing here counts against it.

### The structural finding (more useful than the ranking)

The failures split cleanly by trade frequency, and **both halves fail for opposite reasons**:

- **High-frequency candidates** (passive-maker 383, donchian 225–312, intraday 242, volume-profile
  162, order-flow 94 trades/attempt) fail by **blowing the max-loss limit** — donchian 68–74% of
  attempts, order-flow **100%**. Their median worst drawdown is ~5,004 against a 5,000 limit: they
  do not lose gradually, they run straight into the wall. This is the `ema-pullback` result from
  Phase 1 generalised (there, 2,507 of 5,024 total loss was fees).
- **Low-frequency candidates** (multi-timeframe 2, rsi-atr 5, structural-channel 27 trades/attempt)
  fail by **never resolving** — they cannot reach +4,000 within a 180-day window.

So on BTC 15m/5m with this cost model, the middle is empty: trade often enough to reach the target
and costs plus variance hit the drawdown limit first; trade rarely enough to survive and the target
is unreachable in the window. That is a statement about the **instrument and cost structure**, not
about eleven individual ideas — and it is the same identity that killed Gerchik (a ~0.43×ATR stop
against ~13 bp round-trip cost).

### Consequences

1. **Phase 2 closes with "no second leg."** Per §2.5 the honest action is to stop, not to widen the
   search. Five strategy families have now been measured against a stated bar and all five failed:
   Apollo, Gerchik, XVF (real but too thin), ema-pullback, and this set of ten.
2. **The weekend fade is the entire portfolio** for the challenge account, as measured. It trades
   ~2×/month, which the plan already accommodates (unlimited time, minimum trading days satisfied).
3. **What would legitimately reopen this:** a candidate class whose economics are not
   cost-dominated — i.e. lower trade frequency with a larger per-trade edge, which is exactly the
   fade's shape (≈2 trades/month, ~147 bp/event). Note this is a *description of what would have to
   be true*, not a suggestion to go fishing for one; any new candidate needs its own
   pre-registration.
4. **Not done and deliberately not done:** the 0.1%/0.5% risk sweep (§2.4). It is reported only for
   a candidate that already cleared the gate; running it now to see whether some sizing rescues a
   loser would be exactly the selection the two-cohort rule exists to prevent.
