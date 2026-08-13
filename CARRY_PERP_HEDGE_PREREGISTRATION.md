# Perp-hedged funding carry — pre-registration (2026-08-12)

Written **before any backtest of this construction**. Values fixed here are not to be swept.

## Why this exists

`CARRY_PREREGISTRATION.md` cleared its bar and is still unusable, for two measured reasons:

1. **The universe drained.** Of the ten highest-funding perps each week, the share that also had a
   spot pair and 30 days of history fell 80% (2021) → 43% (2024) → 12% (2025) → **0.3% (2026)**.
   The strategy spent 2026 buying whatever was left, whether or not it paid.
2. **Return is unusable at 5.7%/yr**, because capital is charged as the full notional of both legs -
   and it has to be, when one leg is spot inventory that must be bought outright.

Both trace to the same design choice: **the hedge is the same asset**. That is what forces a spot
pair to exist, and what forces one leg to consume full notional.

The funding itself has not decayed. In 2026 the weekly funding autocorrelation is **0.447** and the
top ten by prior-week funding still realise **+48% annualised** the following week. The money is
still there. The hedge cannot reach it.

**This test replaces the spot leg with a long in BTCUSDT perp.** The short still collects the rich
funding; the long still cancels most of the price exposure; and neither leg needs a spot pair to
exist.

## What this gives up, stated first

A same-asset hedge cancels price exposure exactly. A beta hedge does not. Shorting a thin altcoin
perp against long BTC is **not** market-neutral - it is beta-neutral against one estimate of beta,
and beta is least reliable in exactly the correlated liquidation cascades where funding flips. This
converts a near-riskless spread into a position with real, unhedged residual risk.

That residual is the entire question this test answers. It is not a detail to be discovered later.

## The strategy

1. Weekly, rank symbols by **trailing 7-day realised funding**.
2. Eligible: listed **>= 30 days**; perp 30-day median volume **>= $10m**. **No spot requirement.**
3. Take the **top 10** by trailing funding, equal weight.
4. Each position: **short the perp**, and **long BTCUSDT perp** at `beta x` the short notional.
5. Hold one week, then re-rank.

### Beta, fixed here

**OLS beta of daily log returns of the symbol on BTCUSDT, over the trailing 30 days, computed at
entry and held constant through the week.** Not re-estimated intraweek, not shrunk, not winsorised.
If fewer than 20 overlapping days exist the symbol is ineligible.

### Hedge instrument, fixed here

**BTCUSDT perp, always.** ETH is not offered as an alternative, because choosing per-symbol between
two hedges after seeing results is a free parameter worth several points of apparent Sharpe.

### Control variant, declared now and reported always

The same book with **beta forced to 1.0** (equal notional). This is the control. If it matches or
beats the beta-weighted book, the beta estimation adds nothing and should be dropped - the same role
G3 played in `GERCHIK_V2_PREREGISTRATION.md`, where the control won.

## Costs and accounting, declared

- **6.5 bp per side per leg** (4.5 taker + 2 slippage), unchanged, on both legs.
- **The BTC hedge leg is netted across the book.** All ten hedges are the same instrument, so a real
  account holds one aggregate BTC long and trades only the change in it. Cost is charged on the
  **net BTC notional change** per rebalance, not per position. This is declared in advance because it
  is what an actual implementation does, not because it flatters the result.
- **Funding is charged on both legs.** The short receives the alt's funding; the long **pays**
  BTCUSDT funding on the hedge notional. Mature BTC funding has run positive, so this is a real and
  persistent cost, not a rounding term.
- **Residual price PnL is charged in full**: `beta x btcReturn - altReturn` is carried explicitly.
  Whatever the hedge fails to cancel lands in the result.
- Capital is the **total of both legs**, the same conservative denominator as the spot version, so
  the two are directly comparable.

**Note, not claimed as a result:** both legs are now margin instruments, so the real capital
requirement of this book is far below the two-leg notional. That is the second blocker addressed.
It is deliberately *not* taken as credit in the reported numbers.

## Predictions, registered

1. **Return rises well above 5.7%** on the same denominator, because the reachable universe is far
   richer - 2026 top-10 raw funding ran near +100% annualised.
2. **Sharpe falls below 2.16.** The hedge is worse. If Sharpe does *not* fall, the residual risk is
   being under-measured somewhere and the result should be distrusted rather than celebrated.
3. **2026 turns positive**, from -4.9%. This is the specific failure the design targets.
4. **The worst weeks are market-wide deleveraging**, and they are worse here than in the spot
   version, because realised beta exceeds estimated beta when everything sells at once.
5. **The equal-notional control lands within noise of the beta book** on large caps and clearly
   behind it on small caps, where betas depart furthest from 1.

## The bar for continuing

Net of all costs, on the declared primary configuration, over the full available history — **all
four**, not any:

- **Sharpe >= 1.5**, and
- **maximum drawdown <= 20%**, and
- **net annual return >= 15%** on total two-leg capital, and
- **2026 positive**

The first two are unchanged from the spot carry deliberately: the argument for preferring carry over
a directional strategy is risk-adjusted quality, and lowering that bar now — after a construction
that met it — would be moving the goalpost to fit a weaker result.

The last two are **new and stricter**. A 5.7% book that clears Sharpe 1.5 has already been built and
rejected as not worth trading, so return is a pass condition this time rather than a footnote. And a
design whose entire justification is fixing 2026 does not get to pass while 2026 is still negative -
that is the failure mode this repository has recorded eleven times.

## Explicitly forbidden

- Sweeping the beta lookback, the hedge instrument, the position count, the volume floor, or the
  hold period after seeing results.
- Reporting the beta book without the equal-notional control beside it.
- Reporting funding received as though it were strategy return, or omitting the BTC funding paid.
- Netting the two legs to inflate return on capital, having declared the two-leg denominator above.
- Excluding deleveraging weeks as anomalies. They are the risk being underwritten, and they are the
  reason this construction might not work.
- Adding a participation threshold mid-test. If one is wanted it is a separate addendum registered
  before it is run, as in the spot version — where it was registered, tested, and **rejected**.

## Known limits before starting

The richest funding sits on the newest and thinnest perps. Those have the least stable betas, the
widest spreads, and the shortest return history to estimate anything from — so the names that pay
most are exactly the ones the hedge understands least. If the result is carried by symbols whose
30-day beta is estimated from barely 20 observations, the Sharpe is an artefact of the estimator and
not a property of the trade.

A per-year and per-symbol-size decomposition is therefore part of the primary output, not an optional
follow-up. If one year or one handful of names carries the whole result, it fails regardless of the
aggregate numbers.
