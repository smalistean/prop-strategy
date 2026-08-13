# Exploration plan (2026-08-12)

## What sixteen tests actually taught

Not "nothing works". Something much more specific:

| Approach | Tests | Best result |
| --- | ---: | --- |
| Predict price from a pattern | 12 | Refuted, none close |
| Collect a payment someone is obliged to make | 4 | Sharpe 1.29 - 1.46 |

**Every near-miss came from a payment mechanism. Every clear failure came from a forecast.** And the
best result in the project — the Hyperliquid/Binance funding spread — was found on the first day of
looking at a venue that had never been examined, not from further analysis of data already held.

That is the finding that should direct everything next: **the returns are in structural differences
between venues, not in statistical patterns within one venue.** Binance klines have been mined for
months across Apollo, Gerchik and cross-sectional momentum, and yielded nothing. A new venue yielded
a candidate in hours.

## The strategic point about capital

At €50k, capacity-constrained edges are the **only** place a small account has an advantage. A fund
running $50m cannot trade a $10m-daily-volume coin's funding spread; the position would move the
market. That is not a consolation prize, it is the actual competitive position — and it argues for
hunting in thin, fragmented, operationally awkward corners rather than in liquid ones where size and
latency decide.

Every priority below is chosen on that basis.

---

# Priority 1: the multi-venue funding surface

**Seven venues publish funding, all with public history and no credentials.**

| Venue | Perps | Status |
| --- | ---: | --- |
| Binance | 833 | imported (2.5M rows, 2020-2026) |
| Gate.io | 907 | not imported |
| Bybit | 815 | not imported |
| Bitget | 747 | not imported |
| OKX | 447 | not imported |
| dYdX v4 | 296 | not imported |
| Hyperliquid | 232 | imported (4.45M rows, 2023-2026) |

### Why this is bigger than "more of the same"

Two venues give one spread per coin. Seven give **the best spread per coin** — short the
highest-paying venue, long the lowest-paying — which is by construction at least as large as any
fixed pair, on every coin, every week. The measured Hyperliquid/Binance edge is a *lower bound* on
what the surface offers.

Then breadth compounds it. The current book holds ~10 positions at Sharpe 1.46. Seven venues across
~900 coins offers far more simultaneous, weakly-correlated positions, and `IR ≈ IC x sqrt(N)` is the
only reliable way to raise Sharpe without needing a better signal.

### Registered expectation, before importing

The best-of-seven spread will be **larger** than the Hyperliquid/Binance spread — that is arithmetic,
a maximum over more candidates. The honest question is whether it is larger **net of the cost of
reaching it**: thinner venues, worse fills, more venue risk, and capital fragmented across seven
margin accounts. A best-of-N maximum also selects for measurement error, so some of the increase will
be an artefact of taking a maximum over noisier estimates.

### Order of work

1. Import funding history from Bybit, OKX, Gate, Bitget, dYdX — one importer per venue, reusing the
   throttle and resume pattern already built.
2. Build the venue-by-coin-by-week funding surface.
3. Measure best-spread persistence exactly as in `CROSS_VENUE_FUNDING_MEASUREMENT.md`, with the same
   join guards, then decompose per year and per coin.
4. Only then decide whether the existing forward pre-registration should be superseded by a
   multi-venue one.

---

# Priority 2: new-listing funding, which this project already measured and never used

`CARRY_PREREGISTRATION.md` records that Binance raises funding frequency to hourly when a perp
dislocates, and that **those days average -456% annualised**. It was recorded as a hazard to avoid —
symbols are excluded for their first 30 days — and never examined as an opportunity.

Negative funding means **shorts pay longs**. A long position on those days receives at a rate two
orders of magnitude larger than the 4.5% spreads elsewhere in this project.

The reason it was avoided is sound: being long a freshly listed, dislocating perp is how accounts
die. But the whole point of Priority 1 is that **the hedge exists** — the same coin usually lists on
several venues within days, and shorting it elsewhere removes the price exposure while keeping the
funding differential.

This is untested and the magnitudes are large enough that it deserves a proper look before anything
smaller does. Registered caution up front: a -456% average across a fat-tailed distribution may be
driven by a handful of catastrophic days, and the hedge is least reliable exactly then.

---

# Priority 3: the rate surface beyond perpetuals

Perp funding is one price for leverage. There are others quoted on the same assets:

- **Lending and borrow rates** (Aave, Morpho, and CEX margin desks). Borrowing an asset at X% while a
  perp pays Y% > X% on the same asset is the same payment logic with a different counterparty.
- **Staking yield** against perp funding on the staked asset.
- **Options-implied forward vs perp funding** — already measured on BTC at roughly 2.3% executable,
  which is why it ranks below the others, though the altcoin chains are unmeasured.

Same mechanism class, entirely different participants, so the dislocations are unlikely to be
correlated with Priority 1's.

---

## What to stop doing

- **Testing whether a pattern in price predicts price.** Twelve attempts, zero survivors, and the
  cost of each is days.
- **Re-analysing Binance-only data.** It is the most examined dataset in the project and the most
  competed-for market in crypto.
- **Treating a limitation as a stopping point.** The correct response to "this edge is only 7%" is
  "what structure would make it larger", not "note it and move on".

## What to keep doing

- Pre-registration before every test, with the bar fixed in advance.
- Per-year and per-symbol decomposition as part of the primary result.
- Verifying every join before it feeds a number. That habit found the carry double-count.
- Preferring payments to predictions.

## Immediate next actions

1. Build the five funding importers (Priority 1, step 1). No blockers, no quotas, public APIs.
2. Add the weekly Hyperliquid universe snapshot, so the survivorship defect stops accumulating.
3. Schedule daily incremental imports so forward data collects without manual runs.

Item 3 matters regardless of which priority wins: every strategy here is evaluated forward, and no
forward data is currently being collected.
