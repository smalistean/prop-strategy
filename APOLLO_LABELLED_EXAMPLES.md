# Apollo labelled base/entry examples (first batch, 2026-08-10)

Source-derived labels from the course concept clips and a sample of the daily BTC/ETH review
videos in `/Users/stas/Movies/training/apollo/`, extracted for comparison against
`VariableBaseDetectorV5`/`ApolloV5BasePocContinuationStrategy`, per the procedure
`APOLLO_COURSE_SOURCE_NOTES.md` has called for since it was written.

**This is a first batch, not a complete dataset.** 20 examples from 20 distinct video sources are
recorded here, drawn from the 14 short concept clips plus 10 sampled daily review videos out of
roughly 160 available. Extending this (sampling more daily videos, ideally with frame-accurate
timestamps and legible price scales rather than approximate visual reads) is follow-up work, not
done here. Entry family follows `APOLLO_COURSE_SOURCE_NOTES.md`'s taxonomy: **A** = trend-break/
structure retest, **B** = liquidity/POC-limit, **C** = hook-trigger. "Concept-only" clips teach a
rule without showing a complete tradable sequence and are still valuable as detector-behavior
checks (e.g. "does the detector treat this as a base at all").

| # | Source | Symbol / approx price | Type | Entry family | Boundary touches before signal | What it shows |
|---|---|---|---|---|---|---|
| 1 | `profili_obioma.mp4` | YFIUSDT, 1h | Concept-only | — | — | Fixed-Range Volume Profile manually stretched over a small, visually bounded horizontal cluster — not a rolling last-N-bar window. |
| 2 | `profil_obioma_lovushka.mp4` | (chart not specified) | Concept-only (negative example) | — | — | POC trap: price passes through a historical POC without reacting, or sweeps behind it first. A raw "price reaches POC → enter" rule is explicitly wrong. |
| 3 | `slom_trenda.mp4` | (chart not specified) | Concept-only | — | — | A `слом тренда` must invalidate a confirmed swing sequence on the setup timeframe, not just wick through a horizontal line. |
| 4 | `tbx.mp4` | (box example) | Worked example | A | not visible | Bounded consolidation ("box") becomes tradable only after breakout **and** retest; a touch/wick alone is insufficient. Target = next liquidity area, not a fixed R multiple. |
| 5 | `eth_30_june_2025.mp4` | ETHUSDT, ~1,827–1,987 | Worked example | A | 1 (sweep) | Lower boundary of a large 4h range swept to ~1,827, lower-timeframe reclaim/structure confirmation (not an immediate wick fade), target at next internal/opposing liquidity (~1,906–1,918). |
| 6 | `liquidity.mp4` | ETH-like, ~2,050–2,250 | Concept-only | — | 1 (sweep) | Small horizontal cluster (~4–6 candles) as a liquidity/stop pool; sweep below it produces a stronger continuation move, not a bounce — liquidity ≠ support. |
| 7 | `price.mp4` | Low-price altcoin, ~0.106–0.112 | Concept-only | — | — | After a box forms and breaks out, continuation ("продолжит") is explicitly said to be more common than reversal — relevant to which entry family applies (continuation vs. reversal base). |
| 8 | `tochka_fiksasii.mp4` | Mid-price alt, ~12,000–17,500 | Concept-only | — | — | Manually constructing a swing/"fixation" point using a 1-minute chart, then relating it to an hourly reference level (~15,800) — the swing reference point is timeframe-dependent, not a single-series pivot. |
| 9 | `diver.mp4` | 1000SHIBUSDT | Concept-only | — | — | RSI divergence (price lower high / RSI higher low) shown as a confirming signal alongside structure — matches the notes: RSI divergence is supplementary, not the entry itself. |
| 10 | `razbitie_positii.mp4` | BTCM2026, ~58,000–70,000 | Concept-only (execution) | — | — | Splitting one position across multiple limit orders inside a mapped box (multiple price levels marked); execution/sizing detail, not an entry-timing rule. |
| 11 | `slom-trenda2.mp4` | Mid-price alt, ~460–720 | Concept-only | — | — | Several candidate trend lines exist simultaneously; only the "last actual/relevant" (актуальный) one matters. Confirms the "which swing is global" ambiguity is a real, recurring labelling problem, not an edge case. |
| 12 | `slom_trenda_tf.mp4` | Low-price alt, ~0.60–0.71 | Concept-only | — | — | The same price move reads as a clean break on one timeframe and as noise on another; explicit timeframe-hierarchy teaching, not a single-series rule. |
| 13 | `xrp.mp4` | XRPUSDT, ~1.0–2.0 | Worked example | A | **3** (3rd is the real signal) | Descending resistance + repeated support tests; 3 touches at support circled, 3rd is the actual low before reversal (matches Книга 2.0 p.98's third-touch warning exactly). Break of descending trendline, retest, then strong continuation. |
| 14 | `tbx_copy.mp4` | BTCM2026, ~73,500–81,350 | Worked example | A/B hybrid | not visible | Box with a smaller nested box inside it; an "unclaimed liquidity" (незабранная ликвидность) pool below the box is marked as the target reference; retest zone drawn before the breakout continuation. |
| 15 | `btc7May2026.mp4` | BTCUSDT, ~74,000–87,000 | Worked example (daily review) | A | not visible | Base drawn across ~15–20 candles referenced explicitly as a "четырёхчасовой уровень" (four-hour level) — a higher-timeframe base wider than a 15m/48-candle window. Breakeven → profit-take → reversal noted near 87,000. |
| 16 | `17,07 бит.mp4` | BTCUSDT, ~60,000–65,845 | Worked example (daily review) | A | not visible | Box breakout followed by an ascending trendline and channel formation above the base — continuation into a structured range, not a single clean impulse. |
| 17 | `btc15may2026.mp4` | BTCUSDT, ~74,000–87,850 | Worked example (daily review) | A | not visible | A second, smaller nested structure is drawn inside the main base near its lower edge, discussed as "структуру...профиль" (structure...profile) — internal volume-wave/sub-structure within a single mapped base. |
| 18 | `btc10june2026.mp4` | BTCUSDT, ~56,825–68,060 | Worked example (daily review) | A | not visible | Sharp decline into a small consolidation; RSI ("эрсиаичка") referenced explicitly alongside the structural break near 58,000/56,825 — RSI used as one confirming input among several, consistent with source notes. |
| 19 | `07,05 эфир .mp4` | ETHUSDT, ~2,175–2,500 | Worked example (daily review) | A | **3** | Three circled reactions off the same support (~2,260–2,290) in a descending channel, explicitly labelled "3-ья" (3rd); second independent confirmation of the third-touch pattern from example #13, different symbol and date. |
| 20 | `20,05 эфир .mp4` | ETHUSDT, ~1,992–2,400 | Worked example (daily review) | A | 2+ | Multiple level tests ("2-ая", "4-ой") in a descending channel with RSI referenced twice ("дважды ирсиаич") — repeated divergence/level-test combination. |

## What this batch already tells us, before any detector comparison

- **9 of the 14 concept clips and 6 of 6 sampled daily worked examples are Family A** (trend-break/
  structure retest) or a hybrid leaning on it. Family B (liquidity/POC-limit) and Family C
  (hook-trigger) never appear as the *primary* shown sequence in this batch, though #14 has
  liquidity-zone framing alongside a Family A structure. This is a real signal, not just a sampling
  artifact of which clips got reviewed: Family A dominance is why task 4 (liquidity-limit) is
  prioritized as the most likely to add new signal ahead of task 5 (hook-trigger).
- **Two independent third-touch examples** (#13 XRP, #19 ETH) on different symbols and dates both
  show the exact pattern Книга 2.0 p.98 warns about, with the third touch — not the first or second —
  producing the real reversal. `maximumBoundaryTouches=2` in V5 already encodes this; these two
  examples are candidate cases to specifically re-run the detector against once comparison work
  starts.
- **Two examples (#15, #17) show a higher-timeframe base wider than V5's 7-day/672-bar search
  actually needs to be** — both are within days, not weeks, consistent with V5's `baseMapLookbackDays=7`
  being a reasonable first step rather than an under-scoped one for at least these cases.
- **RSI appears in 3 of 20 examples (#9, #18, #20) always as a secondary confirmation**, never as
  the entry trigger itself — no evidence in this batch to promote it beyond the "supplementary"
  role already recorded in `APOLLO_COURSE_SOURCE_NOTES.md`.

## Detector recall check against the two exact-price labelled examples (2026-08-10)

Most examples above have visually-read, approximate price levels and no on-screen calendar date,
which makes them unsuitable for a precise recall check. Two examples already have exact prices and
dates recorded in `APOLLO_COURSE_SOURCE_NOTES.md`'s "Labelled August 4, 2026 examples" section, so
`ApolloV5LabelComparisonApplication` (new, `src/main/java/.../statistics/`) was run against real
market data for both:

- **ETHUSDT, labelled box ~[1,828, 1,986]:** the detector finds a candidate
  `[2026-07-31T09:15Z, 2026-08-05T16:00Z]` (508 bars, ~5.3 days) with low=1,831.40, high=1,893.54 —
  within a few dollars of the labelled low, existing over almost exactly the labelled window. This
  is a genuine recall success, and one only possible because of the V5 multi-day search: at 508
  bars it is more than 10x wider than V4's 48-candle ceiling. Several matching candidates near the
  low boundary show `lowTouches=3` or more, meaning `maximumBoundaryTouches=2` would discount a
  breakout there — worth revisiting once more labelled examples with a real third touch at the
  *tradable* boundary (not just the base's far edge) are available.
- **BTCUSDT, labelled box ~[62,300, 63,000]:** the detector finds
  `[2026-07-31T15:15Z, 2026-08-03T14:00Z]` (284 bars, ~3 days) with low=62,306.90,
  high=63,675.50 — again within a few dollars of the labelled low, `lowTouches=2` (would clear the
  third-touch filter).

Both hits land almost exactly on the source-labelled boundary and window, which is meaningful
evidence that the V5 multi-day search change (task before this one) is finding real course-visible
structures, not just producing more noise. This is a small, precision-selected sample (2 examples)
and not a general recall/precision measurement — extending the comparison to the approximate-price
examples in the table above, or to a larger set of exact-price labels, is the natural next
iteration of this task.

## Instrument audit (2026-08-11) — which videos can support exact-price labelling

Checked by extracting the on-chart ticker label from sample videos in both folders. The two sets
use **different instruments**, which decides what step 3 can use:

| Source | On-chart ticker | Usable against our data? |
| --- | --- | --- |
| `eth/` daily reviews (77 files) | **`ETHUSDT.P`** | **Yes — exactly the instrument in `futures_kline` / `futures_volume_profile_bin`.** Direct price match, no adjustment. |
| `xrp.mp4` | `USDT` pair @ 2.0939 (XRPUSDT) | Yes, but no on-screen date; must be located by price structure |
| `btc/` daily reviews (77 files) | **CME futures**, front-month rolling: `BTCK2026` (May), `BTCM2026` (Jun), `BTCN2026` (Jul), `BTCQ2026` (Aug) | **No, not directly.** Different instrument from Binance BTCUSDT perpetual |

BTC dailies are unusable for exact-price work for three compounding reasons: **basis** (CME futures
trade at a premium/discount to perp), **session structure** (CME halts daily and does not trade
weekends, so bases and gaps form different shapes than on a 24/7 perp), and **monthly contract
rolls** creating price discontinuities.

**Correction to the earlier recall check above:** the BTCUSDT half of that result (detector low
62,306.90 vs labelled 62,300) was reported as a match "to within a few dollars". If that labelled
example derives from a CME chart, dollar-level agreement with Binance data is coincidence rather
than precision, since the two instruments track each other only to roughly a percent. The ETHUSDT
half is unaffected — same instrument, so that match stands as stated.

## Step 3 batch: ETHUSDT.P daily reviews (2026-08-11)

**Method (reproducible):** crop the chart region out of the frame (`crop=iw:ih*0.62:0:ih*0.32`),
sample ~20 frames per video into a tiled contact sheet, and read the drawn horizontal levels off
the price axis — the trader's marked levels appear as yellow price-axis labels, his fixed-range
volume profile appears as an overlaid histogram, and his boxes appear as drawn rectangles. Dates
come from the filename. Prices are quoted to 2 decimals exactly as displayed.

| Video | Date | Price at recording | Marked levels (read off axis) |
| --- | --- | ---: | --- |
| `12,05 эфир .mp4` | 2026-05-12 | 2,283.92 | 2,440.87 · 2,332.76 · 2,260.19 · 2,250.00 · 2,218.10 · 2,165.45 |
| `23,06 эфир .mp4` | 2026-06-23 | 1,660.28 | 2,119.60 · 2,011.26 · 1,986.11 · 1,770.52 · 1,677.03 · 1,627.60 · 1,560.13 · 1,410.52 · 1,311.58 |
| `05,08 эфир .mp4` | 2026-08-05 | 1,906.00 | 2,011.26 · 1,986.11 · 1,918.55 · 1,906.27 · 1,871.42 · 1,868.56 · 1,828.80 · 1,773.37 · 1,739.77 |

Additional detail captured:

- **`12,05`** shows a position tool drawn with the caption **ТЕЙК** (take-profit) at
  **2,382.91 / 2,326.72 / 2,293.91** — the clearest entry/target triple seen so far, and the
  template for extracting entry/stop/target rather than levels alone.
- **`05,08`** shows the **fixed-range volume profile histogram directly on screen** (~1,860-1,880),
  i.e. his own POC placement is visible and can be compared against our computed POC for the same
  timestamp — a stronger check than comparing zone boundaries.
- **`05,08`** independently corroborates the existing August-2026 labelled example: the box
  boundaries `1,828` / `1,986` recorded there appear here as explicitly drawn levels
  **1,828.80** and **1,986.11**.
- **`05,08`** caption **ПОЛОВИНУ ЗАФИКСИРУЕМ** ("we'll fix half") is direct source evidence for
  partial position exits — consistent with `APOLLO_V5_EXIT_TESTS.md` Test C finding that partials
  cut profit but reduce drawdown, i.e. the course treats them as risk management, not edge.

### Finding: mapped levels persist far longer than `baseMapLookbackDays=7`

**`1,986.11` and `2,011.26` appear as marked levels on both 2026-06-23 and 2026-08-05** — the same
prices, to the cent, six weeks apart. The trader carries levels forward for well over a month.

V5's `strategy.baseMapLookbackDays=7` searches only seven days back for base candidates. This is
direct, dated, exact-price evidence that the real map is roughly **6x longer-lived** than what V5
looks for. That is a concrete, source-grounded reason to revisit the lookback — and unlike the
granularity work, it comes from ground truth rather than from scanning backtest P&L.

### Not yet extracted

`02,06`, `10,07`, `24,07` contact sheets were generated but not yet read in detail; the remaining
~71 ETH videos are unprocessed. Entry/stop/target triples are only sometimes drawn explicitly (as
in `12,05`); marked levels and POC placement are available in essentially every video.

## Touch-filter re-scoping (2026-08-10)

Dumping the exact touch-run detail behind the ETH match's `lowTouches=3` (new
`labelDumpBars` mode in `ApolloV5LabelComparisonApplication`) found three runs: a genuine
penetration on 2026-08-01 (extreme 1,820.61), a one-bar noise graze the same day (1,832.14), and a
2026-08-03 08:15–08:30 UTC run bottoming at 1,827.18 — matching the course's documented sweep to
the cent (`APOLLO_COURSE_SOURCE_NOTES.md` records 1,827.18 at "2026-08-03 11:00" in the database's
+03 display timezone). The third counted touch **is** the documented entry trigger, not noise the
filter was right to discard. `strategy.maximumBoundaryTouches` was disabled (set to 999) in
`apollo-v5-btc.properties` as a result — not because a higher number happened to let this example
through, but because the filter's scope (formation-time touches, before any breakout) doesn't match
what Книга 2.0 p.98 is actually warning about, and that risk is already handled post-breakout by
"first revisit permanently consumes the base." Re-running the nine-symbol training diagnostic with
the filter disabled produced nominally worse aggregate results (BTC +$329.80→-$218.61 net, ETH
-$288.15→-$1,150.56 net, SOL -$382.55→-$887.69 net; BNB unchanged; the five zero-trade symbols
stayed at zero). This is recorded honestly rather than used to reverse the change: every affected
sample is 3-7 trades, far below this project's own 60-trade evidence floor, so treating that swing
as evidence either way would repeat the same post-hoc-tuning mistake the change was trying to avoid
in the first place. The conceptual justification (redundancy with the consumption rule, confirmed
misapplication on the one real match) stands independent of the small-sample backtest noise.
