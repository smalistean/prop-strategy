# XVF v1 — scope

What the first version implements, what it deliberately leaves out, and why.

`XVF_STRATEGY.md` is what the strategy is. `XVF_IMPLEMENTATION.md` is how the full thing works and
everything measured about it. This is the much smaller subset being built first.

**Purpose of v1: prove the execution path with real money at minimum size, and start the
pre-registered forward test.** It is not to maximise return. Every optimisation that has been measured
is deferred, because none of them matters if the plumbing does not work.

---

## Decision: Binance, Bybit and Hyperliquid — dYdX excluded

Three venues, chosen by measuring all ten combinations of the four. An earlier draft of this file said
Binance and Bybit only, on a capital-efficiency argument. **That was wrong** — it picked the least
profitable slice of the strategy — and correcting it produced findings that outlive v1.

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

All ten combinations of the four venues:

| Venue set | Cands/wk | Realised | Deployed | Book fill | Score |
| --- | ---: | ---: | ---: | ---: | ---: |
| **binance + bybit + hyperliquid** | 51.7 | 25.5% | 73% | 90% | **16.7** |
| all four | 55.5 | 27.7% | 63% | 92% | 16.0 |
| binance + hyperliquid | 21.5 | 22.3% | 100% | 71% | 15.8 |
| binance + hyperliquid + dydx | 25.7 | 25.1% | 78% | 79% | 15.6 |
| bybit + hyperliquid + dydx | 24.7 | 24.6% | 78% | 77% | 14.9 |
| bybit + hyperliquid | 20.0 | 21.7% | 100% | 67% | 14.5 |
| binance + bybit | 30.9 | 19.8% | 100% | 73% | 14.4 |
| binance + bybit + dydx | 35.5 | 22.3% | 82% | 79% | 14.4 |
| hyperliquid + dydx | 8.7 | **31.5%** | 100% | 37% | 11.7 |
| bybit + dydx | 5.0 | 24.0% | 100% | 23% | 5.5 |

Adding Hyperliquid to the two CEXs is worth **+16%** (14.4 to 16.7) for one gateway.

**Hyperliquid is the single most valuable venue.** It appears in the top three sets, and the highest
realised figure of all - 31.5% - belongs to hyperliquid+dydx, which fails only because 8.7 candidates
a week cannot fill a book. Binance is the better CEX partner for it than Bybit (15.8 against 14.5).

### dYdX earns its place only in books that are short of names

Adding dYdX to the best set makes it **worse**, 16.7 to 16.0, and the same holds for
binance+hyperliquid (15.8 to 15.6). But it *helps* bybit+hyperliquid (14.5 to 14.9), because that set
has only 20 candidates a week and a 67% fill - dYdX supplies names it does not otherwise have.

So the accurate statement is not "dYdX is bad" but **"dYdX is a marginal venue that pays only when the
book is candidate-starved"**, and the configuration chosen here is not. Its tradeable universe is the
constraint: under the liquidity floor a Binance+dYdX pairing yields 5.4 candidates a week and a full
book in 4.8% of weeks.

An unfiltered version of this comparison scored Binance+dYdX highest by a wide margin, on **82.2%
untradeable legs**. That is exactly the REN failure mode `XVF_STRATEGY.md` documents - extreme funding
on volume that could never have been traded. Any venue comparison run without the liquidity floor will
reach the same wrong answer.

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
| 7 | Reject same-venue pairs | fixed 2026-08-18 |
| 8 | Post-only on thinner venue, market hedge on the fill event | built, untested live |
| 9 | Real `referencePrice()` from best bid/ask | fixed 2026-08-18 |
| 10 | Exit path: market both legs at rebalance | **must build** — the only remaining blocker |
| 11 | Bybit gateway (HMAC REST, mirrors Binance) | built, verified live 2026-08-18 |
| 11b | Hyperliquid gateway (EIP-712 signing) | built, verified live 2026-08-18 — see §12 |
| 12 | Entry inside the pre-stamp window | **must build**, cheap, measured 21.9bp vs 26.2bp |
| 13 | Scheduled `xvf-refresh.sh` | done — launchd agent, daily 06:45 |
| 14 | Dry-run default, `-DxvfDryRun=false` to trade | built |

## Out of scope, with where each is recorded

| Item | Worth | Why deferred | Recorded |
| --- | --- | --- | --- |
| dYdX | -0.7 score in this set | marginal venue; pays only when a book is candidate-starved, which this one is not | this file |
| Full Hyperliquid EIP-712 verification suite | correctness confidence | one live cancel round trip is not the same as fuzzing every code path; see §12 | §12 |
| Bin-packed sizing | +1.7pp of return | ~73% deployed at three venues, so it still applies — but v1 proves plumbing first | §7 |
| Hysteresis / early close | largest unclaimed item | needs measurement before design | §12 item 7 |
| Stamp-level entry/exit timing | ~$7/yr on $10k | measured, too small | §4 |
| BNB fee discount | ~$21/yr | deferred, revisit later | §7 |
| Binance USDC contracts | ~$33/yr | second collateral asset not worth it yet | §7 |
| HYPE / DYDX staking | rejected | locks capital, 6% of capital for $7/yr | §7 |
| Stop-loss before liquidation | 2.1% of legs liquidate at 1x | needs design | §12 item 4 |
| `fundingIntervalHours` in the signal | unknown | free live marker, unmeasured | §12 item 14 |
| Funding observations feeding the guard | unblocks stale data | needs the settled-vs-observed comparison | §12 item 14 |

---

## Build order

Each step leaves the system in a state that can be dry-run.

### Done

- ~~**Reject same-venue pairs.**~~ Fixed 2026-08-18. `bestCrossVenuePair` picks the widest combination
  whose legs sit on different venues, rather than a plain max/min that could pair KAITOUSDC against
  KAITOUSDT on Binance. Verified 20 of 20 cross-venue.
- ~~**Schedule `xvf-refresh.sh`.**~~ Done - launchd agent, daily 06:45.
- ~~**Real `referencePrice()`.**~~ Fixed 2026-08-18. Now `topOfBook(symbol).touch(side)`; a resting
  SELL joins the ask and a resting BUY the bid.
- ~~**Bybit gateway.**~~ Built and verified live 2026-08-18: signed reads succeed, `retCode` (not the
  HTTP status) drives ACCEPTED/REJECTED/UNKNOWN, orders build with correct quantity/price.
- ~~**Hyperliquid gateway.**~~ Built and verified live 2026-08-18 - see §12 for what "verified" means
  and does not mean here.
- ~~**Same-venue pairs / referencePrice / all three gateways wired.**~~ Confirmed together: a full dry
  run against all three real credentials placed correctly-sized orders on Bybit, Binance and
  Hyperliquid in one book, imbalance under 0.28% throughout.

### Remaining
1. **Exit path.** Nothing closes a position - grep finds no `reduceOnly`, no close, no unwind
   anywhere in `xvf/`. The 3-day rebalance is a backtest parameter with no runtime counterpart. The
   only remaining blocker: the system can open positions on all three wired venues and close none of
   them programmatically.
2. **Pre-stamp entry window.** Place orders at `HH:57`-`HH:59` before the slower leg's stamp hour;
   Hyperliquid is hourly so the CEX leg sets the timing. Small, measured at 21.9bp against 26.2bp.
3. **Verify the Binance and Bybit user data streams against live accounts.** The entire entry design
   depends on the fill event arriving; neither has run outside dry-run. Hyperliquid's fill accounting
   (`userFills`) and signing were verified live on 2026-08-18 - see §12.
4. **Paper, then minimum size.** $3,000 is the step-rounding floor; $10,000 is comfortable.

Also stale: `dydx` still appears in the unwired-gateway array in `XvfExecutionApplication`. The venue
measurement dropped it, so it should be removed rather than left as a gateway nobody will write.

### Not on this path

The signal Lambda and the `xvf-signal-book` table serve the frozen-book guarantee and a future web
page. Neither closes any blocker above.

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
