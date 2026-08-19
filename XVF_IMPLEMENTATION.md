# XVF — implementation specification

How the cross-venue funding strategy is actually built and run: processes, schedules, order types,
timing, sizing, and failure handling.

`XVF_STRATEGY.md` says **what** the strategy is and what the measurements were.
This document says **how it executes**. Where the two disagree, this one describes the code as
written and flags the disagreement in §12.

**Status: dry-run only.** All three v1 venues (Binance, Bybit, Hyperliquid) are wired and verified
live; dYdX is excluded on measurement, not unwired. **Exits are not implemented — this is the only
remaining blocker to a live trade.** See §12 before running anything with `-DxvfDryRun=false`.

---

## 1. The position

For one coin, held simultaneously:

```
SHORT perpetual on the venue paying MORE funding
LONG  perpetual on the venue paying LESS funding
equal notional, 1x per leg
```

The short leg receives funding, the long leg pays it, and the difference is the return. Price
exposure cancels because both legs reference the same asset. Twenty such pairs, equal weight.

---

## 2. Process architecture

Three separate processes. They do not share memory and communicate only through PostgreSQL.

| Process | Entry point | Cadence | Sends orders |
| --- | --- | --- | --- |
| **Data refresh** | `scripts/xvf-refresh.sh` | daily, launchd 06:45 | no |
| **Observation export** | `scripts/xvf-funding-export.sh` | hourly, launchd `:20` | no |
| **Signal / reporting** | `XvfSignalApplication` | on demand | no |
| **Execution** | `XvfExecutionApplication` | every 3 days | yes, if `-DxvfDryRun=false` |

### Why three and not one

The refresh walks six venue APIs and takes minutes; the execution path must react to a fill inside a
network round trip. Putting them in one process means a REST backfill can block an order.

They are also separable by risk: the first two cannot lose money, so they can be scheduled,
restarted and left unattended. Only the third needs supervision.

### Why not a persistent daemon

Funding is checked hourly at most, positions turn over every three days, and the reaction time that
matters — maker fill to hedge — is inside a single run. A process that sleeps between rebalances
holds nothing that a restart could not rebuild from the database, and a daemon that dies silently
between rebalances looks identical to one that is working.

The execution process is therefore short-lived: it opens streams, places the book, waits for the
pairs to hedge, reports what is still exposed, and exits.

---

## 3. Funding schedules — measured, not assumed

This is the constraint that sets every time in §4. Binance intervals are as declared by
`GET /fapi/v1/fundingInfo` on 2026-08-15; the rest measured over the trailing 14 days in
`perp_funding_all`:

| Venue | Interval | Symbols | Stamp times (UTC) |
| --- | --- | ---: | --- |
| **dYdX** | 1 hour | 296 | every hour, `HH:00` |
| **Hyperliquid** | 1 hour | 232 | every hour, `HH:00` |
| **Binance** | **1 hour** | **1** | **every hour — dynamic, see below** |
| **Binance** | 4 hours | 444 | `00 04 08 12 16 20` |
| **Binance** | 8 hours | 308 | `00 08 16` |
| **Bybit** | 4 hours | 407 | `00 04 08 12 16 20` |
| **Bybit** | 8 hours | 358 | `00 08 16` |

Two consequences that drive the design:

**The CEX leg is always the binding stamp.** dYdX and Hyperliquid pay every hour, and every CEX
stamp falls on an hour. So a pair entered before a Binance or Bybit stamp collects on *both* legs in
the same minute. There is no schedule where the DEX leg is missed and the CEX leg is caught.

**The majority of the CEX universe is 4-hourly, not 8-hourly.** 444 of 753 Binance symbols and 407
of 772 Bybit symbols pay every four hours. That halves the value of any single payment, and the
hourly tier divides it by eight again:

| Interval | Payments/year | One payment on a 100%-annualised name |
| --- | ---: | ---: |
| 8 hours | 1,095 | 9.1 bp |
| 4 hours | 2,190 | 4.6 bp |
| 1 hour | 8,760 | 1.1 bp |

Against a round trip of roughly 13bp across four fills, a 4-hourly position on a 100% name needs
about three payments to cover its own entry and exit. At a 3-day hold it gets eighteen.

### Binance intervals are dynamic, and they track exactly what XVF selects

A Binance symbol's funding interval is not a fixed property. Binance shortens it when funding runs
extreme and lengthens it again when it settles. Only one symbol (COTIUSDT) is hourly right now, but
over the trailing 30 days **12 distinct symbols spent at least one full day on hourly funding**:

| Symbol | Days hourly | Window |
| --- | ---: | --- |
| COTIUSDT | 17 | 2026-07-28 → 08-13 |
| DEXEUSDT | 16 | 2026-07-22 → 08-06 |
| ERAUSDT | 15 | 2026-07-23 → 08-06 |
| ESPORTSUSDT | 10 | 2026-07-20 → 07-29 |
| TUSDT | 8 | 2026-07-17 → 07-24 |
| 1000XECUSDT | 6 | 2026-07-17 → 07-22 |
| BANKUSDT, GWEIUSDT | 5 each | July |
| CATUSDT, PROMUSDT, ACEUSDT, RIFUSDT | 1–2 each | July–August |

This is not a curiosity at the edge of the universe. Split the 734 Binance symbols by whether they
ever went hourly and compare peak trailing-7-day funding:

| Cohort | Symbols | Avg peak annualised | Max | Share exceeding the 20% entry threshold |
| --- | ---: | ---: | ---: | ---: |
| Never hourly | 722 | 51% | 1,850% | 37.1% |
| **Went hourly** | **12** | **1,199%** | **4,245%** | **100.0%** |

Binance shortens the interval under precisely the condition XVF screens for. Every symbol that went
hourly would have entered an XVF book, against 37% of the rest, and the cohort's average peak is 23×
higher. `ACEUSDT` sits at rank 2 in the current book and ran hourly on 2026-08-08/09.

Three consequences:

1. **The stamp hours above do not hold for the top of the book.** The symbols XVF ranks highest are
   disproportionately the ones moved off the `00/04/08/…` grid onto every hour. Timing must be
   resolved per symbol at signal time, not read off a static schedule.
2. **`fundingIntervalHours == 1` is a free live marker.** It is Binance stating that this symbol is in
   a funding dislocation, published forward-looking through a public unauthenticated endpoint. The
   signal ignores it entirely today — §12 item 14.
3. **The trailing-sum arithmetic is unaffected.** `sum(funding_rate)` over 7 days is the realised
   total whatever the interval, and `× 365/7` annualises it correctly. Interval changes break the
   *schedule* assumptions, not the *rate* ones.

---

## 4. Timing — what happens at what time

### The rebalance clock

Rebalance every **3 days** (`XvfConfig.REBALANCE_DAYS`), read as "2 to 5 days" — 3 is a sampled
peak, not a known optimum. 72 hours divides evenly by 1, 4 and 8, so a 3-day cadence always lands on
a stamp boundary for every venue.

Anchor the rebalance to a stamp hour in `{00, 04, 08, 12, 16, 20}` UTC so both the 4h and 8h CEX
universes are covered when the hour is `00`, `08` or `16`.

**But resolve the hour per symbol, not per book.** Per §3, a Binance leg may be on a 1h interval, and
the symbols XVF ranks highest are the ones most likely to be. Read `GET /fapi/v1/fundingInfo` at
signal time and take each symbol's `fundingIntervalHours`:

| Leg's interval | Next stamp | Effect on entry |
| --- | --- | --- |
| 1h (dYdX, Hyperliquid, some Binance) | the next `HH:00` | any hour works; no waiting |
| 4h | next `HH ∈ {00,04,08,12,16,20}` | up to 4h wait if the cycle is missed |
| 8h | next `HH ∈ {00,08,16}` | up to 8h wait |

The binding stamp for a pair is the **longer** of its two legs' intervals, since the DEX side pays
hourly regardless. A Binance-8h/dYdX pair can only be timed onto `00/08/16`; a Binance-1h/dYdX pair
can be entered in any hour, which is the easier case and applies disproportionately to the top of the
book.

### The entry window within the hour

Measured on 47M Binance 1-minute bars, 1-minute range and trade count by offset from the funding
stamp:

| Window | Minutes sampled | Avg range (bp) | Avg trades |
| --- | ---: | ---: | ---: |
| −60..−31 | 2,169,044 | 26.2 | 1,293 |
| −30..−11 | 1,466,200 | 25.1 | 1,215 |
| −10..−4 | 513,170 | 23.4 | 1,107 |
| **−3..−1** | 219,930 | **21.9** | **1,033** |
| **STAMP** | 73,310 | **34.8** | **1,869** |
| +1..+3 | 219,930 | 30.7 | 1,543 |
| +4..+10 | 513,170 | 27.8 | 1,359 |
| +11..+30 | 1,466,200 | 26.2 | 1,267 |
| +31..+59 | 2,156,247 | 25.2 | 1,209 |

The minutes before a stamp are the calmest of the hour — range falls monotonically from 26.2bp an
hour out to 21.9bp in the last three minutes, 16% below baseline, with the lowest trade count. The
stamp minute is the worst at 34.8bp and 81% more trades, decaying over the following 30 minutes.

Entering just before a stamp is therefore both the cheapest moment and the one that collects
immediately. Both effects point the same way, so the rule is unambiguous:

| Time (UTC) | Action |
| --- | --- |
| `HH:45` | Run `xvf-refresh.sh` if it has not run today. Compute the book. |
| `HH:50` | Start execution. Streams open before any order is placed. |
| `HH:57`–`HH:59` | **Place orders.** Cheapest window of the hour. |
| `HH:00` | Funding stamps on both legs. Position is already on. |
| `HH:00`–`HH:10` | **Place nothing.** 34.8bp then 30.7bp. |
| `HH:10`+ | Anything not filled by now waits for the next stamp hour rather than chasing. |

`HH` is a stamp hour for the CEX leg. The three-minute window is narrow on purpose: it is the
measured minimum, and widening it to ten minutes gives back most of the advantage (23.4bp).

**This window is Binance-only.** Bybit's stamp schedule was not separately measured, and the range
proxy conflates spread with genuine drift. The 127 symbols with 1-minute data skew more liquid than
XVF typically selects.

### Mismatched cadence between the two legs

**Decision: not implemented. The entry rule stays uniform - place in the pre-stamp window, same for
every pair.** Recorded here because the reasoning is not obvious and the conclusion is narrower than
it first appears.

Two legs of a pair rarely share a funding cadence. Measured over the same 146 weeks:

| Pair type | Share | Long leg has a positive rate (you pay it) |
| --- | ---: | ---: |
| CEX-DEX, cadence always differs | **65.7%** | 33.0% |
| DEX-DEX, both hourly | 17.4% | 40.5% |
| CEX-CEX, cadence may match | 16.9% | 14.5% |

**Entry timing matters in proportion to the slower leg's cadence.** Over a 72-hour hold, entering just
before a stamp rather than just after is worth one extra payment:

| Leg cadence | Stamps in 72h | Value of catching one more |
| --- | ---: | ---: |
| 8 hours | 9 | **+12.5%** of that leg's funding |
| 4 hours | 18 | +5.9% |
| 1 hour | 72 | +1.4% |

So the slow leg is the only one worth timing around; an hourly leg catches whatever hour you enter
before regardless. This is already what the pre-stamp window does, which is why no extra logic is
needed for it.

**Stamps coincide, so a single payment cannot be isolated - but that is not what the trade needs.**
Every CEX stamp falls on the hour (`00 04 08 12 16 20` or `00 08 16`) and dYdX and Hyperliquid stamp
at **every** `HH:00`, so at any CEX stamp both legs pay in the same second. It is both payments or
neither. Declining **both**, by entering after that instant or exiting before it, is exactly the trade:
you give up a small hourly payment to avoid a larger 4h or 8h one.

The per-event sizes are what make that attractive in principle. One payment is
`annual_rate / payments_per_year`, so at 100% annualised an 8h payment is 9.1bp against an hourly
payment's 1.1bp - **eight times larger**. So the net at a coincident stamp is negative whenever
`rate_short < k x rate_long` (k = the cadence ratio), while the entry signal only requires
`rate_short > rate_long`. A perfectly sound position routinely has negative stamps.

That yields a clean rule: **be in the position only for positive-net stamps**, acted on at entry and
exit where it is free. Mid-hold negatives have to be absorbed, since dodging one costs a 13bp round
trip.

**Measured, it is worth almost nothing.** Simulated at hourly granularity over the 2,656 pairs the
strategy actually selected, each with its real annualised rate and real cadence, across 72-hour holds
and all 24 entry phases:

| | Fixed exit (scheduled rebalance) | Fixed hold (72h from entry) |
| --- | ---: | ---: |
| Baseline funding per hold | 1.289% of leg notional | 1.289% |
| With timed entry and exit | 1.294% | 1.293% |
| Gain | **+0.37% of funding** | +0.31% |
| At 19% gross | **+0.070% of capital/yr** | +0.060% |
| On $10,000 | **~$7/year** | ~$6/year |

Whether the exit is schedule-anchored barely matters, which removes the one concern that looked
capable of deciding it.

Three diagnostics explain the size. Only **26.8%** of pairs have any negative-net stamp at all; across
those pairs only **10.7%** of stamps are negative; and the rule delays entry in just **20.1%** of cases,
by a mean of 2.4 hours. One stamp is ~1.4% of a 72-hour hold, so moving across one or two changes
little.

**Not implemented.** $7/year against the complexity of per-pair stamp arithmetic in the execution path
is not a trade worth making, and it would have to be re-verified against the pre-stamp entry window in
§4 - which is worth 4.3bp of range per fill and points the other way when the rule says enter *after* a
stamp.

**A negative net is a closing signal, not a timing one.** The trailing signal already handles cadence
correctly - `sum(funding_rate)` over 7 days sums actual payments, 168 for an hourly leg and 21 for an
8h leg - so the annualised spread is cadence-correct by construction. Persistent negative stamps mean
rates moved against the position since entry, which belongs to the hysteresis rule (§12 item 7,
untested) and is worth far more than the timing.

---

## 5. Order types — when limit, when market

The rule in one line: **the first leg is always a post-only limit, the second leg is always a
market order, and nothing else uses a limit.**

### Leg 1 — post-only limit on the thinner venue

```java
placePostOnly(venueSymbol, side, quantity, limitPrice)
```

**Post-only, not a plain limit.** On Binance this is `timeInForce=GTX`, "good till crossing": the
order is *rejected* rather than executed if it would take. Rejection is the wanted behaviour. A
plain limit that crosses fills immediately at taker, silently converting the cheap leg into the
expensive one — the exact cost this design exists to avoid. A rejected entry costs one position's
funding for one period out of twenty positions.

**Which venue rests it:** the *thinner* one. `XvfExecutionApplication.venueDepthRank()` ranks
dYdX (0) < Hyperliquid (1) < Bybit (2) < Binance (3), and the lower rank rests. Crossing costs most
where the book is thin, so the maker order is worth more there and the market order lands where
liquidity is deepest. Putting the limit on the liquid side earns the smaller saving and pays the
larger slippage. Where both legs are the same class, the short leg rests — arbitrary but
deterministic.

**Price:** the touch on the resting side, so the order is at the front of the queue without
crossing. Reading a mid or last price risks crossing and being rejected, which is safe but wastes
the entry. *(Currently a placeholder — see §12.)*

### Leg 2 — market order on the liquid venue, on the fill event

```java
placeMarket(venueSymbol, oppositeSide, filledQuantity)
```

Sent from the websocket callback the instant leg 1 reports a fill — **not on a timer, not on a
poll**. Between the two fills the book is naked in a coin selected precisely because it is
dislocated. Measured on the selected universe:

| | Cost |
| --- | ---: |
| Cross the spread | 3.2 bp |
| 5 minutes unhedged, median move | 56 bp |
| 15 minutes unhedged, median | 92 bp |
| 15 minutes unhedged, p90 | 273 bp |

There is no horizon at which waiting is cheaper than crossing, which is why the second leg has no
limit-order path at all. A partial fill hedges the filled portion immediately rather than waiting
for the remainder — a half-filled maker leg is half-naked, and the exposure is what matters.

### Everything else

| Situation | Order type | Why |
| --- | --- | --- |
| Second leg / hedge | market | above |
| Closing a pair at rebalance | market, both legs | same reasoning in reverse; a resting exit leaves the other leg naked |
| Stop before liquidation | market | a limit stop can be skipped straight through |
| Unwinding after `UNHEDGED_ALERT` | market, manual | the position is already directional |

---

## 6. Entry state machine

`PairedEntryEngine` holds one `Pair` per position, keyed by client order ID.

```
                 placePostOnly on thin venue
                            │
                            ▼
                        WORKING ──── 30 min, no fill ────► ABANDONED
                            │                              (cancel, skip)
                   fill event on user stream
                            │
                            ▼
                      MAKER_FILLED ─── market hedge ok ───► HEDGED
                            │
                     5 attempts fail
                            │
                            ▼
                     UNHEDGED_ALERT
```

### The three failure states, in order of severity

**1. Maker never fills — harmless.** Cancel after 30 minutes, skip the position. Costs one period of
funding on one of twenty positions. The abandon timer fires only from `WORKING`; once the maker has
filled, cancelling is meaningless and the correct action is to hedge, so the transition is a
`compareAndSet` rather than an unconditional write.

**2. Maker fills, hedge send fails — the dangerous one.** Retry the market order five times with
linear backoff (200ms × attempt). The engine does **not** cancel the maker leg: that leg is already
filled, and "cancelling" a filled leg means sending another market order in the same direction as
the exposure. After five failures the pair goes to `UNHEDGED_ALERT`, which is deliberately loud and
deliberately not self-healing. Silently retrying forever would hide the one state that must reach a
human.

**3. Stream goes silent — treated as failure, not as absence of fills.** A fill that arrived while
the listener was dead leaves an unhedged position nobody is watching. `outstanding()` is polled
rather than trusting the stream to have reported everything. On Binance specifically, the `listenKey`
expires 60 minutes after creation and a lapsed key does not error — the socket just stops
delivering. The keepalive `PUT` runs every 30 minutes and a failure to extend is escalated, not
retried quietly.

Note that Binance's *market data* futures stream connects but never delivers a frame in this
environment (see `ChallengeMonitorApplication`). The user data stream is a different endpoint and is
not known to have that problem, but it is unverified — §12.

### Ordering guarantee

Every gateway's `streamOrderUpdates` is wired **before the first order is placed**. Placing first
opens a window in which a fill arrives with nothing listening for it.

---

## 7. Sizing

```
legNotional = capital × LEG_LEVERAGE / (POSITIONS × 2)
```

At $10,000, 1x, 20 positions: **$250 per leg**, 40 legs, $10,000 total notional.

Then three caps applied in order:

**1. Participation cap.** `min(legNotional, thinLegWeeklyVolume × 0.01)`. Funding is a percentage and
says nothing about whether the notional is reachable — REN paid 507% annualised on $289 of weekly
volume. If the cap cuts the leg below half the target, skip the pair entirely rather than opening a
runt position that still pays four fills of fees.

**2. Liquidity floor.** The thinner leg needs **$500k** weekly quote volume or the candidate never
enters the book. Applied in `XvfSignalEngine.topBook`, before ranking.

**3. Step-size guard.** A leg needs roughly `100 × stepSize × price` in notional for rounding error
under 1%.

| | Per leg | Capital (40 legs) |
| --- | ---: | ---: |
| Median selected symbol | $5 | $200 |
| p90 symbol | $77 | **$3,089** |
| Worst (LLYUSDT, $12.09/step) | $1,209 | $48,364 |

**$3,000 minimum, $10,000 comfortable.** At $10,000 this excludes the tokenised-equity perps (LLY,
META, TSM, IWM, ARM, ALAB, AMAT, WDC) at $3–12 per step. Worth knowing the selection reaches those
at all — they are equities, not crypto, and their funding dynamics were never separately examined.

Volume for the participation cap comes from **live venue ticker endpoints** (`LiveVolume`), not from
the kline tables. The kline importers are backfills — Binance 1h currently holds 1,230 rows for the
last seven days where a full universe would carry ~140,000. A stale backfill makes the cap silently
pass everything or silently block everything, and both look like a normal empty book.

### Where the capital has to sit, and why an equal split does not work

The sizing above assumes $250 is available on whichever venue a leg lands on. It is not. Legs are
distributed across venues by the *signal*, and that distribution is never even. Measured over the 146
weeks where all four venues have funding data:

| venue | median legs | p90 | worst | (of 40) |
| --- | ---: | ---: | ---: | --- |
| dydx | 14 | 17 | 20 | |
| hyperliquid | **5** | **17** | 20 | |
| binance | 11 | 14 | 18 | |
| bybit | 9 | 13 | 17 | |

Hyperliquid is the extreme case: idle half the time, then wanting 42% of the book. dYdX carries a
median of 14 of 40 legs, nearly double an equal share.

The fear that the book collapses onto two venues does **not** happen — 145 of 146 weeks used all four
venues and one used three, never two. The problem is the opposite: all four are always needed, in
proportions that change every week.

**Funding every venue for its own peak is arithmetically impossible.**

| | Capital multiple |
| --- | ---: |
| Every venue funded to its p90 | **1.53x** |
| Every venue funded to its worst case | **1.88x** |

You cannot hold 188% of your capital, and the peaks do not coincide, so they cannot be netted.

**An equal 25% split fills the intended book in 1 week out of 18.** At $2,500 per venue each supports
10 legs; against the historical books that blocks 7.2 legs on average and 14 in the worst week, with
only 5.5% of weeks fully filled. The lost positions are not random - they are the ones on whichever
venue is currently most dislocated, which is where the widest spreads are.

Three ways out, only one of which has a known cost:

1. **Size down to fit.** Deploy `capital / 1.53` and hold the rest as venue buffer. Always fills, but
   roughly a third of capital sits idle and return on total capital falls by about a third - a
   nominal 19% becomes ~12.5%. This is the only option whose cost is measured.
2. **Cap legs per venue in the signal.** Keeps 20 positions and full deployment, but the book is then
   "top 20 subject to a constraint" rather than top 20 by spread, and nothing has measured what the
   constraint costs. A small change to `topBook`; an open question.
3. **Rebalance between venues each cycle.** On-chain, minutes to hours, and it fails precisely when
   needed - during the volatility that created the dislocation.

**More venues makes this worse, not better.** Re-running the same measurement over the 14 weeks where
OKX and Bitget also have data:

| | 4 venues | 6 venues |
| --- | ---: | ---: |
| Capital multiple at p90 | 1.25x | **1.40x** |
| Capital multiple at worst | 1.43x | **1.55x** |

Individual peaks do fall - Binance's worst drops 18 to 15 - but the venue count rises faster, so the
*sum* of peaks grows. There is a second effect pushing the same way: the spread is
`max(rate) - min(rate)` across venues, so a wider venue set takes extremes from a larger sample. That
is the best-of-N selection inflation already documented as a bug here, and it pushes the chosen legs
toward whichever venue is currently most extreme - usually a smaller one, which is the worst place to
be forced to hold capital. The four-venue configuration was chosen for data availability; this is a
second, independent reason for it.

(The 14-week window is benign compared with the full 146 - 1.25x against 1.53x - so the comparison
between the two columns is what holds, not the absolute figures. Gate is excluded entirely at 5 weeks
of history, so a genuine 7-venue answer does not exist yet.)

### Collateral is not one currency

The moves above are not just transfers, because the four venues do not share a settlement asset:

| Venue | Perp collateral |
| --- | --- |
| Hyperliquid | **USDC only** - perps are bare coin names (`BTC`, not `BTCUSDT`) with a single account-wide collateral asset. USDT exists only as the `USDT0` spot token and cannot margin a perp. |
| dYdX | USDC |
| Binance | USDT, plus USDC contracts on the larger symbols |
| Bybit | USDT |

So topping up the Hyperliquid leg from a Binance balance crosses a stablecoin as well as a chain. That
lands on exactly the worst leg: Hyperliquid has the most volatile allocation of the four (median 5,
p90 17), so it is the venue most often needing a top-up and the one where topping up costs a
conversion too.

Two things reduce it. Binance has USDC-margined contracts for larger symbols, with a better fee tier
than USDT - keeping that side in USDC where the pair allows removes one conversion. And dYdX settles
in USDC as well, so the two thinnest venues share an asset and move between each other without
touching a stablecoin pair. That leaves USDT genuinely required only for Bybit and for Binance's
USDT-only symbols.

**Every return figure in this document and in `XVF_STRATEGY.md` assumes capital is where it needs to
be.** This section is why that assumption does not hold, and the haircut is material rather than a
detail.

### Recommended split — proposed, NOT yet adopted

The three options above are a choice about which percentile to fund each venue to. Funding every
venue at its own peak is what makes the multiple exceed 1.0, so the anchor sets both how often the
book is fillable and how much capital sits idle:

| Anchor | Leg slots funded | Leg notional at $10k | Deployed | 19% gross becomes |
| --- | ---: | ---: | ---: | ---: |
| median | 39 | $256 | 102.6% (infeasible) | ~19.5%, book truncated ~half the time |
| **p90** | **61** | **$163.93** | **65.6%** | **12.5%** |
| worst case | 75 | $133 | 53.3% | 10.1% |

p90 is the recommendation: it fills the book in nine venue-weeks out of ten while leaving a third less
idle than funding to the worst case. Anchoring to it gives:

| Venue | p90 legs | Share | At $10,000 | Idle on a median week |
| --- | ---: | ---: | ---: | ---: |
| dydx | 17 | 27.9% | $2,787 | $492 |
| hyperliquid | 17 | 27.9% | $2,787 | **$1,967** |
| binance | 14 | 23.0% | $2,295 | $492 |
| bybit | 13 | 21.3% | $2,131 | $656 |

Leg notional is `capital / 61`, not `capital / 40` - the 40 legs of a full book draw on 61 funded
slots, and the difference is the buffer that absorbs an uneven week. Hyperliquid carries most of the
idle capital because its allocation is the most volatile of the four (median 5 legs, p90 17).

Combined with the collateral split below, that is **55.7% USDC and 44.3% USDT** - slightly more USDC
than USDT, which is the opposite of what holding the CEXs on USDT suggests at a glance.

**Not adopted.** Nothing in `XvfConfig` encodes it and no capital has been placed. It is written down
so the choice is explicit rather than made by default at funding time.

### Bin-packed sizing recovers most of the idle capital

Equal sizing wastes capital by construction: the book's legs land unevenly while funding is anchored
to each venue's p90. If two venues both have slack and a pair uses both, that pair can simply be
larger. Formally, choose a size for each pair subject to `sum of sizes touching venue v <= capacity_v`
and a cap on any one position - a bin-packing problem, not a weighting scheme.

Measured over 147 weeks of real selections, sizes in units of leg notional against 61 funded slots:

| Policy | Deployed | Largest position | Effective positions |
| --- | ---: | ---: | ---: |
| equal, skip pairs that do not fit | 62.0% | 5.4% | 18.9 |
| **bin-packed, max 1.5x** | **71.3%** | 6.9% | **18.9** |
| bin-packed, max 2x | 75.3% | 8.7% | 17.8 |
| bin-packed, max 3x | 79.6% | 12.4% | 15.2 |
| bin-packed, uncapped | 97.4% | **52.7%** | **2.2** |

**Uncapped is a trap.** It deploys 97.4% of capital by collapsing the book into 2.2 effective
positions with one bet at 52.7% - that is not XVF, it is a pair trade wearing its name.

**1.5x is close to free.** Deployment rises 62.0% to 71.3%, a 15% relative gain, while effective
positions stay at 18.9 and the largest position moves only 5.4% to 6.9%. On a 19% gross figure that is
roughly **11.8% to 13.5% on total capital** - larger than every fee optimisation in this document
combined.

**The method is spread-neutral, which is the point.** `XvfConfig.EQUAL_WEIGHT` records that
spread-weighting cost a third of the Sharpe, so any sizing scheme has to be checked against that.
Correlation between assigned size and spread rank is **-0.011** under random tie-breaking: sizes are
driven by venue availability, which is orthogonal to the signal's opinion. This is not
spread-weighting in disguise.

**A separate free lever, to be treated with more suspicion.** When two pairs have equal headroom,
feeding the wider spread first captures +6.4% more average spread at identical deployment (169.0%
against 158.8% with random tie-breaking). It costs nothing - but it is a mild form of the thing that
already failed once, so it should be measured on forward returns before adoption, not on signal
spread.

Three limits on the above. It is measured on signal-week spreads, so the *deployment* figures are
structural and trustworthy while anything spread-weighted is not. The participation cap is not
modelled - 1.5x sizing means 1.5x notional on the thin leg, which does not bind at $10,000 ($246
against a $5,000 cap at the volume floor) but will at larger capital. And the greedy water-fill is
near-optimal rather than optimal, though deployment was identical across all three tie-break orders,
which suggests the bound is tight.

Reproduce with `scripts/analysis-bin-packing.py`.

### Decision: one quote currency per venue

**USDT on Binance and Bybit, USDC on Hyperliquid and dYdX.** The two DEXs have no choice; the two CEXs
are held on USDT deliberately, so each venue has exactly one collateral asset and a leg's currency is
never a variable at execution time.

Fee discounts were evaluated against that decision and none of them changes it. The bill they apply
to is `20 positions x 2 legs x 2 fills x 122 rebalances x 3.3bp` = **8.05% of capital per year**,
$805 on $10,000, split by measured leg share: dydx $250, binance $210, bybit $180, hyperliquid $146.

| Option | Verdict | Measurement |
| --- | --- | --- |
| **BNB** on Binance | deferred | 10% off $210 = **$21/yr**. Consumed, not locked, so it needs only a float of ~0.2% of capital. |
| **MNT** on Bybit | excluded | Bybit does not accept MNT fee payment on API orders, and XVF is entirely API-driven. |
| **HYPE** staking | rejected | Smallest tier is 10 HYPE = $596 at $59.60, i.e. **6% of capital locked to save ~$7/yr**. Higher tiers are worse: 100 HYPE returns 0.24% on the stake. |
| **DYDX** staking | rejected | Same shape against a $250 bill, plus ~30-day unbonding. |
| **USDC contract choice** on Binance | deferred | Picking the quote that suits the leg direction is worth **+3.32% annualised per pair** (short +2.43%, long +0.89%, 850 base-weeks over 39 coins) - but only **9.9%** of selections have both contracts, so the book-level gain is **+0.33% of capital/yr**. |

The staking options are rejected on capital, not on yield. Locking capital directly worsens the 1.53x
funding constraint above, and a saving worth 2-3% of the fee bill cannot pay for capital removed from
a book that is already a third underfunded.

The two deferred items are together worth roughly **$54/yr on $10,000**, about 0.5% of capital against
an 8% fee bill - real, but not worth the extra moving parts before the strategy has traded. Worth
revisiting if Binance's promotional reduced-maker pricing on USDC pairs is live, since that attacks
the 8% directly rather than discounting it.

---

## 8. Signal

`XvfSignalEngine.topBook()` is the single source of truth. Both the reporting application and the
execution application call it, so the book you look at and the book you trade cannot drift apart.

```
1. trailing 7-day realised funding, summed per venue per symbol
2. drop any symbol with < 90% of its own median weekly payment count
   (a partial week reads as a low rate, not as missing data)
3. normalise base names        1000PEPE / PEPE / kPEPE → PEPE
4. group by base, keep bases present on ≥ 2 venues
5. spread = (max rate − min rate) × 365/7 × 100
6. keep spread > 20% annualised AND thin leg ≥ $500k weekly
7. sort by spread descending, take top 20
```

Step 3 is not cosmetic. Joining on the raw symbol split one asset into three and matched none of
them, silently dropping every meme coin — which is where funding is most extreme. Prefixes are
contract-size multipliers and funding is a rate, so normalising the name is sufficient; a *price* or
*quantity* would need the multiplier applied.

**Step 4 does not require the two legs to be on different venues.** It requires two *legs*. This is a
live bug — see §12 item 12.

### The freshness guard

`requireFreshFunding()` refuses to return a book unless **every** venue has ≥ 100 distinct symbols in
the trailing window. It checks symbol **count**, not the latest timestamp: Binance's sixteen
old-universe symbols carry live rows while the other 800 depend on a monthly archive, so a timestamp
check reports the venue current when 98% of it is missing — and the cross-venue pairing then
collapses to an empty book with no error at all. 100 sits well under every venue's real universe
(232 to 775) and well over the 16 that leak through when the archive is stale.

---

## 9. Module reference

```
xvf/
├── XvfConfig.java                       every tunable, with the measurement that settled it
├── signal/
│   ├── XvfSignalEngine.java             ranking + freshness guard   ← single source of truth
│   ├── XvfSignalApplication.java        prints the book, never sends an order
│   └── LiveVolume.java                  24h quote volume from venue tickers
├── venue/
│   ├── VenueGateway.java                interface, tick rounding, trigger orders, positions
│   ├── BinanceGateway.java              HMAC-SHA256 REST, GTX post-only, listenKey websocket
│   ├── BybitGateway.java                same shape; retCode not HTTP status decides the outcome
│   └── HyperliquidGateway.java          EIP-712 signed actions, userFills for fill accounting
└── execution/
    ├── PairedEntryEngine.java           the state machine in §6; open() and close()
    ├── XvfReconciler.java               book vs accounts, closes the difference — only ever reduces
    ├── XvfBrackets.java                 resting exit triggers for when nothing is watching
    ├── FillTracker.java                 cumulative fill accounting, max() never sum()
    ├── XvfRoundTripTest.java            live harness, one venue pair at a time, dry-run by default
    └── XvfExecutionApplication.java     wiring, sizing, dry-run gate, -DxvfMode
```

Lambda handlers live in the separate `aws/recorder` module, not here, and deliberately: AWS's managed
Java runtime tops out at 21 while this tree compiles to 25, and a recorder that dragged in Postgres and
Flyway would cold-start slower for no benefit. The module's own pom records this.

`VenueGateway` is deliberately about **order events**, not market data. What matters is hearing your
own fill in a network round trip; the market data stream is not on the critical path.

Implementations own their credentials. Nothing in the interface reads keys, and no key material is
logged, held in fields longer than a request needs, or written to the database. `BinanceGateway`
overrides `toString()` so credentials cannot leak through a debug print of the object.

### Per-venue user data streams

| Venue | Endpoint | Notes |
| --- | --- | --- |
| Binance | `POST /fapi/v1/listenKey` → `wss://fstream.binance.com/private/ws/<key>` | event `ORDER_TRADE_UPDATE`; key expires 60 min, `PUT` every 30. **`/private`, not `/ws`** — the unified URL was decommissioned 2026-04-23 and delivers nothing while still handshaking and pinging. `XVF_LIVE_FINDINGS.md` §1 |
| Bybit | `wss://stream.bybit.com/v5/private` | authenticate, then subscribe `order` + `execution` |
| Hyperliquid | `wss://api.hyperliquid.xyz/ws` | subscribe `{"type":"userFills","user":"0x..."}` |
| dYdX | `wss://indexer.dydx.trade/v4/ws` | channel `v4_subaccounts` |

---

## 10. Data dependencies

| Table | Feeds | Refreshed by |
| --- | --- | --- |
| `perp_funding_all` (view over 7 venue tables) | signal, freshness guard | `VenueFundingImportApplication` |
| `binance_perp_kline`, `bybit_perp_kline`, `hyperliquid_perp_kline`, `dydx_perp_kline` | basis measurement, backtests | `KlineArchiveImportApplication`, `VenueCandleImportApplication` |
| venue ticker endpoints (no table) | participation cap | `LiveVolume`, at signal time |

`scripts/xvf-refresh.sh` runs funding first (the signal needs it) then candles (they only feed basis
measurement and can lag a day without breaking anything), with a 10-day floor so a daily run costs
one or two pages per symbol instead of re-walking years — the dYdX full history took 6.5 hours,
which is not a daily job.

---

## 11. Configuration reference

All in `XvfConfig`. Every value below was measured, not chosen.

| Constant | Value | Settled by |
| --- | --- | --- |
| `VENUES` | binance, bybit, hyperliquid, dydx | the four with usable funding history; OKX/Gate/Bitget serve 1–3 months |
| `LOOKBACK_DAYS` | 7 | carried over from cash-and-carry, not swept |
| `MIN_SPREAD_ANNUAL_PCT` | 20.0 | 12.0% net at a 0% threshold, 19.0% at 20%, 25.4% at 40% but only 6 positions |
| `POSITIONS` | 20 | Sharpe 5.12 vs 4.83 at top 10; ranks 11–20 realise 23.0% forward funding vs 22.3% for 6–10 |
| `REBALANCE_DAYS` | 3 | swept 1/2/3/5/7/10/14 → 14.7 / 17.8 / **22.0** / 19.7 / 19.5 / 15.9 / 13.4 |
| `EQUAL_WEIGHT` | true | spread-weighting cost a third of the Sharpe (4.83 → 3.47) for one point of return |
| `LEG_LEVERAGE` | 1.0 | 2x/3x/5x worse once liquidation friction charged |
| `MIN_WEEKLY_QUOTE_VOLUME` | 500,000 | below this, prints are untradeable and basis goes to zero |
| `MAX_PARTICIPATION` | 0.01 | — |
| `MIN_STEPS_PER_LEG` | 100 | rounding error under 1% |
| `MAKER_BPS` / `TAKER_BPS` | 1.8 / 5.0 | venue schedules, VIP0, BNB discount |
| `MIN_CAPITAL_USD` | 3,089 | p90 symbol at $77/leg × 40 legs |

Daily rebalancing is the worst setting above weekly because **basis is a per-round-trip cost, not a
per-day one**: −22.6% daily, −10.4% at three days, −3.4% weekly, −1.3% at fourteen. Churn realises
it; holding lets it mean-revert.

---

## 12. Known gaps — read before trading

### Not implemented

1. **Exits.** There is no close path. `PairedEntryEngine` opens pairs; nothing closes them. The
   3-day rebalance in `XvfConfig` is a backtest parameter with no runtime counterpart. **This is now
   the only remaining blocker to a first live trade** — every other item that made
   `-DxvfDryRun=false` unsafe has closed, so a rebalance today would open positions on all three wired
   venues correctly and then have no way to end them.
2. ~~**Three of four gateways.**~~ Closed 2026-08-18 for the three venues v1 uses. Binance, Bybit and
   Hyperliquid all place real orders; dYdX is deliberately absent, not unwired — the venue measurement
   excluded it (`XVF_V1_SCOPE.md`). Verified live: a full dry run against real credentials on all
   three venues built correctly-sized, correctly-priced orders in one book with imbalance under 0.28%
   throughout. See item 11 for what "verified" covers for Hyperliquid specifically.
3. ~~**`referencePrice()` returns `BigDecimal.ONE`.**~~ Fixed 2026-08-18. Now
   `gateway.topOfBook(symbol).touch(side)` — a resting SELL joins the ask, a resting BUY the bid.
4. **Stop-loss before liquidation.** Designed but not coded. At 1x, 2.1% of legs still liquidate.
5. ~~**No launchd agent** for `scripts/xvf-refresh.sh`.~~ Closed on 2026-08-18.
   `com.smalistean.propstrategy.xvf-refresh` runs it daily at 06:45 local, and
   `com.smalistean.propstrategy.xvf-funding-export` drains the DynamoDB observation buffer hourly at
   `:20`. Both are loaded. What the gap cost while it was open: settled funding had gone six days
   stale (bybit and hyperliquid last at 2026-08-12) and 43 hours of pending-rate observations were
   sitting in a buffer with a 30-day TTL.

### Untested

6. **The Binance and Bybit user data streams.** Neither has run against a live account with a real
   fill on it. The entire entry design depends on the fill event arriving. Hyperliquid's fill path
   (`userFills`) has run live — see item 11.
7. **Hysteresis for mid-week switching — the largest unclaimed item here.** No rule exists for what
   happens when a held pair stops being worth holding between rebalances. Positions are opened on a
   3-day schedule and closed on it, and nothing looks at them in between.

   The stamp-timing work in §4 is what makes the size of this visible. That analysis chased ~$7/yr by
   moving entries and exits across individual stamps, and along the way established the diagnostic:
   **a pair whose net cashflow at its shared stamps has turned negative is a pair that should be
   closed**, not one whose exit should be nudged. The trailing signal cannot see this, because it is
   computed once at rebalance and the rates move afterwards.

   Two thresholds are needed and neither has been measured: how far the realised spread must fall
   before closing early, and how much it must recover before re-entering — the gap between them being
   the hysteresis, which exists to stop a pair oscillating across the boundary and paying a 13bp round
   trip each time. The cost of getting the band wrong is bounded by that 13bp; the cost of having no
   rule at all is unbounded, since a pair can sit inverted for the remainder of a 3-day hold.

   Worth far more than the timing optimisations that surfaced it, and it is the next thing to measure.

### Discrepancies between the documented strategy and the code

8. **`SECOND_LEG_CROSS_AFTER_SECONDS = 60` is dead.** It is defined in `XvfConfig` and read nowhere.
   `XvfExecutionApplication` hardcodes `Duration.ofMinutes(30)` for a different quantity — the maker
   *abandon* timeout, not a second-leg cross delay. The constant is both unused and misnamed.
9. **The engine never crosses an unfilled maker leg.** The 3.3bp blended fee assumes 54% of legs fill
   post-only within a minute and the other 46% cross. The code instead fills 54% at maker and
   *abandons* the rest. Those are different books: the backtest holds twenty positions at a blended
   cost, the implementation holds roughly eleven at a maker cost. The implemented entry cost per pair
   is `1.8bp (maker) + 5.0bp (taker) = 6.8bp`, and the missing positions are a selection effect
   nobody has measured — the pairs that fail to fill post-only are unlikely to be a random subset.
10. **The 30-minute rest window contradicts §4.** Resting until `HH:27` means holding orders through
    the stamp minute and the expensive decay after it. If the entry window is adopted, the abandon
    timeout should be minutes, not 30.

### Hyperliquid signing — what was verified, and a bug caught live

11. **The EIP-712 signing pipeline is correct, verified two ways, and one real bug was found and fixed
    in the process.**

    Before writing `HyperliquidGateway`, the msgpack encoding, the `action_hash` construction, and the
    EIP-712 domain were cross-checked against a from-scratch Python reference built from the official
    SDK source (not from this code): a real order action's msgpack bytes and its `action_hash` matched
    the Java implementation exactly, byte for byte. `web3j`'s `Keys.getAddress`, `Hash.sha3`
    (Keccak-256 — the JDK has no Keccak provider, which is why this dependency exists at all) and
    `StructuredDataEncoder` were each checked against known test vectors before being trusted.

    That caught nothing, because the test case only exercised small integers (an asset index under
    300). **Live testing against the real account found a genuine bug the offline check had no reason
    to catch**: `MsgPack.writeInt` had no branch above `uint32` and silently truncated larger values
    through a narrowing `(int)` cast. Hyperliquid's numeric order ids exceed 2^32
    (`519178520652` in the case that surfaced it), and only a *cancel* action carries one — a fresh
    order's only integer is the small asset index, so order placement never exercised this path.

    The failure mode was actively misleading. The JSON body sent to the exchange carried the correct,
    untruncated value via Jackson, so the exchange computed the correct hash from what it received.
    This class signed a *different* hash, built from the truncated value. A valid ECDSA signature over
    the wrong hash still recovers **some** address when checked against the right one — not an error,
    a different, meaningless public key — which surfaced as:

    ```
    User or API Wallet 0xa8173edbaa67dea89910c3c38912e2e659ea4b1f does not exist.
    ```

    for an address that had never been configured anywhere and had nothing to do with the actual
    key. That is indistinguishable from a credentials problem unless you already know to suspect the
    hash rather than the key. Fixed by adding the `uint64` branch (`0xcf` + 8 bytes) and pinned with a
    regression test using this exact oid.

    **What was then verified live, in order, on the real account:**

    | Step | Result |
    | --- | --- |
    | Key derivation | `HL_API_PRIVATE_KEY` correctly derives to `HL_API_WALLET_ADDRESS`, checked at construction |
    | Read-only calls | `rules`, `topOfBook`, `orderByClientId` all correct against live data |
    | Signed order, real matching-engine rejection | `$4.80` order rejected with *"Order must have minimum value of $10"* — proves the signature was valid before the truncation bug was even found, since an invalid signature fails differently |
    | Signed order, accepted | `$10.20` BUY on BTC accepted, real `oid` returned, appeared in `openOrders` |
    | Signed cancel (before the fix) | failed with the misleading address error above |
    | Signed cancel (after the fix) | `{"status":"ok",...,"statuses":["success"]}`, order gone from `openOrders` |

    Account left clean: no open orders, no positions, after every step above.

    **What this does not cover.** One order and one cancel is not a test suite. `userFills` fill
    accounting (summing discrete executions per `oid`, deduplicated by `tid` — see the class javadoc
    for why this was chosen over trusting `orderUpdates`' ambiguous `sz` field) has not been exercised
    against a real fill, because the account has never held enough equity for one. The 30-second
    websocket heartbeat interval was not verified against current documentation. `MAX_TAKER_SLIPPAGE_BPS`
    capped IOC orders have not been tested live on this venue specifically.

### Bugs found by running the signal on 2026-08-15

12. **The signal emits same-venue pairs.** `topBook` groups legs by normalised base and takes the max
    and min trailing rate among them, with no requirement that the two legs sit on different venues.
    Observed output:

    ```
    KAITO   binance KAITOUSDC   binance KAITOUSDT   38.3%
    ```

    Both legs on Binance. The base normaliser strips `USDT` and `USDC` alike, so the two quote
    variants of one coin collapse to one base and then pair against each other. Nothing downstream
    catches it: `isThinner("binance","binance")` returns true on the `<=`, so maker and taker
    gateways both resolve to Binance and the pair would be placed.

    A USDT/USDC funding spread on one venue is a real trade, but it is not the one that was measured
    — it is cross-margined, carries no withdrawal latency and no second-venue risk, and none of the
    backtest applies to it. It must either be excluded or measured separately, not silently mixed
    into a book labelled cross-venue.

13. **The freshness guard passes on data three days stale.** The same run reported
    `binance latest 2026-08-14, bybit 2026-08-12, dydx 2026-08-13, hyperliquid 2026-08-12` and
    produced **4 candidates instead of 20** — $2,000 of $10,000 deployed. The guard passed because it
    counts distinct symbols over the trailing 7 days (775 for Bybit, far above the 100 floor) and
    never looks at how recent they are.

    The degradation is safe but silent. The `payments >= 0.9 × median` completeness filter drops any
    symbol whose trailing window is partial, so a stale venue quietly loses almost all its legs; what
    survives is whatever venue is freshest. That is exactly how KAITO ended up as a Binance-only pair
    — the dYdX, Hyperliquid and Bybit KAITO legs all exist and all price wider (dYdX 0.0% against
    Binance −1366.0%, a 1,366-point spread) but were filtered out for incompleteness, leaving the two
    Binance contracts as the only survivors.

    The guard was built to catch a stale monthly archive and does catch that. It does not catch "every
    venue is a few days behind", which produces a book that is undersized and mis-selected rather
    than empty.

14. **The signal ignores `fundingIntervalHours`.** Binance publishes each symbol's current funding
    interval through an unauthenticated endpoint, and shortening it to 1 hour is Binance's own
    statement that the symbol is in a funding dislocation — the condition XVF screens for. Measured in
    §3: 100% of the symbols that went hourly exceeded the entry threshold, against 37.1% of the rest.
    Nothing in `XvfSignalEngine` reads it. It is not clear whether it should be an input to ranking or
    only to timing, but at minimum the execution path needs it to know when the next stamp is.

15. **Interval changes can trip the completeness filter, though they are not doing so today.**
    `payments >= 0.9 × median(weekly payments over 180d)` assumes a stable cadence. A symbol switching
    8h → 1h goes from 21 payments a week to 168 and passes trivially; one switching *back* has 21
    against an inflated median and would be dropped — silently removing symbols just as they come out
    of the dislocation that selected them. Measured on 2026-08-15: all 730 Binance symbols pass, and
    104 of them have had a ≥3× swing in weekly payment count within 180 days. The failure mode is
    latent, not active, but the filter has no notion of the cadence having legitimately changed.

### Unmeasured risks

16. **Adverse selection on maker fills.** You fill when price moves through your level, so the fills
    you get are the worse ones. Requires trade-level data; absent from the 3.3bp.
17. **Fill rates off Binance.** All 313 measured legs were Binance. dYdX and Hyperliquid have thinner
    books; fills will be worse and crossing more expensive.
18. **Survivorship.** Hyperliquid, Bybit and dYdX universes come from currently-listed endpoints —
    every coin in the backtest survived to today. Only Binance's archive includes delistings.
19. **Cross-venue collateral — now partly measured, see §7.** Legs sit on separate venues with no
    cross-margining, and moving funds is an on-chain withdrawal taking minutes to hours. What §7 adds
    is the size of it: funding every venue for its own peak needs **1.53x capital at p90 and 1.88x at
    worst case**, an equal split fills the intended book in only **5.5% of weeks**, and the venues do
    not even share a settlement asset — Hyperliquid and dYdX are USDC, Bybit is USDT, so a top-up
    crosses a stablecoin as well as a chain.

    What remains unmeasured is the *cost* of each way out. Sizing down is quantified (return on total
    capital falls by roughly a third); capping legs per venue and rebalancing between venues are not.
    Until one is chosen and measured, every return figure here assumes capital is already where it
    needs to be, which §7 shows it cannot be.
20. **Reconciliation.** This project has produced 7.5%, 10.98%, 18.5%, 19.0%, 19.6%, 22.0% and 28%
    from pipelines built at different times over different periods. They have not been collapsed into
    one number from one code path. Treat any single figure as indicative until they are.

---

## 13. Runbook

Refresh the data (satisfies the freshness guard). Both now run from launchd, so this is only
needed to force a run early:

```bash
bash scripts/xvf-refresh.sh
```

Drain the DynamoDB observation buffer into `venue_funding_observation`. Distinct from the refresh
above: that one backfills *settled* funding from the venue REST APIs and is refetchable, this one
moves *pending-rate observations* that no endpoint will serve again once the 30-day TTL expires:

```bash
bash scripts/xvf-funding-export.sh
```

Print the target book without sending anything:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.smalistean.propstrategy.xvf.signal.XvfSignalApplication -DxvfCapital=10000
```

Dry run of the full execution path — resolves the book, sizes it, prints every order it would send:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.smalistean.propstrategy.xvf.execution.XvfExecutionApplication -DxvfCapital=10000
```

Live. **Do not run this until §12 item 1 (exits) is closed.** Items 2 and 3 closed on
2026-08-18. Dry run is the default because a missed
run costs one period of funding while an unintended run costs real money on twenty positions:

```bash
mvn -q compile exec:java -Dexec.mainClass=com.smalistean.propstrategy.xvf.execution.XvfExecutionApplication -DxvfCapital=10000 -DxvfDryRun=false -DbinanceApiKey=... -DbinanceApiSecret=...
```
