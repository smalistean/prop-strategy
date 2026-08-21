# Apollo V4 — base POC revisit with continuation confirmation

## Purpose

Replace the rejected pivot-cluster proxy with the course's actual causal
sequence. A POC is derived from a selected horizontal base and is an alert zone,
not a blind entry level.

## Ordered state machine

1. **Completed higher-timeframe base** — a visually/labelled horizontal candle
   concentration. Its boundary is the greatest body high/low; entrance and exit
   candles are excluded.
2. **Base-only fixed-volume profile** — calculate main POC and internal volume
   waves from candles wholly inside those body boundaries.
3. **Accepted breakout** — price leaves the base and holds beyond the relevant
   edge with impulse/full-bodied acceptance candles.
4. **First revisit** — price returns into the POC zone or takes liquidity just
   beyond it. A previously consumed base is invalidated.
5. **Decision after the revisit** — require reclaim of the POC/selected wave,
   then a distinct lower-timeframe swing reversal in the original breakout
   direction. For a long: sweep low → pivot high → higher pivot low → close
   above that pivot high; shorts mirror it. No confirmation means no trade.
6. **Continuation entry** — execute after that structural confirmation, not at
   the POC touch. Stop is behind the entire base/liquidity zone plus one-quarter
   of its height. Target is the next mapped liquidity/internal volume wave,
   subject to sufficient room.

The short setup is the exact mirror image.

## POC lifecycle and historical scope

- A confirmed base starts **fresh**. The first later entry into its POC zone
  changes it permanently to **consumed**, whether price reacts, stops through,
  or merely passes through. A consumed POC cannot generate a second ordinary
  continuation setup; this prevents repeatedly fading an area whose inventory
  has already been cleared.
- The map must search sufficiently far backward for still-fresh bases; bases
  may be weeks or months old. Historical scope is therefore a V4 configuration
  (`baseMapLookbackDays`), independent of the small lower-timeframe confirmation
  windows. We will label and test several declared scopes rather than silently
  impose the current 12-day pivot lookback.

## What V4 deliberately does not do

- It does not define a base as a fixed number of ordinary 15m candles.
- It does not enter merely because price reaches POC.
- It does not infer liquidation from a wick alone.
- It does not reuse Apollo v3 pivot clusters as the POC/base map.

## Implementation order

### V4-A: labelled base/map dataset

Before automation, label representative examples with: base start/end, body
boundaries, breakout direction, principal POC, internal waves, revisit type,
local/global trend break, entry/stop/target, and whether the base was consumed.
Compare an automatic base detector against those labels.

### V4-B: causal map builder

Persist only bases confirmed by completed higher-timeframe candles. Attach base
zone, POC/waves, breakout state, and freshness to each later 15m bar.

### V4-C: lower-timeframe decision strategy

Implement the revisit → sweep/reclaim → local-break state machine and use the
map's next liquidity zone as target. Run it first on labelled examples, then
freeze a configuration for the 15-symbol training universe.

The first code version uses completed 15-minute pivots with configurable
two-candle strength and a 0.25-ATR minimum swing. It deliberately waits for the
right side of each pivot to close, so this confirmation is causal. It is still
only an automatic base-selection diagnostic, not the labelled-map implementation.

## V4.1 evidence from the May–August 2026 video investigation

The later supplied BTC videos add the following durable requirements:

- A map base can remain relevant for days or weeks after its breakout; a
  12–48-candle base ending immediately before the current breakout is only a
  candidate source, not the entire map.
- The map must retain its POC zone and distinct internal high-volume nodes.
  The next opposing node/liquidity level is the first target reference; a fixed
  numerical 3R target is not a course-derived substitute.
- A zone is a decision area. It may reject, range, or pass through. The first
  revisit consumes the ordinary setup whether or not a trade is taken.
- “Слом тренда” is a named prior swing: sweep/reclaim → break a preceding swing
  → retest that broken swing and hold on the intended side. A raw count of recent
  candles is not enough.
- RSI divergence and position splitting are context and execution management,
  respectively; neither is an entry rule for the initial systematic version.

V4.1 therefore adds persistent map state, named-swing retest confirmation, and
volume-node target selection. It must be audited against the video labels before
any performance interpretation.

### V4.1 implementation boundary (2026-08-09)

The implemented map is causal: it detects a mechanically horizontal 15-minute
candidate base, profiles only its completed aggregate trades, publishes it only
after the next completed acceptance candle, and permanently consumes it at its
first later POC-zone touch. While it faithfully implements the lifecycle and
entry sequence above, its *base detector* still begins with a 12-48 candle
candidate. That is an explicit known limitation, not a claim that the videos'
multi-day bases have been fully encoded. The BTC 2026 replay makes no entries,
so the next research task is labelled-base recall (what visible bases were
missed/incorrectly mapped), not threshold changes or validation.

## Existing code to retain or avoid

The old `apollo-base-poc-retest` correctly contains causal base-only profile
calculation and a whole-zone stop buffer, but it uses a fixed candle-count base
and enters a retest too directly. It is reference code only, not V4 logic.
