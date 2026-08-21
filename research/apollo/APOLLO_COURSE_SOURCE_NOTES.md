# Apollo Crypto: source-grounded strategy notes

Source reviewed: `методичка 2,0.pdf` (44 pages, provided 2026-08-09). This note records what the
course teaches and separates that teaching from the mechanical rules we can responsibly test. It is
not a claim that the course's explanatory market narratives are established facts.

Supplementary source reviewed: `Книга 2.0.pdf` (129 pages, provided 2026-08-09). It expands the
same framework with base types, exits, traps, and worked trades. The retained source summary below
is deliberately compact enough that the book need not be reread for future implementation work.

## Core hierarchy

The course is a discretionary, multi-timeframe framework. It says the higher timeframe has priority
and that a structure should be drawn and traded on the same timeframe. When price is in a base or
sideways market and structures overlap, it recommends moving one or two timeframes lower to analyse
and trade that smaller structure (pp. 13-14).

For research, this means a setup must record all of the following separately:

1. setup timeframe;
2. higher-timeframe context and nearby opposing structure;
3. local structure used for the entry;
4. the exact event that invalidates the setup.

It is not valid to call every numerical pivot a trend break or to use a 1h filter as a substitute for
the course's structure reading.

## Glossary in the course's own meaning

| Term | Course meaning | Mechanical implication |
| --- | --- | --- |
| Base / liquidity | A horizontal, bounded concentration of candles; the glossary describes liquidity as a candle cluster and, more fundamentally, a concentration of unfilled orders. A base is price movement in a range with clear boundaries (p. 3). | Detect a visually isolated horizontal candle group; do not treat a fixed number of ordinary trend-pause candles as a base. |
| POC | The maximum concentration of volume, and therefore orders, in the selected base / fixed-volume profile (pp. 23-25). | Compute it only from candles wholly inside the selected base. Exclude the entrance and exit candles. |
| Main / global trend break | Break of a meaningful, clearly formed prior high or low, followed by acceptance. A local break can reach the global break and fail; breaking the global one supports a directional change (pp. 13-14). | Require a hierarchy of swings, not a single prior-bar high/low. Represent the break as a zone from wick end to candle body, not as a one-price line. |
| Local trend break | The nearer internal structural break, distinct from the global break (pp. 13-14). | Keep it separate from global structure. A local break alone is not reversal confirmation. |
| Early break / hook (`крючок`) | The first pullback back in the prior direction, toward the latest low/high. It is an early, risky clue only; it can be a deception and does not obligate price to reverse (p. 18). | Never treat it as a standalone reversal. Wait for the hook to finish, enter with a trigger order, and keep the first target before the setup-timeframe trend break. RSI divergence is presented as an additional confirmation (p. 19). |
| Consolidation / acceptance (`закреп`) | Price has moved beyond a level and formed several full-bodied candles there, or a pair of impulse candles (p. 3). | Measure both close location and candle-body quality. A single wick or a single small close is insufficient. |
| Liquidation (`ликвидация`, margin call) | In the glossary, losing the entire margined deposit; later examples also use the word for large forced market events (pp. 3, 39). | Candle data cannot identify trader liquidations. Use explicit liquidation-feed data if this becomes a signal; otherwise record only a price/volume sweep proxy and do not label it a liquidation. |

## Trend-break checklist from the course

A valid break should meet these conditions (p. 14):

1. The preceding high or low is clearly visible.
2. Price accepts beyond the break with an impulse or several consecutive full-bodied candles.
3. The next-higher-timeframe opposing break is not nearby; otherwise price can react there and stop
   the trade before breaking it.
4. The break is a range from the wick end to the candle body, not a precise line.
5. Breaks alternate. Until an opposite break has formed, the current break remains unconfirmed /
   uncertain in the course's framing.

The course also warns that once acceptance creates new liquidity or consumes the liquidity that was
the anticipated target, the expected move is lower quality and may be better skipped (p. 17).

## Liquidity and fixed-volume profile

The course links a horizontal base with capital being built or distributed over time (p. 23). Its
practical rules are more specific than our prior Apollo implementation:

- Select the visually obvious horizontal candle concentration.
- Find its maximum body low and body high.
- Stretch the fixed-volume profile over **only** candles entirely inside those body boundaries.
- Exclude the entrance and exit candles (p. 25).
- The profile's largest volume concentration is the POC; internal volume waves can be meaningful
  intermediate reaction/target areas, so price need not reach the principal POC (p. 24).
- Larger volume and visibility on higher timeframes increase the course's confidence in the area
  (p. 23). This is a stated heuristic, not yet a validated result in this repository.

### Supplementary clip: `profili_obioma.mp4` (reviewed 2026-08-09)

The short recording is consistent with, and makes one important implementation detail clearer than,
the PDF: the presenter manually selects TradingView's **Fixed Range Volume Profile** and stretches it
over a small, visually bounded horizontal candle cluster (YFIUSDT 1h, around 19 June 2025). It is not
applied to a rolling last-*N*-bar window or to the whole preceding trend. The clip's outcome claim is
that price can return to the profile's concentrated area; that is a trading hypothesis, not evidence
of an edge by itself.

Therefore the unresolved core of any automated Apollo version is **base selection**: find a completed,
horizontal, isolated consolidation first; only then compute its exact base-only profile and track a
later revisit. A pivot-cluster level is still only a proxy, which explains why v2 must not be treated
as a faithful automated test of this visual rule.

### Supplementary clip: `slom_trenda.mp4` (reviewed 2026-08-09)

The clip reinforces the PDF's hierarchy rather than adding a simple new indicator. A `слом тренда`
is not a touch or wick through a horizontal line "in the air": it must invalidate an identifiable
sequence of swings on the setup timeframe. The presentation distinguishes a small/local break from
the break of the older (higher-timeframe) structure, and explicitly uses the 15-minute timeframe as
the lower-timeframe context rather than as a substitute for that hierarchy.

For an automated label this means: require a previously confirmed global swing, a later local swing
inside that trend, and a completed-candle acceptance beyond the relevant *zone*. A wick-only breach,
or a lower-timeframe break while the higher-timeframe structure is intact, is a **non-signal**. The
exact visual choice of which swing is "global" remains a labelling problem; it cannot responsibly be
replaced by a bare `close > max(high, last 3 bars)` rule.

### Supplementary clips: base, retest, and profile trap (reviewed 2026-08-09)

- `eth_30_june_2025.mp4` shows the full map-first sequence: define the large ETH range and its
  nearby targets first, allow the lower range boundary to be swept, then require a lower-timeframe
  structural change before treating the upper boundary / next marked liquidity area as a target.
  The drawn route is an example scenario, not a prediction rule.
- `tbx.mp4` treats a bounded consolidation as a tradable box only after a breakout is accepted and
  then retested. A mere touch or wick at a box boundary is insufficient. The next liquidity area,
  not an arbitrary fixed reward multiple, supplies the proposed objective.
- `profil_obioma_lovushka.mp4` is the essential negative example: a historical fixed-profile POC
  is **not** a blind limit-entry level. Price can revisit it, trade through it, or first take the
  liquidity behind it. The POC becomes actionable only with the current sweep/reclaim and structure
  context; otherwise it is a map reference and a reason to wait.

Together these clips rule out an automatic rule of the form “price reaches POC → enter.” A faithful
future labelled test would require the ordered state sequence: **fresh base → approach/sweep →
acceptance or reclaim → local structural confirmation → room to the next mapped liquidity**.
- If price has already swept/removed the liquidity once, even with a near miss, the course expects a
  later pass-through to be much more likely (p. 24). That area should not receive a second ordinary
  fade entry.

## `Книга 2.0.pdf`: retained implementation summary

### Base and profile construction

- A base is a distinct range with at least four points, conventionally two contacts on each side;
  it may be horizontal or compress toward one boundary (pp. 34-35). Imperfect touches and wicks are
  permitted. A continuation base is traded in the established trend direction, from the opposite
  boundary; a reversal base needs a major contextual level, lack of continuation pressure, and
  usually an internal trend break (pp. 35-38).
- The fixed-profile selection rule is exact: determine the greatest body high and body low of the
  visually selected horizontal candle cluster, then include only candles fully inside those body
  bounds. The entrance and exit candles are excluded (p. 33). Internal volume waves may be earlier
  targets than the principal POC (p. 32).
- A base break has two source-described retests: a shallow edge retest, more likely when liquidity
  sits near that edge; and a deep retest that reaches the base POC, requiring attention to untraded
  internal liquidity and a wider structural stop (pp. 40-41).

### Entry, invalidation, and target principles

- For a higher-timeframe liquidity/base entry, place one or several limits a little before the
  principal/internal volume wave. The total risk across split orders remains unchanged. The stop is
  beyond the entire liquidity/base plus one quarter of its height; nearby lower-timeframe liquidity
  or a volume level may be the more meaningful invalidation point (pp. 36, 52-54).
- A continuation-base entry may be improved by a lower-timeframe structural break at the boundary.
  A breakout/retest entry requires real acceptance beyond the base: several full-bodied candles, not
  one or two wick-like candles (p. 53).
- Targets are not a fixed multiple alone: take some profit before a base boundary, higher-timeframe
  break, liquidity, or internal volume wave. The text also offers exits on an opposite break/retest,
  then higher-timeframe breaks, with staged partials (pp. 36, 49-54, 111).

### Traps and hierarchy

- Do not trade a lower-timeframe countertrend signal into an intact strong higher-timeframe base,
  level, or break (pp. 13, 98). Two nearby highs/lows create a stop-liquidity line; a sweep can be
  immediate or delayed (p. 95).
- Do not blindly enter the third touch of a horizontal base boundary: the book expects a short-stop
  sweep there often enough to avoid automatic entries (p. 98). Likewise, unremoved nearby liquidity
  or an unfilled `УСП` takes priority over a volume level and can cause a stop sweep before any
  reaction (p. 99).

### Ambiguities retained as configurable assumptions

The book intentionally teaches chart reading rather than fixed measurements. It does **not** define
the maximum wick/body tolerance for a touch, the allowed time/range/drift of a base, the size of
"a little before" POC, the number/body size of acceptance candles, or an exact rule for whether a
prior near-touch consumes a level. These remain hypotheses to label and test, never hidden facts.

## Course entry families

### A. Trend-break retest

The basic entry is a retest of the break zone, with the stop behind the relevant high/low. If that
stop is too wide, the course suggests using nearby liquidity and/or a good volume level to shorten
it (pp. 21-22). This is a *trend-break* entry; it is not necessarily a retest of a base POC.

### B. Liquidity / POC entry

For a high-timeframe liquidity area, place a limit order slightly before the principal volume and
hide the stop behind the entire liquidity zone. The course suggests an additional buffer of one
quarter of the liquidity-zone height. Entries may be split across two or three volume waves while
the **total** risk remains unchanged (pp. 24, 26).

### C. Early-break / hook entry

Wait until the hook completes, then use a trigger order. The course says the first target should be
before the setup-timeframe trend break and requires at least 1:3 risk/reward due to the risk of a
false signal (p. 18). RSI divergence is an optional supplementary signal, not the entry by itself
(p. 19).

## Levels and traps relevant to Apollo setups

- Levels are zones, not exact prices; a more visible higher-timeframe level is treated as stronger
  than a weak local level (p. 27).
- Local levels include high/low areas, base boundaries, Fibonacci levels, and support/resistance
  touched at least three times. Close, compressed repeated attacks (`поджатие`) suggest a break may
  be forming instead of a bounce (p. 28).
- Mirror/global levels require repeated reactions from both sides over a longer period. The course
  uses them chiefly as additional targets or stop locations rather than standalone entries (p. 29).
- A POC trap is a pass-through without reaction followed by a return from the far side; it is said
  to occur more often on lower timeframes (p. 39). A backtest must allow this loss mode rather than
  assume every first POC touch reacts.

## Where our prior Apollo backtest diverged

The prior `apollo-base-poc-retest` and variable-base v3 implementations correctly attempted causal
base-only POC calculation, first retests, a whole-zone stop plus 25%, 3R minimum, and 1m maker-fill
simulation. They did **not** faithfully encode these source requirements:

1. A hierarchical global versus local trend-break map.
2. Acceptance by impulse/full-bodied candles beyond a break *range*.
3. Distance/room to the next higher-timeframe opposing break or level.
4. A test for liquidity having already been swept or consumed before the entry.
5. Separate entry families: break-retest, liquidity-limit, and hook-trigger were compressed into a
   single base-breakout-first-retest rule.
6. Explicit liquidation evidence; the project has no liquidation-history dataset.

Therefore the past Apollo result rejects only that narrow mechanical proxy. It does not validate or
invalidate the course's full discretionary process.

## Correct next research procedure

Do not tune prior ATR, volume, or base-length thresholds. First create a labelled dataset of at
least 30 source-style chart examples, with the following annotations made before a result is known:

- timeframe and higher-timeframe context;
- global and local break zones;
- whether acceptance occurred and why;
- base start/end candles and base body boundaries;
- POC and internal volume-wave prices;
- whether liquidity was already swept;
- applicable entry family and the actual entry, stop, first target, and opposing-level room.

Then compare the detector against these labels. Only after it agrees sufficiently with the manually
labelled course setups should we backtest a newly frozen version on training data. Reserved
validation and final-test periods must remain unopened.

## Experiment 4: liquidity-sweep reversal v1

This is a separate strategy from the rejected base/POC retest. It is a causal **price-and-volume
sweep proxy**, not an assertion that exchange liquidations were observed.

Frozen BTCUSDT 15m training definition:

1. Build a candidate liquidity pool from at least two confirmed pivot highs or lows over the prior
   96 bars, clustered within 0.15 ATR.
2. Reject a pool that has already been penetrated by 0.15 ATR after its last confirmed pivot.
3. Require the immediately preceding candle to sweep beyond the pool by 0.15 ATR.
4. Require the next candle to reclaim the pool, close in the reversal direction, exceed the prior
   three-bar local structural extreme, and have at least 1.20x prior-20-candle volume.
5. Set the stop 0.25 ATR beyond the sweep extreme. The nearest opposing confirmed pool must offer
   at least 3R; otherwise skip the setup. Maximum hold is 96 bars.

Result: **0 entry decisions / 0 filled trades** over `[2023-08-07, 2025-08-07)`. The 1.5x cost
stress is also zero by construction. This is not evidence of profitability or unprofitability; it
rejects the current fully mechanical conjunction as too restrictive to form an evaluable sample.
Do not loosen its thresholds after this result. The next legitimate step is source-style manual
labelling to determine whether the detector is wrongly excluding visual examples, especially the
course's discretionary notion of a liquidity pool and local/global break.

## Labelled August 4, 2026 examples

The user supplied ETH and BTC 4h screenshots posted on 2026-08-04. The corresponding Binance
hourly candles were read from the reserved final-test period at the user's explicit instruction.
This is qualitative interpretation only, not a performance test; final-test independence is no
longer preserved for Apollo work informed by these examples.

### ETHUSDT

The screenshot's large active 4h box is visually bounded near 1,828 and 1,986, with internal
profile/level references around 1,918 and 1,906. The hourly database confirms a low of 1,827.18
on 2026-08-03 11:00 (database display timezone +03), followed by recovery into the box and an
advance to 1,916.88-1,927.19 on 2026-08-05. This supplies a source-style sequence:

1. higher-timeframe lower liquidity boundary near 1,828;
2. sweep below it to 1,827;
3. lower-timeframe reclaim/structure confirmation, rather than an immediate fade of the wick;
4. target at the next pre-mapped internal/opposing 4h liquidity around 1,906-1,918.

### BTCUSDT

The BTC screenshot's contemporaneous price is near 64,260. It maps to the August 4 hourly range
around 63,900-64,400, between the marked 4h local support near 63,800 and resistance near 65,200.
It is not itself a completed sweep entry. It demonstrates the correct role of the 4h map: define
the 62.3k-63.0k local liquidity box and the next targets/resistance before a lower-timeframe
trigger occurs. A system must be able to say **no trade in the middle of the map**.

## Revised Apollo v2 architecture

The next implementation must not infer both the map and trigger from one 15m pivot window:

1. Construct and persist a 4h liquidity map from isolated bases: boundary zone, exact base-only
   fixed-volume profile, main POC, internal volume waves, freshness/sweep state, and higher-timeframe
   opposing levels.
2. Map each completed 15m candle only to liquidity areas already known from completed 4h candles.
3. At one such area, detect the lower-timeframe sweep, reclaim, and local/global structure sequence.
4. Use the 4h map, not a newly discovered 15m pivot, to choose the target and reject mid-range
   trades or trades with inadequate room.
5. Compare the map and trigger output first against these labelled ETH/BTC examples, then freeze a
   backtest configuration.

## Experiment 5: higher-timeframe liquidity map plus 15m trigger (v2)

V2 was frozen as a broader, map-first proxy. It aggregates the stored 1h candles into completed 4h
candles, maps the nearest causal confirmed pivot-cluster support and resistance from the prior 48
4h bars, then allows a 15m trade only after a sweep of that map level, reclaim, three-bar local
break, 1.20x volume confirmation, and at least 3R to the opposing 4h mapped level. It uses real
1m maker/taker simulation.

Training results across the unselected universe:

| Symbol | Return | Trades | PF | Result |
| --- | ---: | ---: | ---: | --- |
| BTCUSDT | +10.13% | 65 | 1.381 | Profit-target termination |
| ETHUSDT | -8.66% | 30 | 0.417 | Drawdown termination |
| SOLUSDT | -3.03% | 56 | 0.871 | Drawdown termination |
| XRPUSDT | +10.11% | 78 | 1.342 | Profit-target termination |
| BNBUSDT | -8.92% | 37 | 0.433 | Drawdown termination |
| ADAUSDT | -8.48% | 25 | 0.297 | Drawdown termination |
| DOGEUSDT | -9.64% | 17 | 0.000 | Drawdown termination |
| LINKUSDT | -10.01% | 26 | 0.172 | Drawdown termination |

The aggregate independent-account net PnL was -28,498.42. The two winning symbols are a
post-result observation, not permission to select them. V2 is rejected as an all-symbol strategy.
It is closer to the example-driven architecture than v1, but 4h pivot clusters remain an inadequate
substitute for the visually selected base/POC/liquidity boxes in the course screenshots.

## Experiment 6: ordered liquidity sequence (v3)

This distinct v3 freezes the sequence made explicit by the supplementary clips: the mapped 4h
support/resistance must have had no nearby 15m visit during the prior 24 hours; the level is swept;
one of the next four 15m candles reclaims it with a directional body; and a later completed candle
breaks the prior three-bar local range on at least 1.20x volume. It retains the 0.15 ATR sweep,
0.25 ATR structural stop buffer, 3R opposing-map room, and real 1m maker/taker execution. It is
still a pivot-cluster map proxy, **not** the visually selected fixed-profile base in the source.

| Symbol | Return | Filled trades | PF |
| --- | ---: | ---: | ---: |
| BTCUSDT | 0.00% | 0 | 0.000 |
| ETHUSDT | -0.54% | 1 | 0.000 |
| SOLUSDT | -0.13% | 2 | 0.756 |
| XRPUSDT | 0.00% | 0 | 0.000 |
| BNBUSDT | +0.04% | 2 | 1.079 |
| ADAUSDT | -0.25% | 2 | 0.531 |
| DOGEUSDT | -0.52% | 1 | 0.000 |
| LINKUSDT | -0.51% | 1 | 0.000 |

The independent-account aggregate net PnL was -1,904.53 on nine filled trades. This cannot pass
the evidence floor and is not a viable all-symbol strategy. It also should not be "fixed" by
loosening thresholds after seeing the result: the exact automatic interpretation remains an
insufficient-sample proxy, and the valuable next test is manual base/structure labelling.
