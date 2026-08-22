# XVF — what live execution found

First real orders, 2026-08-18, continued 2026-08-19. Six ordered venue pairs, ATOM, $12 a leg, well
under a dollar in total cost. `XVF_EXECUTION_DESIGN.md` is what the execution path is meant to do and `XVF_V1_SCOPE.md` is what
v1 covers; this is what actually happened when orders reached venues, and it is deliberately separate
because almost none of it could have been found any other way.

Four defects here would each have stranded a real position at full size. All were invisible in dry
run, three were invisible in the logs of the live run that contained them, and one - §3b - was the fix
for two of the others.

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

## 3b. The fix for §1 and §3 was itself checking the same broken source

The answer to a silent stream, adopted after §3, was: before concluding nothing filled, ask the venue
by client id. On Hyperliquid that check read the filled quantity from `cumulativeFilled` - the local
map the `userFills` websocket populates.

So it was not a second opinion. It was the same opinion, asked twice, and a fill the stream missed
produced a confident zero from both. On 2026-08-19 that left a live short of 8.5 ATOM while the
harness printed `IOC filled nothing on hyperliquid` and moved on. Only the final position sweep caught
it.

The venue had been unambiguous the whole time:

```json
{"status": "order",
 "order": {"order": {"oid": 519515305389, "origSz": "8.5", "sz": "0.0"},
           "status": "filled"}}
```

`origSz - sz` is what traded, and is correct for a partial, a completed order and an IOC whose
remainder was cancelled alike. `HyperliquidGateway.filledFrom` now reads exactly that, pinned by a
test built from the response above.

Binance (`executedQty`) and Bybit (`cumExecQty`) always read the response. Only Hyperliquid short-
circuited to local state, which is why five of six pairs passed and the sixth stranded a leg.

**The general rule this earns:** a fallback that shares a data source with the thing it is checking is
not a fallback. Both §1 and §3 were failures to hear about a fill; this was a failure to ask anyone
else.

---

## 4. Execution costs, read from the fee rows rather than the fee schedule

Every figure below is the venue's own commission on a real fill, divided by that fill's notional. The
published schedule was wrong about two of the three.

| | Binance | Bybit | Hyperliquid |
| --- | ---: | ---: | ---: |
| Taker fee | **4.50 bp** | **10.00 bp** | **4.50 bp** |
| Maker fee | - | **3.60 bp** | - |
| Published non-VIP taker | 5.0 bp | 5.5 bp | 4.5 bp |
| Spread on ATOM | 7.0 bp | 7.0 bp | 2.1 bp |

Two surprises, in opposite directions.

**Binance is cheaper than the schedule**, because commission now settles in BNB: the fee row reads
`0.00000896 BNB` rather than a USDT amount, which at 601.77 is `0.005392` on `$11.99` - **4.498 bp**,
exactly the 10% BNB discount. `XVF_STRATEGY.md` §7 lists this as deferred and worth about $21/yr. It
is not deferred; it is on.

**Bybit is nearly double the schedule.** `feeRate` comes back as `0.001` - 10.00 bp taker and 3.60 bp
maker, against a published non-VIP 5.5 / 2.0. That is not a tier explicable from the outside and may
be an account setting rather than a real rate, but it is what the fills are charged.

So the cost of a pair, fees only, open and close, all taker:

| Pair | Fees |
| --- | ---: |
| binance <-> hyperliquid | **18 bp** |
| binance <-> bybit | **29 bp** |

### The number that matters

At the 25.5% realised return in `XVF_V1_SCOPE.md`, a 3-day cycle captures `25.5% x 3/365 ~ 21 bp`.

**A Bybit-legged pair costs more in fees alone than the funding it collects**, before a single basis
point of spread. Five of the eight candidates in the 2026-08-19T05:00 book have a Bybit leg.

Even the cheap pairing only works with a resting leg. All-taker binance-hyperliquid is 18 bp of fees
plus roughly 9 bp of spread against 21 bp of funding; moving one leg to post-only is what turns that
positive. The maker leg is not an optimisation, it is the difference between a strategy that pays and
one that does not, and `-DrtCrossFirstLeg=true` must stay a test mode.

Hyperliquid being both the cheapest to cross and the tightest quoted reinforces the venue ranking on
grounds entirely independent of funding.

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

## 10. A funding capture, held across a real settlement, and what basis divergence cost it

**The trade.** 2026-08-19, hyperliquid ACE short (rate -0.0341%, hourly) against binance ACEUSDT long
(rate -0.1758%, 8h, stamp at 08:00Z). Projected net at the stamp: +0.1416% on $12/leg, about
$0.017 - the rate check run minutes before entry, since rates move. Hyperliquid rested one tick
inside a 2-tick book (`-DrtImproveTicks=1`) and filled on the third chase - the first proven resting
maker fill in this entire test campaign, every earlier attempt having either crossed the first leg or
failed outright on a one-tick book (§5). Binance crossed to hedge. Held about 15 minutes across the
stamp, then closed the same way in reverse.

**Full settlement, from the actual fills, funding rows and fee rows - not projected:**

| Leg | Price P&L | Funding | Fees | Net |
| --- | ---: | ---: | ---: | ---: |
| binance (long) | -0.19834 | +0.01869 | -0.01068 | **-0.19032** |
| hyperliquid (short) | +0.10796 | -0.00362 | -0.00357 | **+0.10077** |
| **Total** | | | | **-0.08955** |

Net funding alone: **+0.01508**, close to the +0.0170 projected. The funding mechanism worked
exactly as designed. The loss came from somewhere else entirely.

**Basis divergence, not funding, drove the result.** Over the ~15-minute hold:

```
binance ACE:      -1.655%
hyperliquid ACE:   -0.902%
divergence:        -0.753 percentage points
```

Both venues sold off together; Binance sold off harder. The position was long the leg that fell more
and short the leg that fell less, so the two price P&Ls did not cancel the way a delta-neutral pair
is supposed to. That single divergence, on ~$12 notional, is effectively the whole loss.

**Not leverage.** Binance was on 20x - an account default, never set by any code path here -
Hyperliquid on 3x. Price P&L on a perpetual is `quantity x price change`; leverage does not appear in
that formula, only in the margin posted to hold a given quantity. The Binance loss of -0.19834 is
exactly `53.75 x (0.21932 - 0.22301)` and would be identical in dollars at 1x - only the margin
behind it would move, from $0.59 to $11.87. Neither leg came near liquidation (Binance reported
`liquidationPrice: "0"` at $12 notional on 20x).

**Not the quantity mismatch either, though it looks like a candidate.** The legs were 53.75
(binance) against 53.98 (hyperliquid), a 0.43% gap. Splitting the total price P&L into a basis term
(at the average of the two quantities) and a mismatch term (at the quantity difference):

```
basis-divergence term  : -0.09103   (100.7% of the loss)
quantity-mismatch term : +0.00065   (-0.7% of the loss)
```

The mismatch contributed under a tenth of a percent of notional, in the direction that slightly
reduced the loss. It is not the cause - but it is not unrelated either. `crossToHedge` sizes the
taker leg to match USD notional at the moment of hedging (`usd = makerFilled x makerMid`,
`quantity = usd / takerMid`, both mids read live) rather than a fixed unit ratio - correct for
contract-multiplier differences like PEPE vs 1000PEPE, but it means any divergence already present
in the few seconds between the maker fill and the hedge computation gets baked straight into the
unit count. The 0.43% gap is consistent with the same divergence that later widened to 0.753 points -
a symptom of the cause above, not a second cause.

**Is this ignorable at the strategy's real 3-day cadence?** `XVF_STRATEGY.md` has already measured
this exact phenomenon, and the direction is unambiguous: basis drag *shrinks* the longer a pair is
held, because divergence mean-reverts given time - `-22.6%` at daily rebalancing, `-10.4%` at three
days, `-3.4%` weekly, `-1.3%` at fourteen. A 15-minute hold is shorter than the "daily" figure in
that table by two orders of magnitude, so this result sits at the extreme worst-duration end of a
spectrum whose entire point is that duration helps. Reading it as "the strategy loses 0.75% every
3-day cycle" would be wrong - a 15-minute snapshot cannot measure a 3-day outcome, and linearly
extrapolating it contradicts the strategy's own backtested economics.

What it does establish, first-hand rather than from a backtest: the mechanism is real, and it can
bite within minutes on a thin, chosen-for-its-spread symbol like ACE - exactly the class of coin this
strategy selects for. At the strategy's real position size (`legNotional = capital x LEG_LEVERAGE /
(POSITIONS x 2)`, $250 at $10,000 capital against this test's $12), the same percentage move is
roughly $1.87 rather than $0.09 per pair. Across a 20-position book an idiosyncratic divergence like
this one should average out; a correlated one - a venue-wide dislocation hitting every pair at once -
would not, and is the tail risk `XVF_STRATEGY.md` already flags under adverse selection.

What would actually answer the question: holding a pair the real 3 days, through `XvfReconciler`
rather than the harness, on binance<->hyperliquid where the fee economics are already favourable.
Not yet run.

---

## 11. Still unproven

| | Why it matters |
| --- | --- |
| The maker path at real notional, on a one-tick book | proven once at $12 on a 2-tick book (ACE, hyperliquid) with one tick of price improvement - §10. Every other liquid perp measured quotes one tick, where improvement is impossible (§5) |
| Trigger order placement | geometry is tested, the wire format has never reached a venue. Expect at least one rejection on first contact, as the tick bug was |
| `XvfReconciler` end to end | plan/apply are unit-tested; the Postgres book path is not exercised |
| `PairedEntryEngine` marking `HEDGED` on acceptance | under-hedges where §3 over-hedged |
| Basis divergence over a real 3-day hold | §10 measured it at 15 minutes, which the strategy's own research says is the worst-case duration for this effect, not a representative one |
| Leverage set per-account rather than per-strategy | tonight's ACE pair ran 20x on one leg and 3x on the other, neither matching `LEG_LEVERAGE=1.0`. Harmless at $12 notional; not designed-for at real size |

Six pairs proving the crossing path, and one proving the resting path once, is not the same as proving
the strategy. What has been demonstrated is that orders reach venues, fills reach the system, hedges
fire from them, both legs close, and funding settles as designed - which is the plumbing v1 exists to
test, and which four separate defects would have prevented a week ago.

---

## 12. A live-only scan of five more venues, and what it isn't safe to act on yet

**Why this got asked.** Twelve hours into the current 20-pair book, realized funding across 17
candidates was running at roughly half of what each one's entry-time trailing spread projected
(§ live check, 2026-08-20). That prompted the question of whether a wider venue universe would
surface better candidates than the current three.

**What's already collected vs. what a real comparison needs.** `perp_funding_all` already carries
funding rows for bitget (since 2026-05-15), gate (since 2026-07-14) and okx (since 2026-05-11) -
`scripts/xvf-refresh.sh` has been importing all three daily. But `XVF_V1_SCOPE.md`'s ten-combination
venue study (`scripts/analysis-venue-sets.sql`) depends on historical *kline* volume to enforce the
$500k weekly floor, and **no kline table exists for bitget, gate, okx, mexc or kucoin** - only
`binance_perp_kline`, `bybit_perp_kline`, `hyperliquid_perp_kline` and `dydx_perp_kline` do. Skipping
the floor was already shown to produce the wrong answer once, on dYdX (82.2% untradeable legs looked
like the best venue, unfiltered). The real comparison for these five venues needs a kline importer
built first - not done, not started.

**What was done instead: a live-only snapshot, today's numbers only.** Fetched current funding +
24h volume directly from bitget, gate, mexc, kucoin (one bulk call each) and okx (bulk ticker for
volume, per-symbol funding calls only for bases clearing $500k/week), normalized bases the same way
`normalise_perp_base` does, and compared against binance/bybit/hyperliquid's current rates. Script:
`scripts/xvf-position-snapshot.py`'s sibling for this was written ad hoc at
`/private/tmp/.../scratchpad/new_venue_scan.py` (session-scoped, not yet promoted into `scripts/`).
This is a snapshot, not a backtest - no history behind it, no validation that the volume holds up
over a week the way the $500k floor is supposed to guarantee.

**The finding that matters more than any spread number.** Several of the highest-spread results are
not crypto. **SKHYNIX** and **KODEX200** are a real Korean chipmaker and a real KOSPI200 ETF; **ZHONGJI**
was already caught once this session as a Bybit stock listing (§ ON Semiconductor collision) and
showed up again here via KuCoin. SKHYNIX carried $7.2B and $4.2B in "weekly volume" on OKX and Gate -
no genuine funding-arb altcoin trades at that scale, which is itself the tell. HANA, CXMT and UNITREE
(a real humanoid-robotics company) are suspect for the same reason and unverified either way. This is
the ON Semiconductor collision (§ ticker collision write-up) at larger scale across more venues, with
no equivalent of Bybit's `symbolType` field checked yet on any of the five - meaning **none of this
scan's numbers should be trusted or acted on until each venue's instrument-type field is found and
filtered on**, the same way `BybitGateway.requireCryptoPerp` does now.

Filtered by hand to names confirmed as genuine crypto, the real incremental candidates were far more
modest than the headline numbers: ACE, COTI, RVN, PORTAL, HFT, CARV, DOS, RED. Worth a real look later;
none of it is a reason to move quickly.

**Open questions, unresolved:**
- Does okx / bitget / gate / mexc / kucoin expose a field like Bybit's `symbolType` to filter
  non-crypto listings automatically? Not checked on any of them.
- Is MEXC's futures API actually restricted from placing trades, as recalled but not verified? If so
  it's disqualified as an execution venue regardless of what its numbers show.
- Is building a kline/volume importer for any of these five venues worth the engineering cost, given
  the collision-filtering problem has to be solved first either way?
- Which specific field(s) on each venue's instruments endpoint distinguish a genuine perpetual from a
  stock/ETF-tracking product wearing a crypto-looking ticker - unknown for all five.

---

## 13. Hyperliquid can force-close a profitable leg, and four bugs that compounded it (2026-08-22)

**The risk.** Hyperliquid's auto-deleveraging (ADL) backstop can force-close a position that is
*winning*, with no warning and no order from this system, to cover a liquidation elsewhere on the
book that the order book itself couldn't absorb. It is not this account being at risk - margin can be
perfectly healthy - and it is not a bug in `PairedEntryEngine`. Measured live: a 675-unit CASHCAT
short, entered 2026-08-20 20:00:53Z, was closed by Hyperliquid at 2026-08-22T05:11:35Z with
`dir: "Auto-Deleveraging"` and `liquidation.method: "backstop"`, realizing +$31.05 - a genuine profit,
forced closed anyway, because CASHCAT crashed roughly 66% intraday (24h range 0.051-0.152) and
liquidated someone else who was on the losing side of that move. This left the paired Bybit leg
naked with nothing watching it; nothing in this system currently detects an ADL event, only a manual
or scheduled position snapshot happening to notice a leg count changed. No mitigation exists yet -
this is a real, unaddressed risk for any venue combination that includes Hyperliquid, not something
this session's fixes below touch.

**What compounded it.** The naked leg above should have been a single, contained incident. Instead it
recurred twice more the same day, and each recurrence traced to its own separate bug, found and fixed
live:

| Bug | Effect | Fix |
|---|---|---|
| `V21` migration edited after being applied locally | Every importer calling `DatabaseMigrator.migrate()` failed silently for over a day; `perp_funding_all` went stale, ranked candidates collapsed from ~20 to 6 | `flyway repair`; new rule - never edit an applied migration, write a new one instead |
| Entry loop counted "already open" only by walking today's ranked book | A retained pair whose signal decays below threshold becomes invisible to slot-counting even with fresh data - 11 of 19 held bases were missing from one day's book - so "add one" tried to open far more than one | Seed `slotsFilled` from live positions across all venues up front, not from the walk |
| `BybitGateway.positions()` had no `limit` param | Bybit's default 20-row page silently dropped the 21st position (measured: BNT) from every caller - the entry loop's already-open check, `closeAllMaker`, `reconcile` | Paginate fully via `cursor`/`nextPageCursor` |
| `hedge()` never cancelled the resting maker on `UNHEDGED_ALERT` | A maker whose hedge just failed kept resting and kept filling - CASHCAT grew to 992 units naked before anything stopped it, well past its ~830-900 unit target | Cancel the resting maker in the same call that sets `UNHEDGED_ALERT`, not at the next chase/deadline check |

**What's still open.** Bybit's margin was down to $5.16 available (99.71% initial-margin
utilization) by the second CASHCAT recurrence - both ONG's maker order and CASHCAT's hedge failed for
this reason, not a code defect. The entry loop still sizes against target notional and a participation
cap; it never checks a venue's actual free margin before attempting an order, so it can walk into an
exhausted venue and only find out via a rejected order after the other leg has already filled. Proposed
but not built: check `availableCapital()` per venue before attempting a candidate that needs it.
