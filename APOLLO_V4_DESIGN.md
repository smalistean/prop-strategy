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
   then a distinct lower-timeframe local trend break in the original breakout
   direction. No confirmation means no trade.
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

## Existing code to retain or avoid

The old `apollo-base-poc-retest` correctly contains causal base-only profile
calculation and a whole-zone stop buffer, but it uses a fixed candle-count base
and enters a retest too directly. It is reference code only, not V4 logic.
