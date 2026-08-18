# XVF v1 — scope

What the first version implements, what it deliberately leaves out, and why.

`XVF_STRATEGY.md` is what the strategy is. `XVF_IMPLEMENTATION.md` is how the full thing works and
everything measured about it. This is the much smaller subset being built first.

**Purpose of v1: prove the execution path with real money at minimum size, and start the
pre-registered forward test.** It is not to maximise return. Every optimisation that has been measured
is deferred, because none of them matters if the plumbing does not work.

---

## Decision: Binance, Bybit and Hyperliquid — and drop dYdX

Three venues. An earlier draft of this file said Binance and Bybit only, on a capital-efficiency
argument. **That was wrong**, and measuring it properly also produced a finding that outlives v1.

### CEX-CEX pairs realise less than half

Realised forward funding, holding the same two venues through the week after the signal:

| Pair type | n | Avg signal | Avg realised | Median | % positive |
| --- | ---: | ---: | ---: | ---: | ---: |
| CEX-DEX | 1,868 | 175% | **84.3%** | 38.8% | 88.5% |
| DEX-DEX | 498 | 104% | 58.0% | 40.1% | 92.2% |
| **CEX-CEX** | 472 | 166% | **37.3%** | 13.4% | 69.1% |

Nearly the same signal, less than half the realisation. Binance and Bybit share participants and arb
flow, so a gap between them closes fast; a CEX-DEX gap is expensive to close and persists. A book made
only of CEX-CEX pairs is the *least* profitable slice of the strategy, which is what the earlier draft
proposed.

### Ranked properly, with liquidity and book fill applied

The comparison only means something with the $500k weekly floor enforced on both legs — without it,
82.2% of selected dYdX legs are untradeable prints. Deployment is from p90 leg-slot anchoring, book
fill is the average share of the 20 positions that can actually be filled:

| Venue set | Realised | Deployed | Book fill | Score |
| --- | ---: | ---: | ---: | ---: |
| **binance + bybit + hyperliquid** | 25.5% | 73% | 90% | **16.7** |
| all four | 27.7% | 63% | 92% | 16.0 |
| binance + hyperliquid | 22.3% | 100% | 71% | 15.8 |
| binance + bybit | 19.8% | 100% | 73% | 14.4 |

Adding Hyperliquid to the two CEXs is worth **+16%** (14.4 to 16.7) for one gateway.

### dYdX should be dropped from the strategy, not just from v1

Adding dYdX to the other three makes the result **worse**, 16.7 to 16.0. Its funding history is
extensive, which is why it was in `XvfConfig.VENUES`, but its *tradeable* universe cannot support a
book: with the liquidity floor applied, a Binance+dYdX pairing yields **5.4 candidates a week and a
full book in 4.8% of weeks**. It contributes little and dilutes deployment by demanding its own
capital buffer.

An unfiltered version of this comparison scored Binance+dYdX highest by a wide margin. That was
entirely the REN failure mode `XVF_STRATEGY.md` already documents — extreme funding on volume that
could never have been traded. Any venue comparison run without the liquidity floor will reach the same
wrong answer.

### The engineering cost, accepted rather than avoided

Binance and Bybit both authenticate with HMAC-SHA256 over a REST query string, so `BinanceGateway`
ports directly. Hyperliquid needs EIP-712 wallet signing, which is genuinely unfamiliar work — but it
is one new auth model rather than two, since dropping dYdX removes the Cosmos SDK path entirely.

Hyperliquid settles in USDC while the CEXs are USDT, so v1 does carry two collateral assets and one
on-chain leg. That is the price of the 16%.

---

## In scope

| # | Item | State |
| --- | --- | --- |
| 1 | Binance + Bybit + Hyperliquid; drop dydx from `XvfConfig.VENUES` | change |
| 2 | Capital split by p90 leg slots across three venues, ~73% deployed | no code |
| 3 | USDT on the CEXs, USDC on Hyperliquid | `XvfConfig.collateral` already correct |
| 4 | Equal sizing | already the default |
| 5 | Signal: 7-day trailing, >20% entry, top 20, 3-day rebalance | built |
| 6 | Freshness guard counting usable symbols | built, verified refusing |
| 7 | Reject same-venue pairs | **must fix** — §12 item 11 |
| 8 | Post-only on thinner venue, market hedge on the fill event | built, untested live |
| 9 | Real `referencePrice()` from best bid/ask | **must build** — §12 item 3 |
| 10 | Exit path: market both legs at rebalance | **must build** — §12 item 1 |
| 11 | Bybit gateway (HMAC REST, mirrors Binance) | **must build** |
| 11b | Hyperliquid gateway (EIP-712 signing) | **must build**, the hard one |
| 12 | Entry inside the pre-stamp window | **must build**, cheap, measured 21.9bp vs 26.2bp |
| 13 | Scheduled `xvf-refresh.sh` | **must build** — guard currently refuses on stale data |
| 14 | Dry-run default, `-DxvfDryRun=false` to trade | built |

## Out of scope, with where each is recorded

| Item | Worth | Why deferred | Recorded |
| --- | --- | --- | --- |
| dYdX entirely | negative | tradeable universe too thin: 5.4 candidates/week under the liquidity floor | this file |
| Bin-packed sizing | +1.7pp of return | ~73% deployed at three venues, so it still applies — but v1 proves plumbing first | §7 |
| Hysteresis / early close | largest unclaimed item | needs measurement before design | §12 item 7 |
| Stamp-level entry/exit timing | ~$7/yr on $10k | measured, too small | §4 |
| BNB fee discount | ~$21/yr | deferred, revisit later | §7 |
| Binance USDC contracts | ~$33/yr | second collateral asset not worth it yet | §7 |
| HYPE / DYDX staking | rejected | locks capital, 6% of capital for $7/yr | §7 |
| Stop-loss before liquidation | 2.1% of legs liquidate at 1x | needs design | §12 item 4 |
| `fundingIntervalHours` in the signal | unknown | free live marker, unmeasured | §12 item 13 |
| Funding observations feeding the guard | unblocks stale data | needs the settled-vs-observed comparison | §12 item 13 |

---

## Build order

Each step leaves the system in a state that can be dry-run.

1. **Reject same-venue pairs** in `XvfSignalEngine.topBook`. Correctness bug: `KAITOUSDC`/`KAITOUSDT`
   both normalise to `KAITO` on Binance and pair against each other. Small, and it must not reach live
   trading.
2. **Schedule `xvf-refresh.sh`.** Without it the guard correctly refuses and nothing else can be
   tested end to end.
3. **Real `referencePrice()`.** Currently returns `BigDecimal.ONE`, so every computed quantity is wrong
   by the price of the asset. This alone makes `-DxvfDryRun=false` unsafe today.
4. **Bybit gateway**, mirroring `BinanceGateway`: HMAC REST, post-only, user data stream over
   `wss://stream.bybit.com/v5/private`. The easy one - same auth shape.
4b. **Hyperliquid gateway.** EIP-712 wallet signing, `wss://api.hyperliquid.xyz/ws` with
   `userFills`. The hard one, and the reason this is not a two-venue v1.
5. **Exit path.** Market both legs, close the whole book at the rebalance. No cleverness.
6. **Pre-stamp entry window.** Place orders at `HH:57`-`HH:59` before the slower leg's stamp hour;
   Hyperliquid is hourly so the CEX leg sets the timing.
7. **Verify the Binance user data stream against a live account.** The entire entry design depends on
   the fill event arriving; it has never run.
8. **Paper, then minimum size.** $3,000 is the step-rounding floor; $10,000 is comfortable.

---

## What v1 will not prove

It runs three venues of four, though the fourth is dropped on measurement rather than convenience.
The 19% gross figure in `XVF_STRATEGY.md` comes from a four-venue book with no liquidity floor in the
venue comparison; the realised figures in this file are lower and computed differently, and the two
have not been reconciled.

It also inherits every unmeasured risk in §12: adverse selection on maker fills, survivorship in the
venue universes, and the reconciliation problem — this project has produced 7.5%, 10.98%, 18.5%,
19.0%, 19.6%, 22.0% and 28% from different pipelines and they have never been collapsed into one
number from one code path.

v1 is a plumbing test that happens to carry real money. Treat its return as an execution measurement,
not as evidence about the strategy.
