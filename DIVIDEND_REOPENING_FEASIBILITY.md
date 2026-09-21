# Equity-perp dividend reopening — outcome-blind feasibility audit

Audit completed 2026-09-12 15:43 UTC. No event-window price, basis or return was
loaded or inspected during this audit.

## Question

Can Binance dividend adjustments be identified independently of outcomes, and is there
enough historical high-resolution data to freeze and run one honest retrospective screen?

## Official mechanism

For US equity perpetuals, Binance documents the following New York-time sequence on the
calendar day before the ex-dividend date:

| Time | Action |
|---|---|
| 15:30 | Change funding interval to one hour, effective 16:00 |
| 19:30 | Enter reduce-only mode and tighten mark/index deviation to 1% |
| 20:00 | Apply special dividend funding immediately after ordinary funding |
| 20:01 | Leave reduce-only mode and restore ordinary limits and cadence |

For an ordinary cash dividend, the special rate is `-D/M`: gross dividend per share divided
by the real-time mark price, with the ordinary funding cap bypassed. Source:
<https://www.binance.com/en/support/faq/detail/7ced719b5e9a4859a1864c2fe657309f>.

## Clean database fingerprint

The special settlement is not merged with ordinary funding in our database. Binance writes
it as a second negative row milliseconds to about one second after the ordinary 20:00 row,
so the exact `(symbol, funding_time)` primary key preserves both.

The frozen fingerprint requires all of the following:

1. Symbol belongs to the 155-instrument Binance `TRADIFI_PERPETUAL`,
   `underlyingType=EQUITY`, USDT universe frozen for the earnings study.
2. Five distinct hourly funding buckets exist from 16:00 through 20:00 New York.
3. A negative sixth row follows the ordinary 20:00 settlement by more than zero and no more
   than five seconds.
4. The event is earlier than the frozen `2026-09-12T00:00:00Z` data cutoff.

This is materially stronger than looking for a large negative rate. Ordinary equity-perp
funding can also be large, and some crypto contracts temporarily settle hourly. The second
20:00 row plus the declared five-hour cadence identifies the exchange action itself.

Two unfiltered near-simultaneous rows, `HYUNDAIUSDT` and `SKHYNIXUSDT`, are deliberately
excluded. They settle at 08:00 Korea time and belong to the distinct Korean workflow, not
the frozen US-equity population.

## Inventory result

The reproducible outcome-blind inventory contains:

- **51 adjustment events**;
- **41 symbols**;
- **32 independent New York process dates**;
- **49 distinct symbol-month archive pairs**;
- process dates from **2026-05-10 through 2026-09-09**;
- median absolute special adjustment **23.73 bp**, range **1.46–123.65 bp**.

This clears the basic feasibility question: there are enough dates for a de-clustered screen.
It does not say that a post-reopening residual exists.

Reproduction:

- script: `scripts/analysis-dividend-reopening-audit.py`;
- frozen output: `research/dividend-reopening-feasibility-2026-09-12.json`;
- output SHA-256: `7294a753d8a3b1300474cb876bdd07e5ccff403cf0cbbb165adaf8371ec20786`;
- source-universe SHA-256:
  `f318b8cf40fe06e78d4a5d2d88be8b98a3d0060ffecedd49e86e0a8727ac0d00`.

## Data coverage and gaps

At the same cutoff, the frozen equity universe has 42,980 funding rows across all 155
symbols. **Zero carry a historical mark price.** Archive and REST funding sources preserve
the special rate and timestamp but not `M`.

Stored candles are:

| Interval | Rows | Symbols | Coverage |
|---|---:|---:|---|
| 1h | 338,702 | 155 | 2026-01-28 14:00 through 2026-09-11 23:00 UTC |
| 1m | 411,660 | 25 | partial, unrelated targeted imports |

Exactly **zero of the 51 event windows currently have stored 1-minute candles**. The 1h
series cannot resolve a 20:01 reopening effect.

An outcome-blind HEAD audit checked the complete primary and possible-control object plan
across the three public 1-minute streams needed by the study, including both UTC dates when
the New York event window crosses midnight:

- executed-trade 1-minute klines;
- index-price 1-minute klines;
- mark-price 1-minute klines.

All **171 primary-scoped archive objects** and all **171 checksums** returned HTTP 200,
totaling 116,634,373 compressed bytes (about 111 MiB). The frozen 56-day matching pool adds
106 control-scoped trade objects; 96 exist and ten older months return 404 because those
contracts had not yet produced an archive. The scopes overlap, so the complete plan contains
245 unique objects, of which 235 exist and total 172,591,677 bytes (about 165 MiB). All 310
symbol-date control candidates remain declared; absent source data makes a control ineligible
and never removes a primary event. Completed May-August periods use monthly objects;
September uses completed UTC daily objects because the month is not complete.

Reproduction:

- script: `scripts/check-dividend-reopening-archives.py`;
- frozen output: `research/dividend-reopening-archive-availability-2026-09-12.json`;
- output SHA-256: `5549244bf26ebee72962f88c3a150ed58b1e4ae0aabb7f166d76458b6a29e8a6`.

Historical bid/ask cannot be recovered from Binance's public `bookTicker` archive for these
2026 events: that archive stopped in 2024. Therefore a retrospective result can use trade
prices plus a deliberately conservative round-trip cost, but it cannot claim observed fills
or spreads. Any discovery-screen pass must proceed to prospective L1 shadow collection.

## Method correction before preregistration

Perp/index basis cannot be the primary signal. Since 2026-05-16, Binance may construct the
equity-perp index from the same order book using an EWMA during maintenance and other
non-trading periods, and it does not expose a historical mode flag. A small basis can
therefore be tautological and apparent convergence can be the index following the perp.
Source: <https://www.binance.com/en/support/announcement/detail/53bfc17634f54f2f90666dbc396f5cee>.

The defensible primary candidate is instead the **dividend-adjusted boundary displacement**:
take the executed-price move from a pre-settlement bar to the completed post-reopening bar,
add back the cash amount implied by the isolated special funding rate and settlement mark,
then fade only a large remaining displacement. This is not claimed to be deterministic fair
value: legitimate news can move the stock during the same interval. The non-event control
and subsequent reversal are therefore required. Binance index data remains diagnostic only
unless an independent underlying quote proves it was externally sourced at that timestamp.

Binance also documents up to 15 seconds of deviation in actual funding transaction time.
The frozen pre-settlement bar must end early enough not to straddle that uncertainty. Source:
<https://www.binance.com/en/support/faq/detail/360033525031>.

The proposed trade must also open after 20:01. It receives no special dividend funding.
Otherwise this would collapse back into the funding family the user has already closed.

## Feasibility verdict and measured result

The outcome-blind feasibility gate passed and the full design was frozen before archive bodies
were downloaded. The subsequent one-look result **failed**: 6 signals / 6 dates, one execution
failure and five executed trades with -4.7 bp gross / -29.7 bp after the frozen 25 bp cost,
t=-0.81. Signal attrition was 32%, no same-regime control met the frozen gap caliper, and the
only winner supplied 100% of positive P&L. See `DIVIDEND_REOPENING_RESULT.md`. Board #17 is
closed; the result does not authorize shadow or live trading and must not be retuned on this
dataset.
