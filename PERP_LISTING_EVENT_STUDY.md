# Perp-listing event study — pre-registration (idea I55)

**Author:** Claude · **Frozen:** 2026-08-30 12:02 UTC, before any outcome query was run.
**Status:** design locked; results appended below the line after execution.
**Code:** `scripts/analysis-perp-listing-event-study.sql`

## Why this study

I55 (from the 2026-08-30 video batch): new perp listings are attention-driven events — the
hypothesis is that newly listed perps **underperform the market in the weeks after listing**
(hype decay, unlock/insider supply, listing-pump exhaust). Binance's own listing history is the
largest untouched dataset in the local database: 829 USDT-perp listings 2020-01-02 → 2026-07-31
with daily bars from the first trading day. The video sources also insist the meta changes by
cycle, so the era split is mandatory and pooled numbers are not to be believed.

## Universe and data

- All symbols in `binance_perp_kline` with `"interval"='1d'` — 832 symbols, all plain
  `*USDT`, no dated futures (verified 2026-08-30 12:00 UTC).
- **Excluded:** the 3 symbols whose first bar is 2020-01-01 00:00 UTC (the table's global data
  start — presence there means "already listed before our history", not a listing event).
- Benchmark: BTCUSDT 1d closes (full coverage 2020-01-01 → 2026-07-31).

## Definitions (frozen)

- **Listing date `t0`** = the symbol's earliest 1d bar `open_time`.
- **Reference price `P0`** = close of the `t0` bar (listing-day close — an executable
  end-of-first-day entry, after the opening price discovery).
- **Descriptive only:** day-1 open→close return of the `t0` bar (not part of the hypothesis).
- **Horizons:** +1d, +7d, +30d — close of the bar with `open_time = t0 + N days`. A symbol
  missing that exact bar (delisted, or listed too close to 2026-07-31) drops out of that
  horizon; dropout counts are reported, not hidden.
- **Excess return** = symbol's P0→PN return minus BTCUSDT's return over the identical dates.
  Sign convention: negative excess supports I55.
- **Era split:** calendar year of `t0` (2020…2026). Never pooled across years in conclusions.
- **De-clustering:** listings arrive in batches, and same-month listings share market regime.
  Primary statistics per era = the series of **listing-month mean excess returns**: n months,
  mean, median, t = mean/(SD/√n). Per-listing medians and %-negative are reported as
  descriptive context.

## Pre-declared hypothesis and decision rule

- **H (I55):** 7d and 30d excess returns are negative in most eras (medians below zero,
  de-clustered monthly means below zero).
- If the sign is consistently negative across eras → I55 graduates to the idea board as
  "measured, short-side candidate" (a tradability pass — borrow/short feasibility, fees,
  and entry timing — is a separate later study; nothing is promoted to live from here).
- If the sign flips era to era → I55 is recorded as regime-dependent, not a standing edge.
- If excess is flat or positive → I55 is killed with these numbers and a timestamp.

---

## Results (appended after execution — see below)

**Executed 2026-08-30 12:24 UTC** via `scripts/analysis-perp-listing-event-study.sql`.
829 listings 2020-01-02 → 2026-07-31; no horizon dropouts except the 2026 cohort's natural
truncation (194 listings, 152 with a 30d bar) — no delisting-driven dropout appeared at all
in 2020–2025, so survivorship is not distorting the medians.

### Descriptive: per-listing excess return vs BTC, by listing year

| Year | listings | day-1 o→c mean | median ex 7d | median ex 30d | % neg 30d |
|---|---|---|---|---|---|
| 2020 | 78 | +0.4% | −7.9% | −20.0% | 73% |
| 2021 | 59 | +0.3% | −10.5% | −17.7% | 73% |
| 2022 | 26 | −0.2% | −6.7% | −19.8% | 73% |
| 2023 | 99 | +1.7% | −11.7% | −22.3% | 79% |
| 2024 | 131 | −0.1% | −11.1% | −21.2% | 71% |
| 2025 | 242 | +7.1% | −14.5% | −30.8% | 76% |
| **2026** | 194 | −0.1% | **−0.8%** | **+0.3%** | **49%** |

### Primary: de-clustered listing-month means, by era

| Year | n months | mean ex 7d | t | mean ex 30d | t |
|---|---|---|---|---|---|
| 2020 | 10 | −7.0% | −1.67 | −12.6% | −1.15 |
| 2021 | 12 | −1.6% | −0.36 | −1.5% | −0.12 |
| 2022 | 10 | −5.2% | −0.57 | +1.8% | +0.08 |
| 2023 | 12 | −9.4% | **−4.24** | −14.5% | **−2.52** |
| 2024 | 12 | −5.7% | −1.62 | −5.1% | −0.81 |
| 2025 | 12 | −2.0% | −0.45 | −2.9% | −0.23 |
| 2026 | 7 | −1.2% | −0.38 | +3.4% | +0.71 |

### Post-hoc cut (labeled as such — run after seeing the 2026 anomaly)

2026 split into tokenized equity/metal perps vs crypto-native: tokenized (n=34) median ex30
**+4.0%** (41% negative); crypto-native (n=160) median ex30 **−1.8%** (52% negative). The
tokenized cohort explains part of 2026's flatness but not most of it — 2026's own
crypto-native listings have lost the decay too (−1.8% vs −18…−31% in every prior year).

### Verdict per the pre-registered decision rule

**Regime-dependent, not a standing edge.** The sign was negative in six consecutive eras
(2020–2025 medians −18% to −31% at 30d, 71–79% of listings negative — one of the most
consistent effects this repo has measured) and is **absent in the 2026 cohort** (median ≈ 0,
49% negative, both listing types). The monthly de-clustered means tell the tradability story:
only 2023 is statistically strong, because the mean is repeatedly rescued by moonshot tails
(COAI +3,101% excess in 30d, GMT +569%, NEIRO +431%). A naive short-every-listing
implementation earns the median but is short those tails — unhedged, that is an
account-ending exposure (the +3,101% tail against a short is 30x the stake).

### What would revive it

- The 2026 attenuation coincides with Binance's listing-cadence and market-regime change;
  re-run the 2026 row quarterly (one psql command) — if new-cohort medians return to
  −10%+ at 30d, the short-side candidate reopens.
- Any implementation discussion needs the tail problem solved first (defined-risk structure,
  or a filter that excludes the moonshot profile pre-listing — unmeasured, and NOT to be
  fitted on this same sample).
