# Course-derived strategy research

## Review scope

Reviewed recursively from the user-provided course folder:

- 34 PDF documents, 655 pages in total. Every page was rendered and visually inspected; embedded text was also extracted for rule analysis.
- 298 PNG images. Every image was inspected.
- JPG images and videos were intentionally excluded because the request was limited to PNG and PDF files.

This document is a concise interpretation, not a reproduction of the course. The source material is designed for discretionary trading, so code necessarily introduces objective definitions that are not claimed to be the instructor's exact numeric rules.

## Repeated concepts in the material

1. A level needs a meaningful origin, commonly a strong turning or impulse bar (BSU), not merely the highest or lowest value in an arbitrary window.
2. Subsequent confirmation/retest bars (BPU) strengthen the level. Repeated random intersections weaken the interpretation.
3. Approach quality matters. A clean directed approach supports a bounce setup; small bars, compression, and price “sticking” to the level support a breakout setup.
4. A false breakout first trades beyond the level and then fails to remain there. The reclaim is the actionable confirmation.
5. ATR is used for volatility, stop buffer (“люфт”), and remaining room. Stops belong behind the invalidating structure.
6. A trade requires sufficient room to the target; favourable reward/risk is a filter, not a reason to place an arbitrary stop.
7. Global/local trend, nearby opposing levels, extremity, and the broader scenario are contextual filters.

## Mechanical implementation (v1)

`gerchik-level` implements three separately configured reactions:

- `bounce`: a confirmed pivot cluster, approach into its ATR zone, close back inside, and a reversal close.
- `breakout`: a confirmed pivot cluster, distance contraction into the level, and close beyond it by an ATR threshold.
- `false-breakout`: a prior bar trades beyond a confirmed level and the next bar closes back inside.

Shared level construction:

- search the preceding `levelLookback` candles;
- find local high/low pivots using `pivotStrength` candles on both sides;
- cluster pivots inside `levelToleranceAtr × ATR`;
- require `minimumConfirmations` touches;
- exclude the immediate approach window from level formation to avoid look-ahead and self-confirmation.

Execution:

- stop is behind the level or false-breakout extreme plus `stopBufferAtr × ATR`;
- target distance is the greater of `targetAtr × ATR` and `minimumRewardRisk × structural risk`;
- a time exit prevents indefinite positions.

## Important limitations

- Crypto trades continuously, unlike many stock/Forex examples in the course. Session gaps and opening-range rules are not part of v1.
- “Large player,” visual cleanliness, bar character, higher-timeframe scenario, and nearby opposing-level room are only partially represented.
- A pivot cluster is an objective proxy for BSU/BPU semantics. It cannot reproduce discretionary chart reading exactly.
- The initial parameters are hypotheses frozen before the first training run. They must not be tuned using the testing period.

## Volume-profile level reactions (v1)

Three reactions consume causal 72-hour profiles built from historical aggregate trades.
Each requires an unchanged POC for at least eight 15-minute buckets and a merged zone containing
at least 2% of the profile's traded notional. Price bins are $10 wide; adjacent bins qualify
when they contain at least 50% of the POC bin's notional.

- `volume-profile-breakout`: close outside the stable zone; stop behind the entire zone plus
  0.15 ATR and target at 3R. A next external volume node is not yet exposed, so breakout uses
  the frozen risk multiple rather than pretending an arbitrary price is a structural target.
- `volume-profile-false-breakout`: one candle closes beyond the zone and the next reclaims it;
  stop is beyond the excursion plus 0.15 ATR and target is the opposite zone boundary. Trades
  without 3R of structural room are rejected.
- `volume-profile-channel`: rejection at one zone boundary toward the opposite boundary, with
  the same structural stop and 3R room requirement.

### Frozen BTCUSDT training results

All runs use `[2023-08-07, 2025-08-07)`, aggregate-trade maker trade-through, 1.8 bps maker
fees, 4.5 bps taker fees, and 2 bps taker slippage. Validation and final test remain unopened.

| Reaction | Trades | Return | Win rate | Profit factor | Max drawdown | Conclusion |
|---|---:|---:|---:|---:|---:|---|
| Breakout | 849 | -13.61% | 26.50% | 0.802 | 13.79% | Closest of the three, but crossings do not establish genuine acceptance; every six-month period lost. |
| False breakout | 81 | -5.45% | 17.28% | 0.483 | 5.45% | Structural room controls frequency and drawdown, but most simple reclaims fail. |
| Channel | 788 | -63.28% | 13.96% | 0.386 | 63.28% | A high-volume zone is not automatically a mean-reverting channel; reject this rule. |

All three passed average win/loss ratio but failed profitability, profit factor, subperiod
stability, and stressed-cost acceptance. The 1:3 constraint is working; the missing edge is
entry selection. The next justified experiment should combine stable volume zones with clean
approach/repeated-penetration diagnostics and aggregate-trade absorption or delta confirmation.
Do not tune these v1 thresholds against validation data.

## Next research iterations

1. [Completed infrastructure] Add aggregate-trade volume-at-price levels. Raw historical `aggTrades` permit the same
   idea as the live WebSocket implementation: group traded notional into price bins, treat the
   highest-volume bin as the point of control (POC), and retain aggressor delta as context.
   `HistoricalVolumeProfileApplication` now provides a non-persistent analyzer for this purpose.
   Strategy tests must use a rolling profile whose window ends before the signal candle; using a
   full-day or full-month profile inside that same period would leak future trades.
   BTCUSDT training data is now stored as 1,728,422 15-minute × $10 bins. The
   rolling generator produces prior-only 24h, 72h, and 168h POC/zone metrics.
2. Add alternating, time-separated BPU confirmations and penalize repeated level penetration.
3. Add the nearest opposing structural or volume-profile level as the target/room filter instead
   of ATR-only targets.
4. Add higher-timeframe trend and extremity context.
5. Separate clean momentum approach from overlapping/noisy approach more rigorously.
6. Add diagnostic counters for rejection reasons so the strategy can be improved without blind parameter search.

### Historical volume-profile research command

The example below scans raw BTCUSDT aggregate trades and prints the 15 strongest $10 price zones:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@25 mvn -q exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.HistoricalVolumeProfileApplication \
  -DprofileSymbol=BTCUSDT \
  -DprofileStart=2023-08-07T00:00:00Z \
  -DprofileEnd=2023-09-01T00:00:00Z \
  -DprofilePriceStep=10 -DprofileTop=15
```

The price step is deliberately configurable. Too small a bin produces noisy isolated prices;
too large a bin merges distinct levels. We will compare steps and rolling lookbacks on training
data only before choosing frozen parameters.

## Frozen v1 BTCUSDT training results

Run on BTCUSDT 15-minute futures candles for `[2023-08-07, 2025-08-07)` using the project's maker/taker execution model. Validation and final-test periods were not opened.

| Reaction | Trades | Return | Win rate | Profit factor | Conclusion |
|---|---:|---:|---:|---:|---|
| Bounce | 3 | -0.76% | 0.00% | 0.000 | The clean-approach proxy is too restrictive and all three fills immediately failed. |
| Breakout (corrected) | 0 | 0.00% | 0.00% | 0.000 | Requiring every approach bar to be at most 0.35 ATR is too restrictive. |
| False breakout | 2,637 | -48.64% | 25.18% | 0.736 | A one-bar excursion/reclaim is far too common; structural and context filters are required. |

An earlier defective breakout-compression check admitted 5,271 trades and lost 98.52%; it was corrected before the result above and is retained only as a diagnostic lesson. These results do not disprove the discretionary concepts. They disprove the sufficiency of our first mechanical proxies. The next useful work is not parameter optimization: it is stricter event semantics, nearby opposing-level room, higher-timeframe context, and rejection diagnostics.
