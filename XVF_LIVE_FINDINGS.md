# XVF — what live execution found

First real orders, 2026-08-18. Six ordered venue pairs, ATOM, $12 a leg, about **30 cents** in total
cost. `XVF_EXECUTION_DESIGN.md` is what the execution path is meant to do and `XVF_V1_SCOPE.md` is what
v1 covers; this is what actually happened when orders reached venues, and it is deliberately separate
because almost none of it could have been found any other way.

Three defects here would each have stranded a real position at full size. All three were invisible in
dry run, and two were invisible in the logs of the live run that contained them.

---

## 1. Binance's user data stream had been dead since April

**Symptom.** A maker leg filled and no hedge fired. `FILLED SELL 25.17` at `20:24:44.962Z` landed on an
account whose user data socket had been open since `20:24:34`, was receiving server pings, and
delivered **zero** order events.

**Cause.** Binance split futures WebSocket traffic into three endpoints and decommissioned the unified
`/ws` and `/stream` URLs on **2026-04-23**:

| Endpoint | Carries |
| --- | --- |
| `/public` | high-frequency market data |
| `/market` | regular market data |
| `/private` | **user data, listenKey** |

An unmigrated connection still completes its handshake, still receives pings, and still delivers
`/public` data. It looks healthy from every angle a client can inspect. It simply never delivers user
data again.

**Evidence.** Same client, same host, same process:

```
LEGACY /ws/btcusdt@aggTrade          frames=0
NEW    /market/ws/btcusdt@aggTrade   frames=19
```

and against one listenKey, both sockets open simultaneously:

```
RESULT: private(new)=1 events, legacy(/ws)=0 events
```

The confusing part, which cost an hour: `bookTicker` **did** deliver 6,810 frames on legacy `/ws`,
because high-frequency data still falls through to `/public`. So the socket looked selectively alive.

**Fix.** `BinanceGateway.WS` → `wss://fstream.binance.com/private/ws/`. One line.

**This had bitten before and been misdiagnosed.** `ChallengeMonitorApplication` carried a comment
blaming the environment - *"it connects there but never delivers a frame, while spot streams do"* - and
worked around it with REST polling. Same bug, wrong cause, recorded as fact for months. Spot was
unaffected because spot is on a separate migration timeline, which is exactly what made the
environment explanation look plausible.

**What to take from it.** A silent stream is indistinguishable from "nothing happened". The entry
design hedges *on the fill event*, so a silent stream means the maker leg fills and the hedge never
fires - a naked directional leg at full size, with nothing in the logs. Venue state must be the
authority; the stream is an accelerator.

---

## 2. Venues disagree about off-grid prices, and the disagreement is what strands a leg

Any price *derived* rather than read from the book - a slippage cap, a mid, an offset - is generally not
on the venue's tick grid.

| Venue | Response to an off-grid price |
| --- | --- |
| Binance | rejects, `-1111 Precision is over the maximum defined for this asset` |
| Bybit | silently accepts |
| Hyperliquid | accepts |

Either behaviour alone would be survivable. The **asymmetry** is what hurts: in a paired trade the
cap `1.4234325` filled the Bybit leg and was rejected three times by Binance, leaving a naked short of
8.4 ATOM. `tickSize` was being fetched by every gateway and used nowhere.

Fixed in `VenueGateway.roundToTick`, applied inside all three gateways so every call site is covered
including `PairedEntryEngine`. Direction follows intent: a **marketable** cap rounds outward so it
stays marketable, a **passive** price rounds inward so post-only stays post-only.

---

## 3. A silent stream plus a retry loop multiplies the hedge

With the stream dead, `awaitFill` returned zero, and the hedge loop treated that as "nothing filled"
and retried. Three attempts, all of which actually filled:

```
20:22:01.745  BUY 8.39 @ 1.428     <- hedge attempt 1
20:22:12.050  BUY 8.39 @ 1.428     <- attempt 2  (~10s apart = the awaitFill timeout)
20:22:22.341  BUY 8.39 @ 1.428     <- attempt 3
                        = 25.17     against an 8.39 maker fill
```

The reported reason - *"IOC accepted but filled nothing, book moved past the cap"* - was wrong on both
counts. Now every crossing path asks the venue by client id before concluding nothing filled, and
reports `STREAM GAP` rather than retrying.

`PairedEntryEngine` never had this bug, because it retries only on rejection. It has the mirror one:
it marks a pair `HEDGED` on IOC **acceptance** without confirming a fill, which under-hedges where this
over-hedged. Still open.

---

## 4. Execution costs, measured from real fills

| | Binance / Bybit | Hyperliquid |
| --- | ---: | ---: |
| Taker fee | **5.0 bp** | **4.5 bp** |
| Spread on ATOM | 7.0 bp | 2.1 bp |
| Round trip per venue, all taker | **~17 bp** | **~9 bp** |

Derived from the fills themselves: `0.0059892 / 11.978 = 5.0bp`, `0.0084 / 11.978 = 7.0bp`.

So a full pair open-and-close costs about **34 bp** CEX-CEX and about **26 bp** CEX-Hyperliquid.

### The number that matters

At the 25.5% realised return in `XVF_V1_SCOPE.md`, a 3-day cycle captures `25.5% x 3/365 ~ 21 bp`.

**All-taker execution costs more than the funding it collects.** Substituting a post-only leg at the
2 bp maker fee brings a pair to roughly `4 + 17 = 21 bp` - about break-even.

The maker leg is therefore not an optimisation. It is the difference between a strategy that pays and
one that does not, and `-DrtCrossFirstLeg=true` must stay a test mode.

Hyperliquid being the cheaper venue to cross reinforces the venue ranking on grounds independent of
funding.

---

## 5. The maker path cannot be proven on demand

Every liquid perpetual measured quotes a **one-tick spread** on both CEXs:

```
ATOM  binance 1.0 ticks   bybit 1.0 ticks
DOGE  binance 1.0 ticks   bybit 1.0 ticks
XRP   binance 1.0 ticks   bybit 1.0 ticks
...   9 of 10 symbols identical
```

A post-only order therefore cannot improve the price - it can only join the back of an existing queue,
and may wait indefinitely. Twelve attempts across two venues produced no fill.

It also means **the chase logic is backwards**: re-placing an unfilled maker every 20s surrenders the
queue position it just built, so `-DrtChases=6 -DrtChaseSeconds=20` is strictly worse than
`-DrtChases=1 -DrtChaseSeconds=120` on a stable book. A chase should trigger when the touch *moves
away* from the resting price, not on a timer. Not yet changed.

The consequence: all six pairs were proven with `-DrtCrossFirstLeg=true`, which exercises the
fill-to-hedge-to-close chain but not the resting path the economics in §4 depend on.

---

## 6. Hyperliquid unified accounts report a perp balance of zero, by design

`clearinghouseState.accountValue` reads `0.0` and `perpAllTime` has never been non-zero, while
`spotClearinghouseState` holds the entire balance. This looks exactly like an unfunded account and is
not one.

Proof, from a round trip on the account:

```
fees + pnl        = 0.009882 + 0.00017 = 0.010052
spot USDC before  = 18.590464
spot USDC after   = 18.580412
difference        = 0.010052            <- exact
```

Perp P&L settles directly against spot USDC. There is no separate perp wallet: spot **is** the
collateral. `userAbstraction` returns `"unifiedAccount"` and the docs confirm *"unified account and
portfolio margin show all balances and holds in the spot clearinghouse state. Individual perp dex user
states are not meaningful."*

Two consequences:

- **Balances** must be read from `spotClearinghouseState`. Reading `accountValue` concludes the account
  is empty and refuses to trade. No code here does; the trap is documented so none starts.
- **Positions** are unaffected. `assetPositions` populates normally - verified live, an open leg
  reported `hyperliquid ATOM 8.41 @ 1.4243` - which is what `positions()` reads. The perp *balance*
  fields are meaningless; the perp *position* fields are not.

`dex` omitted resolves to index 0, which is `null`, the main validator-operated DEX. Correct.

---

## 7. Rotating into a better pair is almost never worth it

Swapping one pair costs **21.5 bp**: 13 bp execution (4 fills at the 3.3 bp blend) plus 8.5 bp of basis
drag realised by the round trip (`-10.4%` annualised over ~122 rebalances).

Improvement required to break even, by how long the replacement is held:

| Hold | Realised delta | As CEX-DEX signal | As CEX-CEX signal |
| ---: | ---: | ---: | ---: |
| 3 d | 26.2% | 54.3% | 116.4% |
| 7 d | 11.2% | 23.3% | 49.9% |
| 14 d | 5.6% | 11.6% | 24.9% |
| 30 d | 2.6% | 5.4% | 11.6% |

The second and third columns exist because signal realises at only **0.482** (CEX-DEX) and **0.225**
(CEX-CEX). A rule written against raw signal spreads - the natural way to write it - overstates the
benefit by two to four times.

Two further reasons not to build it:

1. **The 3-day rebalance already captures it free.** Every cycle rebuilds from the top 20, so a better
   pair arrives within three days without an extra round trip. Rotation pays 21.5 bp to accelerate
   something already coming.
2. **It fights the measured cadence result.** Daily rebalancing loses to 3-day *despite capturing more
   funding*, purely on churn. Discretionary rotation is churn on top of the schedule.

**Close on decay, not on opportunity.** When a held pair's spread falls below the entry floor or flips
sign, the 21.5 bp buys something real - it stops you paying negative funding. Needs a hysteresis band,
or a pair oscillating around the 20% threshold churns repeatedly. Not yet built.

---

## 8. Bracket levels are a property of the pair, not of the leg

Resting exit triggers let a position close while nothing is watching. The obvious construction - size
each leg's band from its own liquidation price - is wrong, and a dry run against live prices caught it:

```
crash to 0.8502 : short leg stays open, long leg CLOSES
```

The two legs sit at different distances from liquidation (0.71 below on the Hyperliquid long, 2.85
above on the Binance short), so the narrower band fires alone, closes the hedge, and leaves the other
leg outright - the exact naked position brackets are installed to prevent, produced by the brackets.

Both legs must share the **tighter** relative distance:

```
binance      ATOMUSDT  band 1.0637 .. 1.7703
hyperliquid  ATOM      band 1.0632 .. 1.7694
  24.93% band, the tighter of 50.56% and 24.93%
```

Also required, and each independently sufficient to break it:

- **Two triggers per leg, four per pair.** One per leg is the arrangement that strands a leg.
- **`reduceOnly` on all four**, or a trigger firing on an already-flat leg opens a new position.
- **Mark price, not last**, so one venue's wick cannot unwind a hedged pair.
- Venues report "no liquidation risk" as `"0"` (Binance), `""` (Bybit) and `null` (Hyperliquid).
  Parsing any of them as zero yields the widest possible band instead of none.

Brackets are a brake, not an exit. Every firing costs a full round trip of basis drag to close a
position that was doing nothing wrong, so the band is set wide enough that ordinary volatility never
reaches it. They exist for the 2.1% of legs that liquidate at 1x, and for when the process that should
have rebalanced them is not running.

---

## 9. Exits cannot give up, and entries can

The exit reuses the entry machinery - rest post-only on the thinner venue, hedge on the fill - with one
difference that is not cosmetic.

An entry whose maker never fills is a missed opportunity, so it cancels and forgets. An exit whose
maker never fills **still holds the position**, and the caller has been told it is going away.
`PairedEntryEngine.close` therefore cancels and then crosses, re-reading the venue for what remains
because the cancel can race a fill. Paying 5 bp is the point.

The same asymmetry shapes `XvfReconciler`: it **only ever reduces**. A leg the book wants but the
account lacks is reported, never opened, because opening one leg unpaired is the failure the whole
system is arranged to avoid. A wrong-side holding closes to flat rather than flipping in one order,
for the same reason.

---

## 10. Still unproven

| | Why it matters |
| --- | --- |
| The maker path | §4 - the economics need it, and §5 says it cannot be summoned on demand |
| Trigger order placement | geometry is tested, the wire format has never reached a venue. Expect at least one rejection on first contact, as the tick bug was |
| `XvfReconciler` end to end | plan/apply are unit-tested; the Postgres book path is not exercised |
| `PairedEntryEngine` marking `HEDGED` on acceptance | under-hedges where §3 over-hedged |
| Hyperliquid as the resting venue for a real maker fill | it has crossed live, never rested |

Six pairs proving the crossing path is not the same as proving the strategy. What was demonstrated is
that orders reach venues, fills reach the system, hedges fire from them, and both legs close - which is
the plumbing v1 exists to test, and which three separate defects would have prevented a week ago.
