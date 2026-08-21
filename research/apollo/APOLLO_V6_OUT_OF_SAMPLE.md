# Apollo V6 — out-of-sample result (2026-08-11)

**The pre-declared stopping rule has triggered. V6 closes.**

Test window **2022-01-01 .. 2023-02-07**, 13 months, ending the day the training window begins. No
configuration tested here had ever seen any of it: the data was not in the database when the
hypotheses were formed, and the aggregate-trade archives could not even be parsed until the
Binance header fix earlier the same day.

The window is the 2022 bear market including the November FTX collapse — conditions absent from the
training window by construction.

## Result — 15 symbols, unselected

| Config | Trades | Net | Per-trade | Win | Blocks | P(profit) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline (V5 frozen) | 301 | +$1,964 | **+$6.53** | 33.6% | 15 | 55.0% |
| absorption +0.03 *(predeclared)* | 28 | +$1,556 | +$55.58 | 32.1% | 12 | 60.4% |
| aligned -0.03 *(post-hoc)* | 127 | **-$4,050** | **-$31.89** | 32.3% | 15 | 34.8% |

## Finding 1 — the aligned delta hypothesis is refuted

In-sample it was the best result the project had produced: +$94.97 per trade, +$24,503 net. Out of
sample it is **-$31.89 per trade, -$4,050 net** — not merely weaker, but the opposite sign, and
worse than the baseline it was supposed to improve.

`APOLLO_V6_WALKFORWARD.md` registered this prediction before the data existed:

> if the edge is regime-dependent rather than structural, aligned -0.03 will *not* be materially
> profitable on 2021-22. If it is profitable there, the regime explanation is wrong and the effect
> is real.

The prediction was correct. The aligned effect was pattern-matching on a favourable regime.

Worth recording precisely because it was persuasive at the time. It had a monotonic dose-response
(-0.01 and -0.03 both improved edge and confidence, in order), a plausible structural mechanism
(V5 zones are breakout-selected, therefore continuation-selected), and it was positive in all four
training half-years. None of that survived contact with unseen data. Dose-response and a good story
are not evidence of generalisation.

## Finding 2 — V5 itself has no demonstrable out-of-sample edge

The baseline returns **+$6.53 per trade with P(profit) 55.0%** — indistinguishable from a coin flip.
Eight of fifteen symbols are profitable, which is exactly what chance produces.

This is the more consequential finding. The aligned hypothesis was one idea among many; the baseline
is the strategy. Combined with `APOLLO_V6_WALKFORWARD.md` Finding 2 — that the entire in-sample
+$14,000 came from a single half-year — the conclusion is that **V5's apparent edge was one
favourable regime, and it does not reproduce.**

## Finding 3 — the only survivor is the predeclared hypothesis, and it is untradeable

Absorption +0.03 is the sole configuration still positive at 15 symbols (+$55.58 per trade). It is
also the one that was declared *before* looking at the delta distribution — the honest one.

It cannot be used. **28 trades in 13 months across 15 symbols** is roughly two trades a month for
the entire universe, and P(profit) is 60.4%. The per-trade figure is built on too few observations
to distinguish from noise, and the frequency is too low to compound.

That the predeclared hypothesis outlived the post-hoc one, despite looking far weaker in-sample, is
the clearest illustration in this project of what post-hoc selection costs.

## The tier pattern — noted, deliberately not acted on

| Universe | baseline per-trade | P(profit) | aligned per-trade |
| --- | ---: | ---: | ---: |
| top 3 | +$92.80 | 76.6% | +$152.78 |
| top 5 | +$123.63 | 89.0% | +$110.88 |
| top 8 | +$24.24 | 62.2% | -$33.95 |
| top 15 | +$6.53 | 55.0% | -$31.89 |

Performance decays monotonically as less liquid symbols enter, independently reproducing the
in-sample rho +0.46 liquidity correlation on unseen data. Top-5 baseline reaches 89.0%.

**This is not a result and must not be traded on.** The tiers were run 3 → 5 → 8 → 15 with the
15-symbol row declared in advance as the one that counts, precisely so that stopping at whichever
tier looked best could not be dressed up as a finding. Choosing "top 5" after seeing this table is
the same selection bias that produced the +91.9% four-symbol figure in V5 and the aligned delta
above.

What it legitimately supports is a **new pre-registered hypothesis** for future work: a liquidity
floor declared in advance, with its cutoff fixed before any run, tested on the still-unopened
validation window. That is a different claim from the one this table appears to offer.

## Stopping rule

`APOLLO_V6_PLAN.md` §9, declared in advance:

> If Phase 1 does not produce a **positive out-of-sample per-trade edge with a block-bootstrap
> P(profit) above 90%**, V6 closes with the finding: a small real effect, not establishable on
> available data.

Highest P(profit) at 15 symbols is 60.4%. Nothing approaches 90%. **V6 closes**, and the finding is
weaker than the rule anticipated: not "a small real effect not establishable", but no demonstrable
effect at all in the frozen configuration.

The validation `[2025-02-07, 2025-08-07)` and final-test `[2025-08-07, 2026-02-07)` windows remain
unopened. Nothing here justifies spending them.

## What was actually gained

- The Binance pre-2022 header fix, without which no history extension was possible at all.
- 17 months of new order-flow and volume-profile data for all 15 symbols, permanently available.
- `-DdatasetStart` / `-DdatasetEnd` with a guard that cannot reach reserved data.
- Two latent 24-month assumptions in `BacktestApplication` removed (subperiod loop, acceptance gate).
- A method that correctly predicted its own negative result in advance.

The last item is the one worth keeping. Every source-derived change in this project worked and every
invented one failed; the aligned delta was the most convincing invented change yet, and it still
failed. The record now contains a case where the discipline caught something that the in-sample
numbers, the dose-response curve, and a plausible mechanism had all endorsed.
