# Apollo Crypto methodology - mechanical strategy research

## Source scope

The complete 44-page `методичка 2,0.pdf` was text-extracted, rendered, and visually reviewed.
The document says Modules 2-4 form the core system. Those modules cover trend breaks,
liquidity/bases with a fixed volume profile (POC), and price levels. Risk management,
indicators, patterns, traps, and chart examples were also reviewed.

This implementation is an interpretation for reproducible backtesting. The document uses
discretionary chart selection and does not specify enough numerical rules to reproduce the
author's trades exactly.

## Source-derived principles

- Trade on the timeframe used to construct the setup; higher timeframes take priority.
- A trend break requires a clearly formed prior high/low and acceptance beyond it through
  an impulse or several full-bodied candles. A break is a price range, not one exact line.
- A base is a horizontal concentration of candles. The fixed volume profile is stretched
  over candles fully contained in the base's body range; entrance and exit candles are excluded.
- POC is the base's greatest volume concentration. Entry may be placed shortly before it,
  but the stop belongs behind all liquidity.
- After liquidity has already been swept, a later pass-through is more likely. Prefer the
  first approach/retest.
- Breakouts should have strong volume. Absorption/reversal requires opposing volume comparable
  with the impulse it absorbs.
- Stops behind a liquidity zone receive an additional buffer equal to one quarter of the
  zone height.
- Minimum reward/risk is 1:3. Total risk across split entries must remain unchanged.
- RSI and other indicators are confirmations, not standalone entries.

## Implemented v1: `apollo-base-poc-retest`

The first objective translation uses BTCUSDT 15-minute Futures data:

1. Build a 16-candle base from candle-body extremes and require width of 0.75-2.50 ATR.
2. Require a close at least 0.15 ATR beyond the base, a second close remaining beyond it,
   and breakout volume at least 1.20 times its prior 20-candle average.
3. Require the causal 72-hour aggregate-trade high-volume zone to overlap the base, contain
   at least 2% of profile notional, and have an unchanged POC for at least two hours.
4. Enter only the first retest within 12 candles after the breakout, after the retest candle
   closes back on the breakout side of the zone.
5. Put the stop behind the entire zone plus 25% of zone height and target 3R.
6. Expire an open position after 96 bars.

The historical profile available at a candle contains only aggregate trades from earlier
15-minute buckets. Signals execute no earlier than the next candle, with strict maker
trade-through checked against actual aggregate-trade minute ranges.

## Known differences from the discretionary method

- A 72-hour rolling profile approximates, but does not exactly reproduce, manually stretching
  TradingView's fixed profile over the selected base. A future version should calculate a
  profile from the detected base's exact start/end timestamps.
- “Obvious,” “clean,” “full-bodied,” senior-timeframe trap, nearby opposing liquidity, and
  visual trend-break alternation are only partially represented.
- The target is 3R because the current profile generator exposes one dominant zone, not the
  next separate volume node.
- The PDF describes split limit entries; v1 uses one maker entry to keep execution attribution clear.

## Frozen BTCUSDT training result

Period: `[2023-08-07, 2025-08-07)`. Validation and final test were not opened.

- 186 trades; 29.03% win rate; profit factor 0.957.
- Net return -0.61%; maximum drawdown 2.97%.
- Raw price PnL before costs +1,856.50; zero-cost PnL including funding +1,849.37.
- Fees were 1,974.21 and modeled slippage was 485.12.
- Longs earned +1,078.49 net across 95 trades; shorts lost -1,688.45 across 91 trades.
- Average winning/losing trade ratio was 2.34.
- Six-month segments: -1,782.96, -956.59, +2,186.76, and -14.79.
- 1.5x execution-cost stress returned -1,839.68.

The v1 strategy fails acceptance, but it is more informative than the standalone volume-profile
reactions: it has positive raw expectancy, sufficient initial trade count, low drawdown, and a
profitable long component. The next hypothesis should calculate POC over the exact detected base,
add higher-timeframe direction, and predeclare a long-only sensitivity. These are new experiments,
not retroactive tuning of v1.

## Experiment 2: exact fixed-window base POC

Only the profile scope changed: each candidate breakout received POC and high-volume-zone features
calculated from the preceding 16 base candles. The breakout and retest buckets were excluded.
Direction, breakout, volume, retest, stop, target, fees, and execution rules were unchanged.

- 619 trades; 23.75% win rate; profit factor 0.641.
- Net return -21.17%; maximum drawdown 21.92%.
- Raw price PnL was already negative at -3,003.44; fees and slippage increased the loss.
- Longs lost -12,073.26 net and shorts lost -9,100.18 net.
- Every six-month segment lost; 1.5x-cost return was -28.86%.

Conclusion: exact volume scope does not help when the base itself is a fixed arbitrary window.
It turns many ordinary trend pauses into narrow “liquidity zones,” increasing trades from 186 to
619 and destroying the small raw edge. The next necessary change is a variable-length horizontal
base detector with containment, slope/drift, entrance, and clean-break requirements. Long-only or
higher-timeframe filtering should not be evaluated until base construction is credible.

## Experiment 3: variable-length exact-base POC (v3)

The detector was frozen before performance evaluation: 12-48 candles, body range 0.75-2.50 ATR,
limited center drift and close slope, no more than 35% material wick penetrations beyond a 0.10
ATR buffer, and a distinct three-candle entrance of at least 0.25 ATR. The selected timestamps
alone determine the aggregate-trade POC. The strategy still requires a volume-supported clean
break, confirmation, the first return, a stop beyond the complete liquidity zone plus 25%, and 3R.

The signal funnel found 1,837 bases, 169 clean volume breakouts, 73 first retests, and 54 filled
both-direction trades. That version returned -4.05%, PF 0.825, and terminated at the drawdown
limit. Long-only without alignment returned -8.06%, PF 0.695, with negative raw PnL and all four
half-years losing.

The predeclared long-only sensitivity used only completed 1h candles and required 1h close above
EMA-50. It produced 54 trades, -5.38%, PF 0.780, 10.11% drawdown, and -6.00% equivalent net under
1.5x costs. Raw price PnL was only +593.95 (zero-cost PnL including funding +402.34); just one of
four half-years was profitable. Although bullish 24h-regime entries were positive, that is a
post-result diagnostic of only 12 trades and must not become another tuned Apollo variant.

Conclusion: Apollo v3 fails the predeclared raw/net quality, PF, trade-count, chronological, and
cost-stress gates. Variable base selection is now implemented and reusable, but this standalone
POC-retest branch is closed. Validation and final-test data were not opened.
