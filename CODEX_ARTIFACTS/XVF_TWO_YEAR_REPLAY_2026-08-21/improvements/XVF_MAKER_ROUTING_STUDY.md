# Codex XVF maker-routing study

**Created by:** OpenAI Codex  
**Analysis date:** 2026-08-21  
**Scope:** isolated Codex research artifact; no production or existing repository file was changed.

## Question

Would XVF improve if Bybit is always the maker whenever a selected venue pair includes Bybit?

## Method

The comparison reuses the strict Codex no-lookahead candidates and funding exports. Every policy
uses USD 1,500 initial collateral per venue, USD 112.50 fixed notional per leg, the same three-day
candidate schedule, exact-pair retention, full-rank capital backfill, and final taker liquidation.
Only the entry routing policy changes. All exits remain taker orders on both legs.

Fee assumptions are the ones already used by the XVF replay, in basis points:

| Venue | Maker | Taker |
| --- | ---: | ---: |
| Binance | 1.8 | 4.5 |
| Bybit | 3.6 | 10.0 |
| Hyperliquid | 1.8 | 4.5 |

`fee_min_one_maker` selects the maker/taker assignment with the lowest combined fee. With this fee
table it is numerically identical to making Bybit the maker whenever Bybit is present. A literal
"choose the venue with the lowest maker rate" policy is shown separately because it is not the
same optimization: it can leave the expensive Bybit taker fee on the other leg.

## Results: 2024-08-21 to 2025-08-21 exclusive

| Entry routing | Funding | Entry fees | Exit fees | Net | Return | New pairs B-BY / B-HL / BY-HL |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Current: maker on thinner venue | +286.66 | 76.72 | 116.37 | **+93.57** | **2.08%** | 352 / 289 / 182 |
| Bybit maker whenever present | +286.65 | 69.23 | 116.53 | **+100.88** | **2.24%** | 353 / 289 / 182 |
| Fee-minimized, exactly one maker | +286.65 | 69.23 | 116.53 | **+100.88** | **2.24%** | 353 / 289 / 182 |
| Literal lower-maker-rate venue | +286.66 | 91.37 | 116.37 | **+78.92** | **1.75%** | 352 / 289 / 182 |
| Both entry legs maker, ideal fill | +286.92 | 44.25 | 116.63 | **+126.04** | **2.80%** | 353 / 290 / 182 |
| Both entry legs taker | +286.41 | 116.04 | 116.04 | **+54.33** | **1.21%** | 352 / 289 / 180 |

Bybit-maker improves this slice by USD 7.31, or 0.16 percentage points, relative to current
routing. One additional pair is opened because fee placement changes the venue-level collateral
path; this explains the small funding and exit-fee difference.

## Results: 2025-08-21 to 2026-08-21 exclusive

| Entry routing | Funding | Entry fees | Exit fees | Net | Return | New pairs B-BY / B-HL / BY-HL |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Current: maker on thinner venue | +435.92 | 94.12 | 129.79 | **+212.02** | **4.71%** | 268 / 30 / 509 |
| Bybit maker whenever present | +436.13 | 72.93 | 129.79 | **+233.42** | **5.19%** | 268 / 30 / 509 |
| Fee-minimized, exactly one maker | +436.13 | 72.93 | 129.79 | **+233.42** | **5.19%** | 268 / 30 / 509 |
| Literal lower-maker-rate venue | +452.47 | 105.61 | 130.21 | **+216.65** | **4.81%** | 274 / 31 / 505 |
| Both entry legs maker, ideal fill | +453.91 | 48.82 | 130.87 | **+274.22** | **6.09%** | 274 / 31 / 509 |
| Both entry legs taker | +434.72 | 128.97 | 128.97 | **+176.78** | **3.93%** | 268 / 30 / 504 |

Bybit-maker improves this slice by USD 21.40, or 0.48 percentage points. The fee table explains
almost all of it: 509 new Bybit-Hyperliquid pairs dominate the recent book. Changing that pair from
Hyperliquid-maker/Bybit-taker to Bybit-maker/Hyperliquid-taker saves 3.7 bp at entry, or USD 0.041625
on a USD 112.50 leg pair. `509 x USD 0.041625 = USD 21.19`.

The literal lower-maker-rate result is a warning, not evidence for that policy. It pays higher
fees, but the changed venue balances cause a different capital-constrained book that happens to
earn more funding in the recent slice. That is path-dependent selection luck and does not make an
inferior fee route desirable.

## Three-day funding-spread break-even

Thresholds below cover the selected entry routing plus taker exits on both legs. They omit
slippage, adverse markout, basis P&L, and failed maker orders.

| Entry routing | Binance-Bybit | Binance-Hyperliquid | Bybit-Hyperliquid |
| --- | ---: | ---: | ---: |
| Current thinner-venue maker | 27.50% | 18.62% | 32.00% |
| Bybit maker / fee-minimized one-maker | 27.50% | 18.62% | 27.50% |
| Literal lower-maker-rate venue | 32.00% | 18.62% | 32.00% |
| Both entry legs maker, ideal fill | 24.21% | 15.33% | 24.21% |
| Both entry legs taker | 35.28% | 21.90% | 35.28% |

The nominal Bybit-maker benefit applies only to Bybit-Hyperliquid: it lowers entry cost from 11.8
bp to 8.1 bp and the all-in three-day break-even from 32.00% to 27.50% annualized. Binance-Bybit
already makes on Bybit under current routing, and Binance-Hyperliquid is unchanged.

## Why this is not yet a live-execution conclusion

The replay assumes every maker order fills immediately, fully, at the same price an aggressive
order would have received. Real maker execution violates all three assumptions:

- A post-only order can be rejected, remain unfilled through the funding timestamp, or fill only
  partially. If unfilled opportunities are abandoned, the backtest must remove their future
  funding, not award the funding while merely charging a maker fee.
- Maker fills are selected by subsequent market flow. A fill often happens when price moves
  through the quote, so its adverse markout can exceed the nominal fee saving.
- Bybit-maker moves the taker leg from deep Bybit to thinner Hyperliquid. The nominal fee advantage
  is only 3.7 bp on Bybit-Hyperliquid, so incremental Hyperliquid slippage plus Bybit adverse
  selection can erase it.
- If the other leg is crossed before the maker leg fills, the strategy carries naked directional
  exposure. If the maker leg is filled first, there is still exposure during hedge latency, but it
  is shorter and measurable.
- Both-maker entry is non-atomic. Two independent limits need not fill together; one-leg fills,
  partial fills, post-only rejects, and chase-to-taker fallbacks are precisely the costly states
  omitted by the ideal-fill rows.

For a Bybit-Hyperliquid attempt that falls back to taker on both venues, a fee-only calculation is
illustrative. A successful Bybit-maker entry costs 8.1 bp; failed-maker plus all-taker fallback
costs 14.5 bp; current routing costs 11.8 bp. Ignoring every price effect, maker fill probability
must exceed `(14.5 - 11.8) / (14.5 - 8.1) = 42.2%`. This is not a sufficient production hurdle:
adverse markout, timeout movement, partial fills, and lost funding opportunities must also be
included.

Entry-only optimization also leaves the expensive taker exit intact. Under Bybit-maker routing,
the recent slice pays USD 72.93 in entry fees but USD 129.79 in exit fees, of which USD 87.41 is
Bybit exit fees. Maker-first exits with a strict deadline could offer more savings, but close-side
signal decay and hedge risk make them a separate execution experiment.

## Recommendation

Replace the fixed depth heuristic with an expected-cost router, initially in shadow/paper mode:

1. Calculate both maker/taker assignments from the account's actual fee tier.
2. Estimate venue/size-specific maker-fill probability, maker adverse markout, taker slippage, and
   hedge latency from live order telemetry.
3. Place the maker leg post-only; after each partial fill, hedge only the filled quantity on the
   other venue. Cancel or reprice at a declared timeout; never assume the intended notional filled.
4. Record post-only rejects, fill ratio, time to fill, 1/5/30-second markouts, hedge slippage,
   abandoned opportunities, and whether the funding event was actually captured.
5. Re-run the replay by sampling or replaying those empirical fills. Promote Bybit-maker only if
   funding-minus-fees-minus-basis remains better after failed and adversely selected orders.

The fee-only result supports testing Bybit-maker specifically for Bybit-Hyperliquid. It does not
support deterministic both-maker execution or immediate live rollout.

## Reproduce

```bash
python3 CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/improvements/xvf-maker-routing-study.py
```

Source artifact: `xvf-maker-routing-study.py` in this directory.
