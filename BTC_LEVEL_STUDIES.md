# BTC level studies — pre-registration (ideas I66 round-number fade, I71 ATH-break)

**Author:** Claude · **Frozen:** 2026-08-30 12:31 UTC, before any outcome query was run.
**Status:** design locked; results appended below the line after execution.
**Code:** `scripts/analysis-btc-level-studies.sql`

## Why this study

I66 (video batch): traders treat round dollar levels as barriers — the claim is that BTC's
*first touch* of a round number after an approach gets rejected (fade the touch). I71: after
BTC breaks its all-time high, follow-through is claimed to differ by era. Both are
prop-account-tradeable if real (defined events, frequent enough, liquid instrument). The
board's method rules apply: definitions frozen first, era splits mandatory, and I66 gets a
**placebo control** — identical mechanics on non-round levels — because "price reverses after
touching *some* level" is true of any level by construction of noise; only the round-minus-
placebo difference counts as evidence.

## Data

BTCUSDT perp 1h bars 2020-01-01 → 2026-08-11 (57,845 bars, verified 2026-08-30 12:28 UTC);
1d bars 2020-01-01 → 2026-07-31 for the ATH study. Price range in sample: $3,622 → $126,209.

## I66 definitions (frozen)

- **Round levels R** = $10,000·k for k = 1…12 ($10k…$120k). **Placebo levels P** =
  $10,000·k + $5,000 for k = 0…12 ($5k…$125k). Same construction rule, $5k offset.
- **Resistance touch at L:** 1h bar with `high ≥ L`, where (a) **no bar in the prior 168h**
  had `high ≥ L` (first touch in ≥7 days), and (b) the **minimum low of the prior 72h
  ≤ 0.97·L** (a genuine approach from ≥3% below, not hovering). Fade position: **short at the
  touch bar's close.**
- **Support touch at L** (mirror, reported separately): `low ≤ L`, no prior-168h `low ≤ L`,
  prior-72h max high ≥ 1.03·L. Fade position: **long at the touch bar's close.**
- **Forward returns:** touch-bar close → close 1, 24, and 72 bars later (≈1h/24h/72h; 1h-bar
  gaps are rare and accepted). Sign convention: the fade predicts **negative** forward returns
  after resistance touches and **positive** after support touches.
- **Era split:** calendar year of the event.
- **Clustering:** the 7-day no-touch condition de-clusters within a level. Cross-level
  simultaneity (two levels touched within 24h) is counted and reported; primary stats use all
  events.

## I66 decision rule (pre-declared)

The fade is only evidenced if the **round-set** forward returns are more fade-favorable than
the **placebo-set** at 24h and/or 72h with |t| ≥ 2 on the difference and a consistent sign in
at least 3 eras. Round ≈ placebo (whatever their common sign) → I66 killed: any apparent
level-fade is generic mean reversion, not round-number structure.

## I71 definitions (frozen)

- Daily closes. `prior_ATH` = running max of all previous closes (within data, from
  2020-01-02). **Event:** close > prior_ATH, with **no event in the prior 30 days** (one event
  per breakout episode).
- **Forward:** close → close +7, +30, +90 days. Baseline: the unconditional mean of same-length
  BTC returns over 2020–2026, shown alongside.
- Expected n is single-digit → **descriptive prior only**, every event row printed, no
  significance claims. Era = event year.

---

## Results (appended after execution — see below)

**Executed 2026-08-30 12:35 UTC** via `scripts/analysis-btc-level-studies.sql`.

### I66 — KILLED (round-number first-touch fade)

103 resistance touches and 92 support touches, 2020–2026, split round vs placebo:

| Set / side | n | mean f24 | med f24 | t | mean f72 | t |
|---|---|---|---|---|---|---|
| round resistance | 55 | +0.37% | −0.06% | 0.81 | +1.24% | 1.69 |
| placebo resistance | 48 | +0.48% | +0.53% | 1.10 | +1.09% | 1.49 |
| round support | 47 | +0.93% | +0.23% | 1.28 | +1.35% | 1.38 |
| placebo support | 45 | +1.97% | +1.11% | 2.50 | +1.05% | 1.12 |

Round-minus-placebo (Welch t): resistance −0.11% at 24h (t=−0.17), +0.15% at 72h (t=0.14);
support −1.04% (t=−0.97), +0.30% (t=0.22). **Nothing approaches the pre-declared |t| ≥ 2, and
no sign is consistent across eras** (yearly table in script output). Two observations worth
keeping: (1) the resistance fade is wrong-signed anyway — after first touching a level from
below BTC *continues up* on average (+0.4% at 24h, +1.1–1.2% at 72h), at round and non-round
levels alike, which is just BTC's drift; (2) the only |t| ≥ 2 cell in the whole table is the
**placebo** support set — exactly the pattern noise produces across 8 cells. Per the
pre-registered rule: killed 2026-08-30 12:35 UTC. Round numbers carry no measurable structure
that an arbitrary $5k-offset level doesn't.

### I71 — descriptive prior: ATH breaks stopped following through after 2020

12 de-clustered ATH-break episodes. Baseline unconditional BTC: +1.0%/+4.4%/+15.8% at
7/30/90d. Event mean f30 = +5.5% — indistinguishable from baseline. The era pattern is the
finding:

- **2020 (3 events):** f30 +27.2%, +3.8%, +45.9% — breakouts ran.
- **2021 onward (9 events):** f30 positive in only 2 of 9 (2024-11-06 +32.0%, 2025-07-10
  +0.4%); median of the last 8 events ≈ −5.4%; the two 2021 breaks and the 2025-10-05 break
  led 30–90d drawdowns of −11% to −48%.

n=12 permits no significance claim (as pre-declared), but the prior is the *opposite* of the
lore: in the post-2020 regime an ATH break has more often marked a local top within 30 days
than a continuation. Worth remembering when the next ATH approach arrives; not tradeable on
this evidence.
