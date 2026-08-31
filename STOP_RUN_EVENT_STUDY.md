# Stop-run event study — OI-flush discriminator

**Author:** Claude, from the user's thesis
**Date:** 2026-08-25 20:49 UTC — **superseded by §9, 2026-08-26 04:39 UTC**
**Status:** The effect replicated on its 2024-Q1 holdout and is **measured at zero in the current
regime** (n=133 continuous events, summer 2026, via Coinalyze hourly liquidation history). Not a
live strategy. §9 has the full chronology; the live collectors continue as a cheap regime monitor.

## 1. Thesis (user's, stated before any query)

Someone who wants to move price in a direction first pushes it the *other* way, through the level
where stops cluster, to eject the "passengers" — traders already positioned in his direction who
would otherwise ride along. The observable footprint of that ejection is a breach of an obvious
level on **falling open interest** (positions closing) rather than rising OI (new positions
joining a genuine break). Prediction: OI-flush breaches revert; OI-build breaches continue.

Prior art in this repo: Apollo and Gerchik sweep strategies tested this shape on **price patterns
alone** and were refuted. They could not see who traded the breach or what happened to
positioning. This study adds exactly that column, so their failure neither supports nor blocks it.

## 2. Frozen design (set before the pooled run; never altered)

| Parameter | Value |
|---|---|
| Bars | 5m klines |
| Level | rolling prior-24h low (288 bars), downside only |
| Event | first bar whose low breaches the level (previous bar did not); events >= 4h apart per symbol |
| Classifier | OI change from last snapshot <= t to first snapshot in [t+10m, t+30m]: **FLUSH** <= -0.25%, **BUILD** >= +0.25%, else ambiguous |
| Entry reference | close of the bar at t+10m (after the classifying OI print exists — causal) |
| Horizon | +2h from entry reference (30m recorded as secondary) |
| Train | AAVE, ADA, AVAX, BCH, BNB, BTC, DOGE, DOT, ETC, ETH, LINK, LTC, SOL, TRX (USDT perps), 2023-05-16 → 2023-12-31 |
| Holdout 1 (time) | same 14 symbols, 2024-01-01 → 2024-03-30 |
| Holdout 2 (symbol) | XRPUSDT, 2023-05-16 → 2024-03-30 |

Two knobs existed (level lookback 24h, OI threshold 0.25%); neither was swept.

## 3. Results

### Train (pooled, per event)

| Class | n | mean +2h | median | t |
|---|---:|---:|---:|---:|
| FLUSH | 1,168 | **+8.4 bp** | +13.3 | 2.64 |
| ambiguous | 875 | +1.8 | +6.0 | 0.58 |
| BUILD | 238 | **-2.5** | +10.4 | -0.36 |

Ordering exactly as predicted. Day-clustered FLUSH (all same-day events averaged to one
observation — the conservative correction for cross-symbol correlation): **193 days, mean +17.5,
median +14.1, t = 3.53**. Clustering strengthened rather than weakened it.

The BUILD and ambiguous classes are the drift control: same event type, same period, same market —
only the OI response differs. "This is just buying dips in a rally" does not survive that
comparison.

### Holdout 1 — held-out time (one shot, 2026-08-25)

| Class | n | mean +2h | median | t |
|---|---:|---:|---:|---:|
| FLUSH | 336 | **+17.1** | +14.7 | 2.16 |
| ambiguous | 446 | +9.5 | +13.9 | 1.23 |
| BUILD | 167 | **-14.1** | +7.7 | -1.05 |

**Replicates.** Full predicted ordering, FLUSH-BUILD spread wider than in train (31 bp).

### Holdout 2 — held-out symbol, XRP (one shot, 2026-08-25)

| Class | n | mean +2h | median | t |
|---|---:|---:|---:|---:|
| FLUSH | 130 | +2.2 | **+20.0** | 0.16 |
| ambiguous | 68 | -1.0 | -2.0 | -0.08 |
| BUILD | 30 | +49.0 | +49.4 | 2.26 |

**Partial.** The FLUSH median (+20.0) is the strongest of any cohort, but the mean is destroyed by
a left tail: 12 of 130 events lost more than 100 bp; p05 = -211, p01 = -648, worst = **-1047 bp**
(2023-08-17, the market-wide flash crash; the other large losses map to XRP's SEC-news days).
BUILD's +49 on n=30 is noted, unexplained, and not explained away.

## 4. What this establishes and what it does not

Established, across a replicated time-holdout: **the OI response to a level breach carries real
information about the next two hours.** Flush-breaches revert; build-breaches do not. This is the
first design in this repository whose primary prediction survived an untouched holdout.

Not established:

1. **Tail safety.** The signal cannot distinguish a stop-run from the first leg of a genuine
   repricing at entry time — they are identical when the flush prints. XRP alone shows what one
   symbol's news-year does to the mean. Any implementation lives or dies on a tail rule (stop, or
   reclaim-failure exit), and that rule is **not yet designed**. Designing it on this data and
   re-checking these holdouts would be fitting; it must be registered before the forward test.
2. **Cost viability.** Holdout-1 mean +17.1 bp against ~11-13 bp taker-taker round trip is thin;
   the median ~= costs. Maker execution (0% on USDC pairs) or longer horizons may widen it; both
   are implementation-layer questions for the forward test.
3. **The dose question** (the user's original framing — at what aggregated forced volume does
   reversion become reliable) is unanswered: ΔOI at 5-minute cadence is a crude proxy for
   liquidation volume. The collector now records actual forceOrder notionals per second.

## 5. Rules now in force

- Both holdouts are burned for this design family. No variant re-tests against them.
- No parameter of §2 changes retroactively. Improvements go into the forward registration only.

## 6. Pre-registered forward test (to be written as its own document before evaluation)

Same event definition, with the classifier upgraded from ΔOI to **actual liquidation notional**
(sum of forceOrder prints in [t, t+10m], per symbol) from `binance_liquidation`, which began
recording 2026-08-25 19:56 UTC. The dose-response — reversion probability and magnitude as a
function of liquidation notional — is the named primary analysis. A tail rule must be specified in
that document before the first look at outcomes. Evaluation begins when at least 8 weeks of
liquidation data exist (~late October 2026); earlier looks are recorded as exploratory.

## 7. Tail-rule design pass — train only (2026-08-25 20:57 UTC)

Nine variants were evaluated on the 1,168 train FLUSH events (five exit rules, four entry timings).
That multiplicity is disclosed here deliberately: train is design space, but the forward test names
**one** primary and treats the rest as recorded alternatives.

**Exit rules all fail, and the failure is structural.** A -50 bp stop produces a *median* outcome
of -52 bp — more than half of all events, winners included, draw down through -50 bp before
reverting. Reclaim-checkpoint exits sell the bottom of the V and made the worst case worse
(-983 vs -729). Winners and losers share the same early path; no exit decision point can separate
them.

**Entry timing separates what exits cannot — in the opposite direction from the obvious guess.**
Entering at t+30m *only if the level is already reclaimed* ("confirmation") is dead: -2.3 bp mean —
the reversion is over by the time it confirms. Entering at t+30m *only while price is still below
the level* doubles the edge: **+17.4 bp mean, median +16.2, p05 -108, t = 4.20, n = 512.** The
premium accrues to whoever is in the position while price is displaced.

**The catastrophic 1% is irreducible with price/OI data.** p01 ~ -290 bp under every variant. The
disasters are not filterable at any decision point; they are also already inside the reported means
(1% x -300 costs ~3 bp of the +17.4). The tail is therefore a **sizing constraint, not a signal
defect**: cap per-event notional so the worst observed event (-1047 bp, XRP) costs an acceptable
account fraction. At 10% of account notional per event, the worst two-year event costs -1.05% of
the account. No fitting, no knobs.

**Registered for the forward test as primary:** FLUSH classification per §2, entry at t+30m
conditional on price still below the breached level, +2h horizon, no stop, per-event notional
<= 10% of account. Everything else in this section is a recorded alternative.

## 8. Execution layer — train only (2026-08-25 21:03 UTC)

Fill simulation on SOL train FLUSH events under the §7 primary rule, against real best bid/ask
(`binance_book_ticker_second`, SOLUSDT, coverage then 2023-05-16→10-07): 23 eligible events,
**96% passive fill rate**, average spread at entry 0.80 bp, gross mid-to-mid +36.7 bp, net at full
taker both sides **+27.1 bp**, maker-in/taker-out +29.7 bp.

What this settles: the adverse-selection failure that killed the flow-imbalance signal does not
apply — the entry condition is a displaced market, so the passive bid fills almost always, and the
edge is tens of bp against ~1 bp of spread and ~7-11 bp of fees. Execution is not the deciding
constraint. What it does not settle: precision — n=23 on the strongest train symbol; the pooled
train +17.4 bp remains the honest central estimate.

Data state: BTCUSDC + ETHUSDC 2024-02→03 imported (9.0M second-rows); SOLUSDT + XRPUSDT
2023-05-16→2024-03-30 importing (~day 144/320 at time of writing). Fill simulation on **holdout**
events remains forbidden until the forward test's rules say otherwise.

## 9. Current-regime evaluation — the effect is gone (2026-08-26 04:39 UTC)

A Coinalyze key (free tier) provided hourly Binance liquidation history covering roughly the last
90 days, which made a verdict-grade test possible immediately instead of late October. Window:
2026-05-12 → 2026-08-10, continuous, 14 symbols — no version of this design had ever touched it.
Frozen rules from §2/§7 applied verbatim, one look.

| Class | n | mean +2h | median | t |
|---|---:|---:|---:|---:|
| FLUSH | 133 | **0.0** | -4.7 | 0.00 |
| ambiguous | 294 | +5.3 | +5.2 | 0.94 |
| BUILD | 48 | +48.1 | +12.6 | 2.42 |

The predicted ordering is not merely absent — it is inverted. With this n, a true +17 bp effect
would have appeared at t ~ 2; the estimate is exactly zero.

Dating the decay with the reserved Tardis first-of-month cohort (its single permitted look):
2024 Apr-Dec -4.5 bp (n=24), 2025 +37.4 (n=29, t=2.57), 2026 first-of-month +47.2 (n=6). The
sparse bars are wide (±20 bp), but the only high-powered recent estimate — the 133-event
continuous window — is zero. The effect was real in 2023 through at least 2025 and is not
present in the current regime.

**Consequences:**

- The §6 forward pre-registration is **withdrawn as a validation** — there is nothing live to
  validate. The collectors stay on as a regime monitor: the same frozen query, run monthly on
  accumulating data, would show the effect returning if it returns. Cost: near zero.
- The planned live paper-trader is **not built**. Paper-trading a measured-zero effect produces
  activity, not information.
- The BUILD inversion (+48, t=2.42, n=48) is recorded and deliberately not pursued: chasing the
  best cell of a failed test is the selection error this repository documents sixteen times.

**Method note for the record:** the decisive acceleration came from refusing to wait for forward
data and instead locating an independent historical source (free-tier Coinalyze) for the same
measurement. The same move earlier located the Binance bookTicker archive gap and the Tardis
free tier. Checking whether the data already exists somewhere is consistently cheaper than
collecting it.
