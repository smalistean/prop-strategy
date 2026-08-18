# XVF execution design

The contract between the strategy and a venue: what is adopted, what it replaced, and why each choice
is the safe direction rather than merely the tidy one.

Derived from a review of `XVF_EXECUTION_API_REQUIREMENTS.md` (Codex, 2026-08-17). That document is the
API survey; this one is the subset implemented, with the reasoning that survived scrutiny. Scope is
`XVF_V1_SCOPE.md`: Binance, Bybit, Hyperliquid.

---

## 1. The rule everything follows

Every choice below resolves the same way: **when two failure modes are available, take the one a human
will see.**

An under-hedge raises an alert. An over-hedge is a silent naked position. A refused entry costs one
period of funding on one of twenty positions. A duplicated entry costs real money. Where the design
looks over-cautious, it is buying visibility rather than correctness.

---

## 2. The bug this contract exists to prevent

`OrderUpdate.filledQuantity` is **cumulative** — Binance's `z` is the running total for the order, not
the size of the latest fill. The engine previously hedged that figure on every `PARTIALLY_FILLED`
event:

| Event | Cumulative `z` | Hedged | Should have hedged |
| --- | ---: | ---: | ---: |
| partial 1 | 1 | 1 | 1 |
| partial 2 | 2 | 2 | 1 |
| partial 3 (final) | 3 | 3 | 1 |
| **total** | | **6** | **3** |

Two units of hedge against one unit of maker fill, leaving a **naked short equal to the whole
position** in a coin selected precisely for being dislocated. The excess is `Q(N-1)/2` for N partials.

The class javadoc concealed it by describing intent instead of units: *"partial fills hedge the filled
portion"* is true about what was meant and wrong about what the number is.

**Fix:** difference the cumulative figure against a per-pair high-water mark, under a lock so two
updates cannot both claim the same increment.

**Why not read the per-event increment** (`l` on Binance), which is the obvious alternative: a
cumulative total is *self-correcting*. Drop a message and the next event still carries the true
running total; sum increments and a dropped message is permanently wrong. Tested directly —
`aDroppedUpdateIsRecoveredByTheNextOne`.

**Why the watermark advances before the hedge is sent:** a failure then under-hedges and raises
`UNHEDGED_ALERT`. The alternative risks hedging the same increment twice, which nothing reports.

---

## 3. Three submission outcomes, not two

```java
enum SubmitOutcome { ACCEPTED, REJECTED, UNKNOWN }
```

A timeout or 5xx after the request has left the process is **not** a rejection. The order may already
be resting. Both naive readings are wrong:

| Treated as | Consequence |
| --- | --- |
| `REJECTED` | a live order nobody is tracking |
| retry | a second order at the same price |

Only an explicit 4xx carries a venue decision. Everything else resolves through
`orderByClientId(venueSymbol, clientOrderId)`. An empty answer means the venue never saw it and is a
genuine rejection; anything else means it exists and must be tracked, whatever the network said.

The previous code threw on any exception and the hedge path retried five times, so one timeout after
acceptance placed up to five extra crossing orders.

## 4. The caller owns the client order ID

An ID generated inside a gateway cannot be persisted before the request is sent, which makes an
ambiguous submission unresolvable — there is nothing to query by. `placePostOnly` and `placeCappedIoc`
both take an ID the caller has already written down.

This is also what makes restart recovery possible at all: without caller-owned IDs, a process that
dies mid-rebalance cannot ask a venue what it did.

## 5. Registration lifecycle, and a defect the tests found

A pair is registered **before** its maker order is submitted, so a fill arriving in the same
millisecond has somewhere to land. It is deregistered on one condition only: a submission the venue
never saw.

| Terminal reason | Stays registered? | Why |
| --- | --- | --- |
| Maker rejected / never landed | **no** | no order exists, so no fill can; a stray or replayed update must find nothing |
| Abandon timer fired, cancel sent | **yes** | the cancel can race a real fill, and that exposure still has to be offset |

Writing the test for the first row exposed the second: the original fix removed nothing, so an update
arriving after a rejected submit still triggered a hedge. Deregistering unconditionally would have
been the opposite error, discarding the cancel/fill race that Codex's §10 step 6 exists to handle.

## 6. Capped IOC, never an unbounded market order

Crossing is intended. Crossing at *any* price is not.

```java
placeCappedIoc(symbol, side, quantity, worstPrice, clientOrderId)
```

Priced from the touch plus `XvfConfig.MAX_TAKER_SLIPPAGE_BPS` (25bp). Measured cost to cross is 3.2bp,
so 25bp absorbs a thin book and a moving market while still refusing the prints that make a market
order dangerous in exactly the coins XVF selects. An IOC that caps out leaves the remainder unfilled
and **visible**, rather than executed at whatever was there.

Maps consistently across all three venues: Binance `timeInForce=IOC`, Bybit `timeInForce=IOC`,
Hyperliquid TIF `Ioc`.

## 7. Resting price comes from the book

`referencePrice()` returned `BigDecimal.ONE`, making every computed quantity wrong by the price of the
asset. `topOfBook` replaces it:

- a resting **SELL** joins the ask, a resting **BUY** joins the bid — the side you are joining, not
  the one you would cross into;
- mid would cross and be rejected under post-only (safe, but it wastes the entry);
- last-traded price is unrelated to where the book currently is.

## 8. Credentials

Environment variables — `BINANCE_API_KEY`, `BINANCE_API_SECRET` — never system properties.
`-DbinanceApiKey=...` is visible in `ps aux` to every user on the machine and is captured in any
process listing a crash dump or monitoring agent takes.

Also required, none of which the code can enforce: dedicated subaccount per venue, trading permission
only, withdrawals disabled, IP allowlist where supported, and a dedicated Hyperliquid agent wallet.
`toString()` is overridden on the gateway so credentials cannot leak through a debug print.

---

## 9. The interface

```java
SubmitResult      placePostOnly(symbol, side, qty, limitPrice, clientOrderId);
SubmitResult      placeCappedIoc(symbol, side, qty, worstPrice, clientOrderId);
void              cancel(handle);
Optional<OrderSnapshot> orderByClientId(symbol, clientOrderId);
TopOfBook         topOfBook(symbol);
AutoCloseable     streamOrderUpdates(listener);
SymbolRules       rules(symbol);
```

`rules` reads the venue **filters** (`LOT_SIZE`, `PRICE_FILTER`, `MIN_NOTIONAL`), not
`pricePrecision`/`quantityPrecision` — Binance documents explicitly that those are display precision,
not tick and step size. Getting that wrong produces orders rejected for an invalid price increment,
or silently rounded into a different size.

## 10. Test coverage

Six tests in `PairedEntryEngineTest`, each pinning a failure that has occurred or was one live fill
away:

| Test | Pins |
| --- | --- |
| `hedgesOnlyTheIncrementAcrossThreePartialFills` | 1+1+1, not 1+2+3 |
| `ignoresDuplicateAndOutOfOrderUpdates` | a repeated cumulative figure exposes nothing new |
| `aDroppedUpdateIsRecoveredByTheNextOne` | why cumulative beats per-event increments |
| `cappedIocPriceIsBoundedByTheTouch` | the cap clears the ask but stays inside 25bp |
| `unknownSubmissionIsResolvedByClientIdRatherThanRetried` | queried once, not re-sent |
| `unknownSubmissionTheVenueNeverSawCountsAsRejected` | no order, no registration, no hedge |

---

## 11. Adopted from the review, and what was not

| Codex section | Status |
| --- | --- |
| 3.5 order/fill event model | **adopted** — client IDs, UNKNOWN, cumulative-vs-incremental |
| 4 venue-neutral API | **adopted in part** — submit/cancel/query/topOfBook/stream; no `capabilities()`, `serverTime()`, `positions()`, `fillsSince()` yet |
| 10 recovery sequence | **partially adopted** — UNKNOWN resolution and the cancel/fill race; steps needing durable state are not |
| 12 security | **adopted** — credentials off the command line |
| 11 execution ledger | **not adopted** — needs schema and a rebalance-slot lease |
| 13.2 continuous supervisor | **not adopted** — see below |
| 8 dYdX | **rejected** — venue excluded, see `XVF_V1_SCOPE.md` |
| 9 sizing formula | **rejected as stated** — it restates `capital/40`, which the capital-allocation work showed is wrong |

## 12. What is still missing, in the order it can cost money

1. **No durable state.** Everything lives in a `ConcurrentHashMap`. A crash between maker fill and
   hedge loses the knowledge that an unhedged leg exists — the one state that must survive anything.
   Caller-owned client IDs make the fix possible; nothing yet writes them down.
2. **No frozen signal run.** `XvfExecutionApplication` calls `topBook()` at startup, so a restart
   mid-rebalance trades a *different* book from the one it was partway through.
3. **No exit path.** Nothing closes a position. The 3-day rebalance is a backtest parameter with no
   runtime counterpart.
4. **No supervision between rebalances.** Positions sit for three days with nothing watching them.
   The §2 argument in `XVF_IMPLEMENTATION.md` for a short-lived process covers the rebalance only, and
   Codex is right that it does not extend to the hold.
5. **Bybit and Hyperliquid gateways** are unimplemented; `UnwiredGateway` throws rather than silently
   opening one leg.
6. **The Binance user data stream has never run against a live account.** The entire entry design
   depends on the fill event arriving.
