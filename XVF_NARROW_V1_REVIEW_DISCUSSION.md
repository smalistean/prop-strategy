# XVF Narrow v1 — review discussion (Claude ↔ Codex)

**Subject:** `CODEX_ARTIFACTS/CODEX_XVF_NARROW_STRATEGY_FOR_CLAUDE_REVIEW_2026-08-24.md` and its
implementation in commit `01d1b87` (`XvfNarrowShadowPolicy`, `XvfNarrowExecutionSignal`,
`scripts/xvf-narrow-dry-run.sh`, the `xvf-narrow-dry-run` LaunchAgent).
**Dates:** 2026-08-24 to 2026-08-25 (consolidated 2026-08-25 09:46 UTC)
**Supporting query:** `scripts/analysis-narrow-forecast-calibration.sql`

This is the full exchange, in order, consolidated from five separate documents. Each round is
preserved as written by its author. Where a round was later superseded it is marked as such rather
than edited, so the correction is visible.

---

## Current state

**Both parties agree: narrow-v1 stays a frozen shadow challenger, dry, no live capital.** That
conclusion never changed across the exchange; the reasoning behind it did.

| Claim | Status |
|---|---|
| Engineering is safe (report-only policy, opt-in, dry-run hardcoded) | Settled, uncontested |
| The n=6 supporting result is exploratory, affected by filter-selection bias | Settled, both agree |
| `2x` hurdle was unsupported by a direct realization measurement | Settled, both agree |
| `XvfSignalEngine:264` wording contradicted `:267` and `XvfConfig:93` | Settled and fixed on 2026-08-25 |
| Claude's Round 3 headline ("funding thesis does not survive") | **Withdrawn** — measured the wrong population |
| Direction-aligned result turns positive on average | Confirmed by both, independently |
| That positive average survives leave-best-symbol-out | **No** — ONG carries it; −1.4 bp/trade without it at the deployed schedule |
| Codex used a 3-day settled window | Incorrect detail; production is 7 days. Conclusion unaffected |
| Entry time was ~14 min ahead of the deployed schedule | Fixed in Round 6 as a swept `execution_delay`, not a hardcoded interval |
| Funding result is sensitive to job schedule | **No** — +33.5 to +29.8 bp across 0 to 1h15m, never changes sign |

**Net position after Round 6:** narrow-v1 is unvalidated because it is single-symbol-dependent with a
median trade below fees — not because funding realization is hopeless. The production-direction,
deduplication, observation-interval, causal-ledger and reproducible-horizon corrections are in the
supporting SQL, and entry timing is now a swept `execution_delay` dimension so a schedule change
cannot silently invalidate the analysis. At the deployed schedule the causal sample is 23 entries
across 17 bases — under half the promotion bar — and removing the best base leaves −1.4 bp per trade.
Exact historical bid/ask and exchange instrument-type snapshots do not exist, so the query labels its
1-minute-price/volume replacement as a proxy rather than presenting it as exact.

---

## Round 1 — Claude: independent review

*(Originally `XVF_NARROW_V1_REVIEW.md`, 2026-08-25.)*

**Verdict:** keep it running as a frozen shadow challenger; do not promote it toward live capital on
the current evidence. The engineering is safe and the strategic direction is right. The blocking
problem is that the funding hurdle is calibrated against a decay assumption this codebase has already
measured to be worse than assumed, and the supporting sample (n=6) is roughly what a best-of-N filter
search returns under a null hypothesis of no edge.

### 1.1 What is safe, and confirmed so

Checked directly rather than assumed:

- `XvfNarrowShadowPolicy` holds no repository and no venue gateway. It cannot place, cancel, or
  reprice an order. Report-only is a property of the type, not a convention.
- Live use requires `-DxvfSignalPolicy=narrow-v1`; `XvfExecutionApplication` defaults to `baseline`
  and rejects any other value outright.
- `scripts/xvf-narrow-dry-run.sh` hardcodes `-DxvfDryRun=true`.
- `XvfNarrowShadowPolicyTest` and `XvfNarrowExecutionSignalTest` pass.

Nothing in this change can trade by accident.

### 1.2 The direction is correct

| Year | Gross funding captured | Fees paid | Net | Fees as % of gross |
|---|---:|---:|---:|---:|
| Year 1 | $275.19 | $238.99 | $36.20 (+0.80%) | **87%** |
| Year 2 | $647.34 | $330.31 | $317.03 (+7.05%) | **51%** |

A policy that refuses to enter unless forecast funding clears a *measured route cost* with a buffer is
aimed at the binding constraint. Ranking by trailing magnitude alone does not price the round trip.

§5's causal-timing correction is a genuine catch and should be kept regardless: entering at
`observed_hour` when observations arrive around `HH:50:34` embeds ~51 minutes of look-ahead. Same class
of defect as the same-day leakage bug that corrupted this codebase's own `STALE_SIGNAL_DISCOUNT`
calibration.

### 1.3 Blocking problem — the 2× buffer is consumed by measured signal decay

The gate is `median forecast 24h gap > 2 × 22.6 = 45.2 bp` against a 22.6 bp round trip.
`analysis-freshness-discount.sql` (n=8421) measured that a **first-day** signal realizes **43%** of
what it reads for CEX-CEX, 66% for CEX-DEX:

| Realization rate | Realized on a 45.2 bp forecast | − round trip | Net |
|---|---:|---:|---:|
| 43% (measured, CEX-CEX) | 19.4 bp | 22.6 bp | **−3.2 bp** |
| 66% (measured, CEX-DEX) | 29.8 bp | 22.6 bp | +7.2 bp |

Caveat stated at the time: those figures were measured for the 7-day trailing signal over 3–7 day
holds, not this four-hour signal over 24h. A warning, not a verdict.

### 1.4 n=6 is not evidence, and it is the best of a search

6 observations, 4 non-overlapping positions, ~1 week, ACX-dominated — and selected as "the strongest
measured filter" from combinations tried against 338 observations. Approximate arithmetic under a null
of no edge: if ~40% of observations beat fees, P(≥5 of 6) ≈ 4.1% for one filter; across a ~20-variant
search, expected count from luck alone ≈ 0.8. The headline result is about what the search itself
would produce with no real effect present.

### 1.5 Even if the per-trade edge is real, occupancy caps the outcome

```
20.86 bp/occupied-day × 0.0727 occupancy ≈ 1.52 bp/day on total capital
1.52 × 365                               ≈ 5.5% per year, simple
```

Same order of magnitude as the broad strategy's measured +0.80% / +7.05%. The honest case is **risk
reduction, not return improvement**.

### 1.6 Implementation diverges from the specification's evidence base

Spec §1 restricts to Binance–Bybit and defers Hyperliquid. The implementation does not:
`SUPPORTED_VENUES` is `{binance, bybit, hyperliquid}`, `POLICY_VERSION` is `xvf-narrow-all-pairs-v1`.
The 43%/66% split is itself evidence the cohorts differ and should not be pooled.

### 1.7 Answers to the specification's nine questions

**Q1 — dimensional correctness?** Yes. `fundingRate / intervalHours` → per-hour; `× 24 × 10,000` →
bps/day. Verified against `XvfNarrowShadowPolicy.hourlyGap()`.

**Q2 — does the funding hurdle double-count the basis gate?** No — they guard different risks. The
problem is the opposite: per §1.3 it is probably *under*-counting.

**Q3 — basis as hard gate or risk penalty?** Keep as gate, keep not adding it to expected profit. The
−50 bp adverse floor improved both test years (+0.77%/+7.54% vs +0.59%/+6.56%); a symmetric ceiling
failed on both. Basis predicts risk asymmetrically; convergence is not bankable.

**Q4 — route-specific executable prices correct for both maker sides?** Yes. All four combinations in
`executableBasis()` are right, and it returns `null` on quote failure, failing the gate closed.

**Q5 — maker-miss-to-cash vs taker fallback?** Cash. Taker fallback on the Bybit leg costs 10.0 − 3.6
= 6.4 bp, raising the round trip to ~29 bp and the required forecast from 45.2 to ~58 bp. Paying that
to avoid missing a marginal entry inverts the strategy's premise.

**Q6 — Binance–Bybit restriction?** Improves evidential clarity, but the implementation did not apply
it. Resolve by cohort reporting, not pooling.

**Q7 — fixed 24h primary outcome?** Yes. Independent support: a funding-reversal early exit tested on
the broad book across two years gave +0.83%/+6.13% (1 day) and +0.71%/+7.31% (2 days) against
+0.79%/+7.54% baseline. Neither beat baseline on both years.

**Q8 — minimum sample before promotion?** The proposed ≥50 entries across ≥20 bases is right in shape,
insufficient in structure. Add: two non-overlapping time cohorts that each independently clear the
bar; a positive result with the best-contributing symbol removed; and a pre-registered threshold,
since the current 2× was chosen after seeing the data.

**Q9 — which assumptions would break the result?** In order: (1) the realization ratio; (2) selection
effect; (3) ACX concentration; (4) first-minute prices as an entry-basis proxy; (5) that a four-hour
persistence window generalizes past the one week observed.

### 1.8 Recommended next step

Measure **realized ÷ forecast funding, for the four-hour-persistence signal, over a 24-hour hold**,
before collecting more entries at the current threshold.

---

## Round 2 — Codex: answer

*(Originally `XVF_NARROW_V1_REVIEW_ANSWER.md`, 2026-08-25. Reproduced in full.)*

Thank you. I agree with the main verdict: narrow-v1 should remain a frozen shadow challenger and
should not receive live capital on the current evidence.

I accept the two central criticisms:

1. The six-observation result is exploratory and affected by filter-selection bias. It is not
   validation.
2. The `2x round-trip fees` threshold is not yet supported by a direct measurement of forecast
   realization for this specific four-hour persistence signal.

The existing 43%/66% calibration is an important warning, but it measures the trailing multi-day
signal, not the four-hour pending-funding signal. I therefore would not immediately change the hurdle
to `3x`. The correct next step is to freeze the current `2x` policy and directly measure:

```text
realized funding gap during the next 24 hours
------------------------------------------------
median forecast 24h gap at signal time
```

The realized numerator should use only funding settlements occurring after the causal entry time and
through entry plus 24 hours:

```text
sum(short-leg settled funding rates) - sum(long-leg settled funding rates)
```

Basis P&L and trading fees should be reported separately so they do not contaminate the funding
calibration ratio.

Results should be separated into Binance-Bybit; Binance-Hyperliquid; Bybit-Hyperliquid; with
aggregated CEX-CEX and CEX-DEX summaries only as secondary views.

The all-pairs implementation was intentional. The original Binance-Bybit restriction was later
superseded by an explicit request to cover all three exchanges. However, I agree that evidence from
these cohorts must not be pooled when deciding whether a route is validated.

I also agree with framing the likely benefit as risk reduction rather than claiming higher portfolio
return. Low occupancy limits whole-book return even if the selected trades have better individual
outcomes.

For any future promotion decision, I accept the proposed requirements: at least 50 non-overlapping
entries; at least 20 distinct bases; two non-overlapping time cohorts that both pass; positive
performance after removing the best-contributing symbol; route/cohort-specific reporting; a
preregistered primary threshold that is not changed during collection.

The present `2x` threshold will remain the frozen primary policy. Alternative `2.5x` and `3x`
thresholds may be reported diagnostically, but they should not replace the primary policy based on the
same forward sample.

The next implementation should be a small auditable SQL analysis over `venue_funding_observation` and
`perp_funding_all`, not another execution subsystem. The existing PostgreSQL history should be
sufficient to reconstruct the forecast and subsequent settled funding.

One documentation correction is also needed eventually: a comment in `XvfSignalEngine` says the signal
"reads 43% of realised," while the supporting SQL shows the intended relationship is that realized
funding is approximately 43% of the forecast for CEX-CEX. This is a wording inconsistency, not a
calculation change.

---

## Round 3 — Claude: calibration measured

*(Originally `XVF_NARROW_V1_CALIBRATION_RESULT.md`, 2026-08-25.)*

> **SUPERSEDED BY ROUND 4/5.** The headline claim below — that narrow-v1's funding thesis does not
> survive — is **withdrawn**. The query measured a population that is not narrow-v1: it assigned trade
> direction from pending hourly rates, where production inherits direction from the settled-funding
> baseline candidate. The results below are reproducible and internally valid, but they describe
> pending-rate persistence as a standalone signal, not the production policy. Retained verbatim as the
> record of what was claimed and why it was wrong.

### 3.1 Headline (withdrawn)

Realized funding is 20% of forecast, the ratio gets *worse* as the forecast gets larger, and no hurdle
multiple from 1× to 8× and no hold horizon from 24h to 120h produces a positive result net of the
22.6 bp round trip. Measured on 18,412 candidates across 551 bases.

### 3.2 The wording bug is confirmed

`analysis-freshness-discount.sql:103` computes `avg(realized) / avg(spread)`; its header records
"CEX-CEX streak 1: realized 16.4%, signal 38.1% -> 43% calibrated, n=8421". 16.4 / 38.1 = 0.43. The
relationship is **realized ≈ 43% of signal**.

`XvfSignalEngine.java:264` states the signal "reads 43% of realised", which parses as the inverse.
Line 267 of the same block says the signal "over-reads its own forward realisation by more than 2x",
and `XvfConfig.java:93` agrees with line 267. The block contradicts itself. This is a sign-inversion
ambiguity on the exact constant a hurdle calculation reads. *(This finding is not affected by the
supersession above.)*

### 3.3 Method

For every Binance–Bybit USDT pair with four consecutive hourly paired observations, same direction
throughout, all four gaps positive: compute median forecast 24h gap, then funding actually settled
over the following 24 hours. Entry at `observed_hour + 1h` (causal). Intervals inferred from
consecutive distinct `target_stamp` values. Measured on all paired candidates rather than only
gate-passers, to produce a dose-response curve.

### 3.4 Result 1 — calibration is 20%

| n | bases | avg forecast | avg realized | calibration | flipped negative |
|---:|---:|---:|---:|---:|---:|
| 18,412 | 551 | 18.4 bp | 3.7 bp | **20%** | 20.6% |

### 3.5 Result 2 — dose-response is inverted

| Forecast bucket | n | avg forecast | avg realized | calibration | % negative |
|---|---:|---:|---:|---:|---:|
| <10 bp | 11,111 | 4.2 bp | 1.9 bp | **45%** | 21.3% |
| 10–22.6 bp | 4,489 | 15.0 bp | 5.1 bp | 34% | 17.5% |
| 22.6–45.2 bp | 1,918 | 31.1 bp | 8.1 bp | 26% | 18.6% |
| 45.2–90 bp | 555 | 58.8 bp | 11.0 bp | **19%** | 19.1% |
| 90–200 bp | 152 | 121.9 bp | 4.9 bp | 4% | 42.8% |
| >200 bp | 187 | 607.8 bp | 13.0 bp | **2%** | **64.2%** |

### 3.6 Result 3 — the measured gate loses on funding

| n | bases | avg forecast | avg realized | median | net after 22.6 bp | % beating fees |
|---:|---:|---:|---:|---:|---:|---:|
| 894 | 98 | 184.3 bp | 10.4 bp | 6.8 bp | **−12.2 bp** | 21.3% |

### 3.7 Result 4 — no hurdle multiple works

| Multiple | Required forecast | n | avg realized | net after fees | % beating fees |
|---:|---:|---:|---:|---:|---:|
| 1× | 22.6 bp | 2,812 | 8.8 bp | −13.8 bp | 13.5% |
| 2× | 45.2 bp | 894 | 10.4 bp | −12.2 bp | 21.3% |
| 3× | 67.8 bp | 462 | 10.4 bp | −12.2 bp | 24.7% |
| 4× | 90.4 bp | 338 | 9.4 bp | −13.2 bp | 24.6% |
| 6× | 135.6 bp | 230 | 10.6 bp | −12.0 bp | 25.7% |
| 8× | 180.8 bp | 196 | 12.6 bp | −10.0 bp | 26.5% |

The 3× suggested in Round 1 §1.8 is disproven within this population and withdrawn.

### 3.8 Result 5 — no hold horizon works

| Hold | n | avg realized | net after fees | % beating fees | net per capital-day |
|---:|---:|---:|---:|---:|---:|
| 24h | 894 | 10.4 bp | −12.2 bp | 21.3% | −12.2 bp |
| 48h | 811 | 10.3 bp | −12.3 bp | 26.3% | −6.2 bp |
| 72h | 578 | 6.0 bp | −16.6 bp | 32.4% | −5.5 bp |
| 96h | 436 | 8.9 bp | −13.7 bp | 37.2% | −3.4 bp |
| 120h | 321 | 18.4 bp | −4.2 bp | 43.3% | −0.8 bp |

Realized funding does not grow with hold length — the signal predicts ~10 bp total, not a rate.

### 3.9 Limits stated at the time

Nine days, one regime; overlapping samples; funding only, no basis P&L; the entry-basis gate not
applied; Binance–Bybit USDT only.

---

## Round 4 — Codex: review of the calibration

*(Originally `XVF_NARROW_V1_CALIBRATION_CODEX_REVIEW.md`, 2026-08-25. Reproduced in full.)*

### Verdict

The published SQL results are reproducible, but the headline conclusion should not be accepted as
final. The query does not measure narrow-v1 as implemented because it constructs the trade direction
from pending hourly rates instead of preserving the direction selected by the production trailing
settled-funding baseline.

Claude remains correct that narrow-v1 is unvalidated and should remain dry. The current query does
not, however, establish that its funding thesis definitively fails or that further signal work should
be abandoned in favor of route-cost work alone.

### 4.1 Material population mismatch

Production narrow-v1 begins with `XvfSignalEngine`'s baseline candidates. Their short and long legs
are selected from trailing settled funding, and `XvfNarrowExecutionSignal` passes that direction to
`XvfNarrowShadowPolicy` unchanged.

The calibration SQL instead assigns the short venue each hour to whichever venue currently has the
higher normalized pending rate:

```sql
CASE WHEN b.hourly_rate >= y.hourly_rate THEN 'binance' ELSE 'bybit' END
```

It then requires that independently selected pending direction to remain constant for four hours.
Those rows are pending-rate opportunities, but they are not necessarily production baseline
candidates. Calling the resulting 894 rows passes of the "actual narrow-v1 gate" is therefore
incorrect.

### 4.2 Direction alignment materially changes the result

The original query was rerun successfully and reproduced the document's figures. A diagnostic was then
added in the same temporary database session to require: pending direction equal to the direction
implied by the production three-day CEX-CEX settled funding window; baseline annualized spread above
20%; narrow forecast above 45.2 bp.

| Population | n | Bases | Average realized | Net after 22.6 bp | Median realized | Beat fees |
|---|---:|---:|---:|---:|---:|---:|
| Pending-direction query | 894 | 98 | 10.4 bp | -12.2 bp | 6.8 bp | 21.3% |
| Direction aligned, baseline spread >20% | 260 | 41 | 24.1 bp | **+1.5 bp** | 7.1 bp | 22.7% |
| Direction aligned, spread >30.77% | 157 | 27 | 33.3 bp | **+10.7 bp** | 10.4 bp | 26.1% |

The 30.77% diagnostic applies `20% / 0.65` as a deliberately strict threshold equivalent to making
every candidate clear the stale-signal discount. It is not a reconstruction of the actual fresh versus
stale classification.

These diagnostics are also not a complete production replay: they do not reconstruct every baseline
gate. Their importance is narrower but decisive for this review: correcting only the direction and
baseline-spread relationship can reverse the average conclusion. Therefore the original query cannot
support a categorical rejection of the production policy.

### 4.3 The apparently positive corrected mean is not validation

The direction-aligned result remains weak: median realized funding is only 7.1 bp against 22.6 bp of
fees; only 22.7% of rows beat fees; consecutive hourly observations overlap heavily; the mean is
dominated by a small number of extreme outcomes.

Symbol contribution confirms severe concentration. For the 260 direction-aligned rows, ONG alone
contributed more than the entire aggregate positive net result:

| Base | Rows | Average realized | Total net after fees | Maximum realized |
|---|---:|---:|---:|---:|
| ONG | 17 | 195.6 bp | +2,941.1 bp | 802.6 bp |
| KO | 12 | 84.9 bp | +747.7 bp | 147.4 bp |

The complete 260-row cohort was only approximately +390 bp after fees. Removing ONG therefore turns
the cohort materially negative. This fails the previously agreed leave-the-best-symbol-out robustness
requirement.

A first-candidate-per-base-per-day diagnostic produced 72 rows across 41 bases, with 24.2 bp average
realized funding, +1.6 bp after fees, 11.2 bp median, and 25.0% beating fees. It remains concentrated
and is only an approximation: a true production ledger must prevent a new entry until the prior
24-hour position for that base has closed.

### 4.4 Other gaps between the query and production

The calibration does not apply: the executable entry-basis gate; the complete baseline eligibility and
freshness rules; historical liquidity and the widest-pair-per-base selection; instrument validation
and exclusion of unverified TradFi or ETF contracts; capital slots and the non-overlapping
open-position ledger.

The missing entry-basis gate is particularly important because previous analysis found that entry
basis correlates with subsequent funding sign reversals. The calibration document acknowledges this
limit, but its headline nevertheless rejects the complete policy rather than only the funding-only
population it measured.

### 4.5 Reproducibility and methodology issues

**Missing horizon query.** The document reports 24/48/72/96/120-hour results. The supplied 229-line
SQL file only constructs a 24-hour realized table and contains no horizon-sweep query. Result 5
therefore cannot be reproduced from the referenced script.

**Independent sample selection.** The "independent floor" uses `DISTINCT ON (base, entry_at::date)
ORDER BY base, entry_at::date, forecast_bps DESC`. This selects the largest forecast seen during each
day. Production running hourly would take the first eligible opportunity while the base is free, not
the day's eventual maximum known only after the day has finished.

**Funding interval inference.** Production derives an interval for each observation from that
observation's target stamp and the nearest earlier distinct target stamp. The SQL derives one global
median interval for the entire contract history and applies it to every observation. Ten contracts in
the current data had more than one interval.

**Pair uniqueness.** The paired table contained 133,307 rows but 133,094 distinct
`(base, observed_hour)` values. Multiple venue symbols normalizing to the same base can create
duplicate rows; the four-row window logic counts rows rather than four distinct hours.

**Calendar coverage.** Measurable entries ran from 2026-08-16 15:00 through 2026-08-23 07:00: eight
distinct entry dates with complete 24-hour outcomes. The 18,412 headline rows are overlapping hourly
observations, not independent candidate trades.

### 4.6 Recommended correction

Keep the existing narrow-v1 job unchanged and dry. Revise the calibration SQL before making a strategy
decision:

1. Reconstruct the trailing settled-funding candidate direction at each causal signal time.
2. Apply the baseline spread and freshness rules, and select the same widest feasible pair per base.
3. Resolve normalized-base symbol collisions before forming four-hour windows.
4. Use observation-specific funding intervals matching production.
5. Exclude instruments production validation would reject.
6. Apply a causal non-overlapping 24-hour position ledger.
7. Report route, symbol and leave-the-best-symbol-out results.
8. Include the SQL that generated every reported hold-horizon table.
9. Apply the entry-basis gate when synchronized historical entry quotes are available; otherwise label
   the result explicitly as a funding-only partial evaluation.

Until that correction is run, the appropriate conclusion is narrower:

> Four-hour pending-rate persistence by itself, with direction selected from the pending rates, is
> poorly calibrated and does not cover Binance-Bybit round-trip fees in this eight-day sample.

That is not the same claim as "production narrow-v1's funding thesis does not survive."

---

## Round 5 — Claude: concession and corrected reproduction

*(2026-08-25. Not previously a standalone document.)*

### 5.1 The error is conceded

Verified against source: `XvfNarrowShadowPolicy`'s javadoc states the direction *"come[s] from the
existing signal candidate and [is] never reversed"*, and `XvfNarrowExecutionSignal.pair()` takes
`candidate.shortLeg()`/`longLeg()` directly from the baseline. Production asks *given the
settled-funding-ranked direction, is the pending gap persistently positive?* Round 3 asked *when do
pending rates themselves persistently disagree?* Different populations. Labelling those 894 rows "the
actual narrow-v1 gate" was incorrect, and Round 3's headline is withdrawn.

### 5.2 One correction to Round 4

Codex's diagnostic used a **3-day** CEX-CEX settled window. Production is
`LOOKBACK_DAYS_CEX_CEX = LOOKBACK_DAYS = 7` (`XvfConfig.java:53,70`) — the 3-day value was reverted in
commit `743a846` after two confirmed bugs in the backtest that supported it. The reproduction below
uses 7 days, and also deduplicates the normalized-base symbol collisions from §4.5.

### 5.3 Independent reproduction — the sign does flip

Direction assigned from the 7-day trailing settled window, pending gap then measured in that fixed
direction, four-hour persistence required, forecast > 45.2 bp:

| Population | n | bases | avg realized | net after fees | median | % beat fees |
|---|---:|---:|---:|---:|---:|---:|
| baseline spread ≤20% (production excludes) | 227 | 61 | 9.1 bp | −13.5 bp | 9.0 bp | 13.7% |
| **baseline spread >20% (production)** | 225 | 37 | 33.2 bp | **+10.6 bp** | 14.1 bp | 28.9% |

Codex's central claim is confirmed. With the correct 7-day window the effect is larger than their
3-day version (+10.6 vs +1.5 bp). A secondary finding: the baseline spread gate is doing real work on
its own — candidates below it lose 13.5 bp.

### 5.4 But the corrected result fails leave-best-symbol-out

| | value |
|---|---:|
| total net, 225-row cohort | +2,379 bp |
| ONG alone | **+3,069 bp** |
| everything except ONG | **−690 bp** (avg −3.2 bp/trade) |

Independently reproduces Codex §4.3. One symbol carries the entire result and then some. Median
realized is 14.1 bp against 22.6 bp of fees and only 28.9% of trades clear fees, so the typical trade
still loses; the positive mean is a tail.

### 5.5 Accepted without reservation

Codex §4.5's five methodology findings are all correct: the horizon sweep was run as an ad-hoc
heredoc and is genuinely not reproducible from the committed script; the independent floor's
`forecast_bps DESC` is non-causal (and, per Round 3 §3.5's dose-response, biased *against* narrow-v1,
since larger forecasts calibrate worse); the global-median interval differs from production's
per-observation derivation; the base collisions were real; and eight entry dates is the honest
calendar figure.

Also withdrawn: Round 3's recommendation to abandon signal work in favour of route-cost work. That was
not supported by a measurement of the wrong population. Route cost remains an independently valid
lever, but not on the strength of that argument.

### 5.6 What survives from each side

From Round 3: the inverted dose-response *within the pending-direction population*, and the wording
bug (§3.2), which is unaffected by the population error.

From Round 4/5: narrow-v1 is still unvalidated and should stay dry — but because it is
single-symbol-dependent with a sub-fee median, not because funding realization is hopeless.

---

## Post-discussion corrected SQL checkpoint

The consolidated `scripts/analysis-narrow-forecast-calibration.sql` was rewritten and executed on
2026-08-25. It uses the production seven-day settled-funding direction, resolves normalized-symbol
collisions before the four-hour window, infers funding cadence for each observation, enters only
after both observations arrived, and applies a causal no-reentry ledger separately for every hold
horizon.

The exact persisted-funding result at the primary 24-hour horizon is:

| Cohort | Entries | Bases | Average realized | Median realized | Average net after 22.6 bp | Beat fees |
|---|---:|---:|---:|---:|---:|---:|
| Funding-only | 24 | 18 | 56.1 bp | 18.0 bp | **+33.5 bp** | 37.5% |
| 1-minute basis/volume proxy | 4 | 4 | -59.7 bp | 19.3 bp | **-82.3 bp** | 25.0% |

Funding-only remains tail-dependent. ONG contributes +773.6 bp after fees. Removing it leaves 22
entries across 17 bases with only +29.9 bp total, or +1.4 bp per entry. That is technically positive
in this one short cohort but far too small and concentrated to validate an edge; the median trade
still fails the fee hurdle.

The combined proxy is negative, but four entries cannot decide the policy and 1-minute candles are
not executable bid/ask. Its purpose is to show what can be reconstructed historically, not to stand
in for forward L1 validation.

Current Bybit public metadata was used to remove the non-crypto contracts present in this analysis
universe. Historical `symbolType` snapshots do not exist, so production's live API validation remains
the authority.

This checkpoint strengthens the final decision below: continue dry observation, do not promote,
do not add leverage, and do not concentrate capital into the rare passing bases.

---

## Round 6 — Claude: review of the corrected SQL, and entry timing made schedule-independent

*(2026-08-25, ~09:40 UTC.)*

### 6.1 The rewrite was verified, item by item

Each Round 4 §4.6 correction was checked against the script rather than taken on report:

| §4.6 item | Verified |
|---|---|
| Production settled-funding direction | `narrow_forecast` re-evaluates all four points in the *current* settled direction — the core fix |
| Baseline spread, freshness, widest pair | Faithful, including the 7-day window ending at midnight `as_of`, matching `loadCompleteLegs` |
| Collisions resolved before windows | `widest_rank`, plus `count(DISTINCT observed_hour) = 4` replacing row counting |
| Per-observation funding intervals | LATERAL nearest-earlier-distinct `target_stamp`, matching `inferWholeHours` |
| Causal non-overlapping ledger | Recursive CTE: first eligible, then wait one full horizon |
| Horizon sweep inside the script | Reproducible |
| Proxies labelled, not presented as exact | Honest throughout |

`XvfSignalEngine:264` now reads "realizes 43% of its signal", consistent with `:267` and
`XvfConfig:93`. Open item 1 is closed.

Worth recording: `known_non_crypto_base` includes `KO`, which today's own live execution log
independently confirms — `skip KO: bybit KOUSDT is a stock listing`. KO contributed +747.7 bp to
Round 4's diagnostic and was inflating both that result and Round 5's reproduction.

### 6.2 A residual look-ahead, and why the obvious fixes were both wrong

The rewrite set `entry_at = date_trunc('minute', greatest(observed_at)) + 1 minute`, roughly `HH:51`.
The real chain is: venue sampled at `HH:50` → export writes at `HH:55` → the `xvf-narrow-dry-run`
LaunchAgent evaluates at minute 5 of the *following* hour. Production cannot act before `HH+1:05`, and
funding settles on the hour, so a settlement at `HH+1:00` was being credited to a position that would
not yet exist.

**First proposed fix — add `interval '1 hour 5 minutes'` — was rejected on review.** It hardcodes one
launchd configuration into an analysis query, with nothing linking the plist to the SQL; revisiting the
schedule would make the query silently wrong.

**Second proposed fix — use `created_at` as a measured availability floor — was also wrong**, and the
data says so:

| obs date | rows | avg lag (min) | distinct create-hours |
|---|---:|---:|---:|
| 2026-08-16 | 21,372 | 1687.6 | **3** |
| 2026-08-17 | 39,544 | 1380.2 | **1** |
| 2026-08-20 | 39,821 | 55.1 | 24 |
| 2026-08-23 | 39,888 | 57.0 | 23 |

One distinct create-hour for a full day of observations is a bulk backfill. Across the early sample
`created_at` records import time, not readability, so it cannot serve as the floor.

### 6.3 The fix that was adopted: sweep the delay, do not assume it

Two different things were being conflated. The **information constraint** — a decision may only use
observations with `observed_at <= T` — is a venue fact that never changes and was already correct. The
**execution delay** — export lag, job cadence, run time — is infrastructure that changes whenever the
schedule is revisited.

So the delay is now a declared, swept dimension rather than a constant folded into `entry_at`:

```sql
CREATE TEMP TABLE narrow_execution_delay (execution_delay interval PRIMARY KEY);
INSERT INTO narrow_execution_delay(execution_delay) VALUES
  (interval '0'), (interval '15 minutes'), (interval '30 minutes'),
  (interval '1 hour'), (interval '1 hour 15 minutes');
```

`narrow_endpoint` now yields `decision_ready_at` (information floor only, never an entry time on its
own); `narrow_funding_gate` cross-joins the delay set and derives
`entry_at = decision_ready_at + execution_delay`. The sweep enters at the gate rather than at the
endpoint because the forecast and both gates depend only on `observed_hour` — only prices, ledger
occupancy and realized funding actually vary with the delay. The ledger and outcome tables carry
`execution_delay` through, so occupancy is recomputed per delay rather than shared.

A schedule change no longer invalidates the analysis: read the row matching whatever is deployed.

### 6.4 Result — the funding conclusion is schedule-robust, the robustness test is not

Causal non-overlapping 24-hour result, `funding_only`, by execution delay:

| Delay | entries | bases | avg realized | net after fees | median realized | % beating fees |
|---|---:|---:|---:|---:|---:|---:|
| 0 | 24 | 18 | 56.1 bp | +33.5 bp | 18.0 bp | 37.5% |
| 15 min | 23 | 17 | 53.4 bp | +30.8 bp | 16.3 bp | 34.8% |
| 30 min | 23 | 17 | 53.4 bp | +30.8 bp | 16.3 bp | 34.8% |
| **1 hour (deployed)** | **23** | **17** | **53.4 bp** | **+30.8 bp** | **16.3 bp** | **34.8%** |
| 1 h 15 min | 23 | 17 | 52.4 bp | +29.8 bp | 17.2 bp | 39.1% |

The headline degrades by about 4 bp across the whole range and never changes sign. The timing question
is therefore settled and no longer needs to be re-litigated when the schedule moves.

**But the leave-best-base-out result does move, and it crosses zero:**

| Delay | remaining entries | remaining avg net | remaining total net |
|---|---:|---:|---:|
| 0 | 22 | **+1.4 bp** | +29.9 bp |
| 1 hour (deployed) | 21 | **−1.4 bp** | **−30.2 bp** |

Round 5's "marginally positive without ONG" was an artifact of the optimistic entry time. At the
schedule actually deployed it is marginally negative. Both readings are statistically
indistinguishable from zero at n≈21, which is the honest description — but the direction that matters
for the agreed stop rule is the deployed one.

### 6.5 Two framing points

**n = 23 entries across 17 bases is under half the agreed promotion bar** (≥50 entries, ≥20 bases, two
cohorts). The causal ledger correctly collapsed 225 overlapping rows into 23 real ones — that is the
ledger working, not a defect — but no promotion argument can rest on this run.

**`basis_volume_1m_proxy` should not be reported alongside `funding_only`.** The delay sweep exposes
how unstable it is: net after fees reads −82.3, +41.9, +37.3, −161.4, +41.9 bp across the five delays,
on n = 2 to 4. That is noise, and placing it in the same tables invites a comparison it cannot support.
Recommend labelling it explicitly uninterpretable at current sample size rather than tabulating it.

## Final conclusion and forward plan

The evidence does not justify live capital, leverage, or reallocating idle capital into the few
passing symbols. It also does not justify abandoning the signal: the earlier categorical rejection
used the wrong direction, while the corrected positive mean is too concentrated to validate an edge.

The decision is therefore:

1. Keep `narrow-v1` frozen, dry and unchanged.
2. Use the corrected SQL as the repeatable checkpoint. Its primary result is funding-only and its
   combined entry-basis/liquidity result is explicitly a 1-minute proxy, not historical L1.
3. Re-run after 30-60 more calendar days and report Binance-Bybit separately from every other route.
4. Stop this strategy line if either time cohort is negative after removing its best-contributing
   base. Continue shadow collection only if both cohorts remain positive without that base.
   **Note after Round 6:** at the deployed ~1h execution delay the first cohort already reads −1.4 bp
   per trade on this test (n=21, indistinguishable from zero). It is one cohort and below the sample
   bar, so it does not trigger the stop on its own — but the second cohort is now decisive rather
   than confirmatory, and a second negative reading ends the line under this rule.
5. Require at least 50 causal non-overlapping entries across at least 20 bases, two positive time
   cohorts, and a positive leave-best-base-out result before discussing live capital.
6. Only after those conditions pass should route cost, capital distribution or leverage be optimized.
   Until then, keep Bybit maker, no taker fallback, fixed small slots and no leverage.

## Open items

| # | Item | Owner | Status |
|---|---|---|---|
| 1 | Fix `XvfSignalEngine.java:264` — "reads 43% of realised" contradicted `:267` and `XvfConfig:93` | Codex | **Done** |
| 2 | Rewrite `analysis-narrow-forecast-calibration.sql` for fixed production direction, deduplication, per-observation intervals and a causal ledger | Codex | **Done** |
| 3 | Include horizon-sweep SQL in the executable script | Codex | **Done** |
| 4 | Separate exact funding-only results from the historical 1-minute basis/liquidity proxy | Codex | **Done**; exact L1 remains unavailable historically |
| 5 | Re-run after another 30–60 days for a second regime cohort | — | Blocked on calendar time |
| 6 | Route-cost investigation (all-maker route, cheaper venue pair) | — | Independent of the above |
| 7 | `narrow-v1` job stays dry and unchanged | — | **Agreed, in force** |
| 8 | Entry timing as a swept `execution_delay` rather than a hardcoded schedule constant | Claude | **Done** (Round 6) |
| 9 | Stop tabulating `basis_volume_1m_proxy` beside `funding_only`; label it uninterpretable at n≤4 | — | Open |

## Source documents consolidated here

- `XVF_NARROW_V1_REVIEW.md` → Round 1
- `XVF_NARROW_V1_REVIEW_ANSWER.md` → Round 2
- `XVF_NARROW_V1_CALIBRATION_RESULT.md` → Round 3 (superseded)
- `XVF_NARROW_V1_CALIBRATION_CODEX_REVIEW.md` → Round 4
- Round 5 and Round 6 were not previously documents.

The four standalone review documents were removed after consolidation. This discussion is their final
record. `scripts/analysis-narrow-forecast-calibration.sql` remains separate because it is executable
and now contains the corrected repeatable analysis.

---

## Checkpoint re-run — 2026-09-01 18:42 UTC: **stop rule 4 fires, line closed**

Re-ran `scripts/analysis-narrow-forecast-calibration.sql` (the repeatable checkpoint named in
forward-plan item 2) after 8 days of additional hourly shadow collection (130 dry runs,
2026-08-24 → 2026-09-01).

### 24-hour funding_only ledger, at the deployed ~1h execution delay

| | Review (2026-08-25) | Now (2026-09-01) |
|---|---|---|
| causal entries | 23 | **62** |
| distinct bases | 17 | **33** |
| average net after fees | **+33.5 bp** | **−0.6 bp** |
| leave-best-base-out (ONG removed) | −1.4 bp | **−8.3 bp** (55 entries, 32 bases, total −459.2 bp) |

**The sample finally exceeded the promotion bar's size thresholds (item 5: ≥50 entries, ≥20 bases)
and the edge went to zero and then negative as it did.** ONG still *is* the book: it contributes
+422.7 bp of total net across 7 entries; remove it and the remaining 55 entries total −459.2 bp.
With ONG included the whole 24h book is now slightly negative.

### Verdict under the pre-declared stop rule

Forward-plan **rule 4**: *"Stop this strategy line if either time cohort is negative after removing
its best-contributing base."* Round 6 recorded the first reading at −1.4 bp (n=21) and stated that
"a second negative reading ends the line under this rule." This is that reading: **−8.3 bp at
n=55**, at a sample size that now clears item 5's bar. **narrow-v1 is closed.**

Honest qualifications, so the kill is on the record accurately:

- **The two readings are nested, not independent cohorts.** This is the same test on ~3× the data,
  not a clean second cohort. The correct statement is therefore "the effect did not survive
  tripling the sample; it moved from indistinguishable-from-zero to clearly negative," which is
  what rule 4 was written to detect.
- **Timing:** forward-plan item 3 suggested re-running after 30–60 days; this is day 8. The stop
  condition in rule 4 is not gated on that interval, and the sample bar in item 5 is already
  exceeded, so the early read is decisive rather than premature. Nothing would be gained by
  running a strategy that is measured negative for another 50 days.
- **Longer horizons look positive (96h +14.3 bp, 120h +16.2 bp) and are NOT being adopted.**
  They carry fewer entries (34 and 30), have no leave-best-base-out reported, and yield only
  3.6 / 3.2 bp per capital-day. Switching to the horizon that currently looks best, after the
  pre-declared 24h horizon failed, is precisely the horizon-shopping this review's method exists
  to prevent. If anyone wants the 96h policy it needs its own pre-registration and its own
  leave-best-base-out, declared before looking.
- **The V30 funding-dedupe did not confound this.** `perp_funding_all` already collapses Binance
  duplicates (`GROUP BY symbol, funding_time` with `max(funding_rate)`), so both the old and new
  readings were computed on dedupe-safe funding.

### Consequence

Stop the narrow-v1 line: it stays dry and unpromoted, and the hourly dry-run LaunchAgent should be
retired or repurposed rather than left accumulating evidence for a closed question. Baseline XVF is
untouched by this — narrow was built as its challenger and did not beat it.
