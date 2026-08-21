# Codex XVF lifecycle basis-convergence study

**Created by:** OpenAI Codex  
**Analysis date:** 2026-08-21  
**Repository changes:** Codex research artifacts only, isolated in this `improvements/` directory.

## Result

Large funding gaps did **not** produce an additional price-convergence profit on average. They
produced more funding income but were associated with a more adverse cross-venue price basis.

For the actual retained-position lifecycles selected by the strict two-year replay, the raw
annualized funding gap had a -0.169 correlation with equal-dollar basis P&L. The highest gap
quartile earned $307.75 funding but lost $130.83 in basis P&L before $104.39 of fees. The funding
edge remained positive overall; price convergence reduced it rather than adding to it.

The useful discriminator is the **direction of the executable entry basis**:

| Entry trade-price basis | Lifecycles | Basis hit | Basis P&L | Local-model funding | UTC-window funding | Fees | Local-model total | UTC-window total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Short venue >5 bp more expensive (aligned) | 468 | 75.0% | +$41.12 | +$210.30 | +$213.78 | -$116.97 | +$134.45 | +$137.93 |
| Within ±5 bp | 516 | 55.8% | +$10.06 | +$201.28 | +$200.17 | -$130.81 | +$80.53 | +$79.42 |
| Short venue >5 bp cheaper (adverse) | 634 | 21.0% | -$220.69 | +$294.35 | +$277.35 | -$166.49 | -$92.83 | -$109.83 |

The aligned group began at a median +12.63 bp short-minus-long price basis and converged by 8.44
bp. The adverse group began at -14.70 bp and converged toward zero by 12.54 bp; that normal
convergence loses money because the strategy is short the cheaper venue and long the expensive one.

This supports adding an entry-basis alignment/risk model. It does not support adding an
unconditional "large funding gap means extra basis-arbitrage profit" bonus.

## Production-like portfolio impact

The lifecycle reconstruction exactly reconciles the canonical funding-only replay:

| Independent period | Funding | Fees | Existing net |
| --- | ---: | ---: | ---: |
| 2024-08-21 to 2025-08-21 | +$286.66 | -$193.09 | +$93.57 |
| 2025-08-21 to 2026-08-21 | +$435.92 | -$223.90 | +$212.02 |

Historical trade-price endpoints cover 1,622/1,630 lifecycles (99.51%). Removing `H` and `PURR`,
whose contracts cannot be canonicalized with current metadata, leaves 1,618 usable price
lifecycles. Their omitted basis P&L is:

| Comparable covered subset | Lifecycles | Basis | Local funding | UTC funding | Fees | Local total | UTC total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2024-08-21 to 2025-08-21 | 819 | -$49.02 | +$285.63 | +$280.42 | -$192.19 | +$44.42 | +$39.21 |
| 2025-08-21 to 2026-08-21 | 799 | -$120.50 | +$420.30 | +$410.88 | -$222.07 | +$77.73 | +$68.31 |

For a full-ledger attribution, keep all canonical funding/fees and treat the 12 missing or
unverifiable price lifecycles as zero unknown basis:

| Independent period | Covered basis P&L | Local-model net + basis proxy | Return | UTC-window funding - fees + basis | Return |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2024-08-21 to 2025-08-21 | -$49.02 | about +$44.55 | about +0.99% | about +$39.43 | about +0.88% |
| 2025-08-21 to 2026-08-21 | -$120.50 | about +$91.52 | about +2.03% | about +$80.63 | about +1.79% |

The proxy roughly halves the funding-minus-fee result. Median lifecycle basis P&L is nearly zero
(-0.36 bp on two-leg gross capital), but losses have a substantially larger tail: per $225 pair,
p01 is -$2.546 versus p99 +$0.848.

The local-model column is an attribution hybrid: it combines the canonical local-midnight funding
with delayed UTC00 trade prices. The UTC-window column is time-consistent with those price proxies:
funding inside the same UTC00-to-UTC00 lifecycles is $281.54 and $425.03. It is still not an exact
portfolio rerun because entry selection and venue balances remain those of the canonical ledger.
Production's signal/entry cutoff is Europe/Chisinau midnight, while broad synchronized price data
exists only at UTC midnight, 2–3 hours later. That delay misses about $5.12 and $10.89 of funding.

### Entry-basis result by year

The direction result appears in both independent periods rather than coming from one year:

| Period | Entry basis | Local-model total | UTC-window total |
| --- | --- | ---: | ---: |
| 2024-08-21–2025-08-21 | Aligned | +$59.88 | +$59.98 |
| 2024-08-21–2025-08-21 | Flat ±5 bp | +$31.31 | +$29.90 |
| 2024-08-21–2025-08-21 | Adverse | -$46.77 | -$50.67 |
| 2025-08-21–2026-08-21 | Aligned | +$74.57 | +$77.95 |
| 2025-08-21–2026-08-21 | Flat ±5 bp | +$49.22 | +$49.52 |
| 2025-08-21–2026-08-21 | Adverse | -$46.06 | -$59.16 |

These are conditional slices of the selected ledger, not a self-consistent filtered-book replay.

## Funding-gap quartiles

After removing `H` and `PURR`:

| Raw annualized gap | Lifecycles | Basis hit | Basis P&L | Local funding | UTC funding | Fees | Local total | UTC total |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Q1, 20.0–31.1% | 404 | 50.5% | -$4.26 | +$107.71 | +$104.37 | -$100.39 | +$3.06 | -$0.27 |
| Q2, 31.1–40.7% | 405 | 51.4% | -$3.46 | +$94.48 | +$92.41 | -$105.60 | -$14.58 | -$16.65 |
| Q3, 40.7–64.8% | 404 | 49.3% | -$30.97 | +$196.00 | +$192.93 | -$103.89 | +$61.13 | +$58.07 |
| Q4, 65.1–2229.5% | 405 | 39.8% | -$130.83 | +$307.75 | +$301.58 | -$104.39 | +$72.53 | +$66.36 |

The Q4 funding gap is valuable, but its realized price component is the worst. Raw gap correlates
-0.243 with initial price-basis direction and -0.221 with subsequent convergence. This is consistent
with high funding being compensation for crowded positioning and adverse venue basis.

## Method and sign validation

- Reproduced every newly opened lifecycle from the strict no-lookahead candidate export, including
  capital skips, lower-rank backfill and exact-pair retention.
- Used each actual `entry_day -> close_day`, not a forced three-day close.
- Froze the signal at the production local-midnight cutoff, then used the first common UTC-midnight
  trade-candle opens because Bybit has no intraday history.
- Used Binance 1h opens and Bybit/Hyperliquid 1d opens at identical UTC timestamps.
- Normalized explicit `1000`, `10000`, `100000`, `1000000`, `1M`, and Hyperliquid `k` prefixes.
- Equal-dollar basis P&L matches current sizing:
  `N * [(1 - short_exit/short_entry) + (long_exit/long_entry - 1)]`, with `N=$112.50`.
- A normalized equal-token-quantity calculation was retained as a cross-check.

Manual check: ZEC short Bybit / long Binance entered at a +304.7 bp basis and closed at +15.8 bp;
the pair earned +$2.91 of price P&L. BARD short Binance / long Bybit entered at -303.6 bp and
closed at -55.9 bp; convergence hurt the orientation and lost $1.81. Both confirm the implemented
sign convention.

## Data limitations

- The database has trade OHLC, not historical mark/index/bid/ask prices.
- Bybit has 1d candles only; `mid_price` is null in all 494,972 rows.
- Hyperliquid has broad 1d history, but 1h history for only 94 coins and ends before the replay end.
- A daily candle's open is the first trade in its bucket, not proof of an executable quote exactly
  at 00:00.
- Price endpoint coverage by leg is Binance 937/939, Bybit 1,311/1,311 and Hyperliquid 1,004/1,010.
- `PURR` is about 100x different across venues and `H` about 2x; absent effective-dated contract
  metadata, these may be implicit multipliers or ticker collisions and are excluded. `XPL` remains
  but a sensitivity excluding every initial gap above about 10% changes aggregate basis from
  -$169.51 to -$177.77, so the conclusion does not depend on it.

## Architecture recommendation

Add a separate expected-net-P&L layer before capital allocation:

1. Canonicalize instruments with effective-dated asset identity and contract multiplier metadata.
2. At decision time, compute normalized executable basis using short bid and long ask, plus mark and
   index basis for diagnostics.
3. Prefer funding direction aligned with price basis: the short venue is expensive and the long
   venue cheap.
4. When basis is adverse, subtract a forecast convergence loss and require funding to cover it,
   entry/exit fees, slippage and a tail buffer.
5. Rank candidates by expected net dollars per unit of constrained venue capital, then backfill.

An aligned-basis filter is promising, but the table above is conditional analysis, not a filtered
portfolio replay. Removing entries changes lower-rank backfill and venue capital usage; it must be
rerun end-to-end before changing production.

For a decision-grade replay, collect synchronized 1m/tick mark, index, bid, ask and last prices;
effective-dated contract identity/size/quote/linear-inverse metadata; and actual order/fill time,
price, normalized quantity, fee and maker/taker status. Key historical prices by
`(venue, venue_symbol, price_type, event_time)` and preserve both source and receive timestamps.

## Reproduce

```bash
python3 CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/improvements/xvf-basis-selected-entries.py
psql -X -U prop_strategy_app -d prop_strategy \
  -f CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/improvements/xvf-basis-lifecycle-export.sql
python3 CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/improvements/xvf-basis-lifecycle-analysis.py
```

Outputs:

- `improvements/generated/xvf_basis_selected_entries.csv`
- `improvements/generated/xvf_basis_lifecycle_legs.csv`
- `improvements/generated/xvf_basis_lifecycle_results.csv`

The lifecycle analysis is the primary result. A fixed-three-day diagnostic was intentionally not
included in this folder because it understates retained positions' funding and price exposure.
