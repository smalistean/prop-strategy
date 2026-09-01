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
