# XVF v1 — scope

What the first version implements, what it deliberately leaves out, and why.

`XVF_STRATEGY.md` is what the strategy is. `XVF_IMPLEMENTATION.md` is how the full thing works and
everything measured about it. This is the much smaller subset being built first.

**Purpose of v1: prove the execution path with real money at minimum size, and start the
pre-registered forward test.** It is not to maximise return. Every optimisation that has been measured
is deferred, because none of them matters if the plumbing does not work.

---

## Decision: Binance and Bybit only

Two venues, not four. This is the decision that shapes everything else.

### The capital argument, which is decisive

With two venues, **every pair has exactly one leg on each**. Measured over 147 weeks, Binance holds 20
legs and Bybit holds 20 legs — p50, p90 and worst case, all identical:

| Venue | p50 | p90 | worst |
| --- | ---: | ---: | ---: |
| binance | 20 | 20 | 20 |
| bybit | 20 | 20 | 20 |

The entire capital allocation problem disappears. No 1.53x funding multiple, no idle buffer, no
bin-packing, no rebalancing between venues. **50/50, 100% deployed, permanently.**

Against the four-venue configuration at 65.6% deployment, that recovers a third of the capital — which
is worth more than the wider book costs:

| | 4 venues | 2 venues |
| --- | ---: | ---: |
| Candidates over 20% per week | 79.0 | 32.4 |
| Weeks with a full 20-pair book | 90.5% | 54.4% |
| Median selected spread | 36% | 33% |
| Capital deployed | 65.6% | **100%** |

32.4 candidates against 20 needed is ample, and the median spread barely moves.

### The engineering argument, which agrees

Binance and Bybit both authenticate with HMAC-SHA256 over a REST query string. `BinanceGateway` is
already written and Bybit is the same shape. Hyperliquid needs EIP-712 wallet signing and dYdX needs
Cosmos SDK transaction signing — two unfamiliar auth models, each capable of losing money in novel
ways, on the two thinnest venues.

Both CEXs also settle in USDT, so v1 has one collateral asset, no stablecoin conversion, and no
on-chain withdrawal in the loop.

---

## In scope

| # | Item | State |
| --- | --- | --- |
| 1 | Binance + Bybit only, `XvfConfig.VENUES` reduced | change |
| 2 | Capital split 50/50, 100% deployed, leg notional = `capital / 40` | no code |
| 3 | USDT collateral throughout | `XvfConfig.collateral` already correct |
| 4 | Equal sizing | already the default |
| 5 | Signal: 7-day trailing, >20% entry, top 20, 3-day rebalance | built |
| 6 | Freshness guard counting usable symbols | built, verified refusing |
| 7 | Reject same-venue pairs | **must fix** — §12 item 11 |
| 8 | Post-only on thinner venue, market hedge on the fill event | built, untested live |
| 9 | Real `referencePrice()` from best bid/ask | **must build** — §12 item 3 |
| 10 | Exit path: market both legs at rebalance | **must build** — §12 item 1 |
| 11 | Bybit gateway | **must build** |
| 12 | Entry inside the pre-stamp window | **must build**, cheap, measured 21.9bp vs 26.2bp |
| 13 | Scheduled `xvf-refresh.sh` | **must build** — guard currently refuses on stale data |
| 14 | Dry-run default, `-DxvfDryRun=false` to trade | built |

## Out of scope, with where each is recorded

| Item | Worth | Why deferred | Recorded |
| --- | --- | --- | --- |
| Hyperliquid + dYdX gateways | 65.7% of the 4-venue book | unfamiliar auth models; v1 proves plumbing first | this file |
| Bin-packed sizing | +1.7pp of return | irrelevant at 2 venues — nothing is idle | §7 |
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
   `wss://stream.bybit.com/v5/private`.
5. **Exit path.** Market both legs, close the whole book at the rebalance. No cleverness.
6. **Pre-stamp entry window.** Place orders at `HH:57`–`HH:59` before a Binance/Bybit stamp hour.
7. **Verify the Binance user data stream against a live account.** The entire entry design depends on
   the fill event arriving; it has never run.
8. **Paper, then minimum size.** $3,000 is the step-rounding floor; $10,000 is comfortable.

---

## What v1 will not prove

It runs half the strategy. The measured 19% gross comes from a four-venue book, and a two-venue book
is a different, thinner selection whose forward return has not been separately backtested — the
comparison above is on candidate counts and spreads, not on realised return.

It also inherits every unmeasured risk in §12: adverse selection on maker fills, survivorship in the
venue universes, and the reconciliation problem — this project has produced 7.5%, 10.98%, 18.5%,
19.0%, 19.6%, 22.0% and 28% from different pipelines and they have never been collapsed into one
number from one code path.

v1 is a plumbing test that happens to carry real money. Treat its return as an execution measurement,
not as evidence about the strategy.
