# Options-implied forward vs. perpetual funding — research plan (opened 2026-08-20)

This is item 4 in `RESEARCH_OPTIONS.md`. That document already measured the mechanism on BTC alone and
found it real but too thin to trade (~2.3%/yr gross, before any cost). This document is the plan for
using the now-importing Tardis archive to answer the question that measurement left open: **is the
spread wider on the altcoin chains, and can it be traded at all after the same executability problem
that killed short expiries on BTC.**

Written before the import finishes and before any of these numbers are computed. Nothing here is a
result.

## What just changed

`TardisDeribitImportApplication` (new, 2026-08-20) reads the 89 monthly Tardis archives - already
downloaded, 2019-04 through 2026-08, ~24GB - and imports one representative snapshot per month (noon
UTC, last-quote-before-target per instrument) into `deribit_option_quote`, the same table the live
hourly recorder has been writing to since 2026-08-12. V18 widened that table to keep bid/ask size and
all five greeks, which the original schema (V10) did not store and which cannot be re-fetched once the
archives are gone.

Import is running now (`logs/tardis-import.log`). Once it finishes: 89 months x 9 chains (BTC, ETH,
BTC_USDC, ETH_USDC, SOL_USDC, XRP_USDC, TRX_USDC, HYPE_USDC, AVAX_USDC) of quotes, joinable against
Binance/Bybit perp funding that already covers 2020-2026 across 833 symbols with no new import needed.

## Questions, in the order worth answering them

**1. Does the spread persist month over month, on BTC/ETH, across the full history?**
The original measurement was a single point in time (2026-08-12). Twelve consecutive months exist
specifically to check this - autocorrelation of the spread itself, not of funding, which the
`CROSS_VENUE_FUNDING_MEASUREMENT.md` work already flagged as a distinct and easily-conflated question.
If the spread doesn't persist, everything downstream is measuring noise.

**2. Is the spread wider on the altcoin chains than BTC's ~2.3%?**
The single most promising unmeasured question from the original write-up. Altcoin perp funding is far
more variable than BTC's, so the options-vs-perp gap plausibly is too. This is also the highest-risk
question to answer well: the altcoin chains had far thinner quote coverage in the first measurement
(SOL_USDC 53% of strikes with a live bid, XRP_USDC 37%) and are newer, so most of the 89 months may
have little or no altcoin data at all. Coverage has to be checked before the spread numbers are
trusted, not after.

**3. Does executability kill it the same way it did on BTC?**
At 3.6 days the executable forward (sell the call at bid, buy the put at ask) was -288% annualised
against +8.3% at mark - the bid-ask spread on two option legs swamped the trade, and it only survived
at one-to-three-month expiries. Whether that threshold holds on altcoin chains, where spreads are
plausibly wider in both directions (options and perp), is unmeasured. This is the question that turns
"the mark shows a wide gap" into "the gap is real money," and it needs the executable price, not mark.

**4. Which side is persistently rich - perp funding or the options forward?**
Not asked in the original measurement, which reported the gap's size but not its sign's persistence.
If one side is structurally rich across most months and chains, that says something about *why* the
gap exists (a specific participant base overpaying on one venue type) rather than just that it does,
and it determines which leg to hold as the default rather than re-deriving direction every period.

**5. Is this signal related to anything already measured, or independent of it?**
Cross-venue funding spread (item 1, XVF) and cash-and-carry both trade a version of "funding is rich
somewhere." If the options-forward gap correlates with those signals it may be the same underlying
crowding measured a second way, worth less as a diversifier than it looks. If it's orthogonal, it's a
genuinely separate source of edge. Cheap to check once both series exist and not yet done.

**6. What would executing this actually require?**
`DeribitChainSnapshotApplication` is explicitly public-data-only - "no API key, and it places no
orders." There is no Deribit execution gateway of any kind in this codebase. Even a spread that
survives questions 1-4 is a research result, not a tradeable one, until this is scoped - and per
`RESEARCH_OPTIONS.md`'s own "one item at a time" rule, XVF (item 1) is the one currently in a
pre-registered live evaluation window (opened 2026-08-17, 52 weeks). Building Deribit execution before
finishing that evaluation would be starting a second live item, not researching one.

## What "enough to answer" looks like, stated before looking

- Question 1 needs the full 89-month BTC/ETH series - already importing, no additional data required.
- Question 2 needs a coverage check *first*: for each altcoin chain, how many of the 89 months have
  a strike near the money with both a bid and an ask. Chains that fail this on most months get
  reported as "not enough data," not extrapolated from the months that happen to have quotes.
- Question 3 needs the executable-forward calculation (not mark) repeated per chain per expiry bucket,
  the same way the original BTC table did it.
- Question 4 and 5 are the cheapest to compute once 1-3 exist and are ordered after them for that
  reason, not because they matter less.

## Status

Import complete: all 89 months, 169,782 rows, 50.5 minutes.

**Correction (2026-08-20):** the first two results below were originally computed while the import
was still running (partial archive, ~60-82 of 89 months). Re-run on the complete 89-month archive
after finishing; numbers shifted slightly but the qualitative findings - perp structurally rich, ETH
more persistent than BTC - held. Figures below are the corrected, final ones. Recorded as a correction
rather than silently overwritten, matching item 7 of `RESEARCH_OPTIONS.md`'s own standing rules about
verifying joins and coverage before trusting a result.

## Question 1, BTC: persistent but not predictable

ATM strike, executable forward (sell call at bid, buy put at ask - matches the original methodology),
against Binance BTCUSDT funding on the same day. Two expiry windows checked independently so a result
isn't mistaken for a short-expiry artifact, which the original measurement already showed can produce
numbers up to two orders of magnitude off:

| Window | Months | Mean spread | Median spread | Positive months | Lag-1 autocorr |
| --- | ---: | ---: | ---: | ---: | ---: |
| 15-60 days | 80 | +14.88% | +11.65% | 74/80 (93%) | 0.22 |
| 60-150 days | 80 | +13.85% | +10.70% | 73/80 (91%) | 0.21 |

**Perp funding has been richer than the options-implied forward in roughly 92% of months since 2020.**
Both windows agree, which rules out the result being a short-expiry crossing-cost artifact. The
negative months cluster around the COVID crash. This answers question 4 ahead of schedule: the rich
side is structurally the perp, not the options market, at least for BTC.

**The size is less predictable than the sign is stable.** Lag-1 autocorrelation of ~0.21-0.22 is
noticeably weaker than the ~0.42-0.46 weekly autocorrelation already measured for cash-and-carry and
the cross-venue funding spread (not a clean comparison, monthly vs weekly, but the gap is large enough
to note). Knowing the spread was wide last month says a little, but not much, about how wide it'll be
this month - mostly that it'll probably still be positive.

**Open tension, not yet resolved.** The historical average here (+11 to +15%/yr) is well above the
single live point measurement from 2026-08-12 (+2.3%/yr, executable, 135-day expiry). Two explanations,
not yet distinguished: today's market may simply be in a tighter regime than the last five years'
average (2021 in particular ran extremely hot - April 2021 alone hit +97.78%), or there's a
methodological difference between this monthly ATM-nearest-expiry scan and the original single-day,
specific-listed-expiry measurement worth checking directly against each other on the same date.

## Question 1, ETH: same magnitude, meaningfully more persistent

Identical methodology, ETH underlying against Binance ETHUSDT funding, both expiry windows:

| Window | Months | Mean spread | Median spread | Positive months | Lag-1 autocorr |
| --- | ---: | ---: | ---: | ---: | ---: |
| 15-60 days | 80 | +17.22% | +12.42% | 73/80 (91%) | **0.45** |
| 60-150 days | 80 | +15.69% | +11.41% | 72/80 (90%) | **0.41** |

The mean, median and positive-month rate are close to BTC's - same structural finding, perp funding is
the rich side, roughly as often and by roughly as much. **What differs is persistence.** ETH's lag-1
autocorrelation (0.41-0.45) is roughly double BTC's (0.21-0.22) and lands close to the ~0.42-0.46
weekly autocorrelation already measured for cash-and-carry and cross-venue funding - notable given
this is a monthly lag, not weekly, so ETH's spread is holding onto information from one month to the
next about as well as those other signals hold it week to week. If forced to pick one chain to dig
into further on tradeability, this makes ETH the more promising candidate despite similar average size
- a spread that's usually about as wide as it was last month is more useful for real-time sizing than
one whose sign is stable but whose magnitude is closer to a coin flip.

## Question 3: does crossing cost eat the spread at these expiries?

The original measurement's headline warning: at 3.6 days, the executable forward was -288% annualised
against +8.3% at mark - crossing cost alone flipped the sign and swamped everything. Checked directly
at 60-150 days, mark price vs executable (crossing bid/ask), full archive:

| Chain | Avg executable spread | Avg mark spread | Crossing cost | Sign flips |
| --- | ---: | ---: | ---: | ---: |
| BTC | +13.85% | +12.74% | -1.12 pts | 3/80 |
| ETH | +15.69% | +14.01% | -1.67 pts | 1/80 |

**At 60-150 days, crossing cost is small - about 1-2 annualised points, not the ~300-point swing seen
at 3.6 days - and it costs the trade, but never comes close to erasing it.** Mark and executable agree
on sign in 96-99% of months. This is the reassuring answer: the spread found in questions 1-2 is not a
mark-price mirage that crossing costs would eat. It survives execution friction at these expiries.
What it does not yet answer: Deribit's own option fees, perp fees on the funding leg, and margin
requirements across two venues - real costs, not yet subtracted from any number above.

## Question 5: is this a new signal, or the same one already traded?

Not a new signal. Correlated the actual options-vs-perp spread (not raw funding - the tradeable
quantity) against the broad-market average trailing funding across all Binance perps at the same 79
monthly dates, the same factor cash-and-carry ranks symbols on:

| | vs market-wide funding | vs each other |
| --- | ---: | ---: |
| BTC spread | 0.797 | **0.904** |
| ETH spread | 0.796 | (BTC vs ETH) |

**Both chains' spreads move almost in lockstep with each other (0.904) and both track the same broad
funding regime cash-and-carry already trades (~0.8).** This is the answer the plan flagged as mattering
more than it looks: a strategy trading this options-forward gap on BTC and ETH is not a diversifier
against cash-and-carry or, by extension, against XVF's own dependence on funding being rich somewhere
- it is largely the same underlying bet ("is crypto funding elevated right now") expressed a third way.
Running all three together would concentrate risk into one regime factor, not spread it.

This does not refute the mechanism - the spread is still real, still structurally positive, still
survives crossing cost (questions 1-3). It refutes treating it as independent capacity or a hedge
against the other two strategies going through a bad funding regime at the same time.

## Question 6: what would executing this actually require

Not scoped in any depth yet, and per `RESEARCH_OPTIONS.md`'s own "one item at a time" framing this is
exactly the boundary between researching an item and starting it. Stated plainly rather than built:

- **No Deribit execution gateway exists.** `DeribitChainSnapshotApplication` is explicitly
  public-data-only. Options order placement, Deribit-side auth (API key/secret, distinct from the
  public endpoints this session's importer and the live recorder use), and margin/collateral handling
  for an options position are all unbuilt.
- **Two-venue margin.** The forward leg is Deribit options; the funding leg is a Binance perp, which
  this codebase already has a working gateway for (`BinanceGateway`). But collateral sits on two
  separate venues with no cross-margining between them - capital efficiency is worse than a single-
  venue strategy by construction, the same shape of problem `CARRY_PREREGISTRATION.md` hit with its
  spot leg, just on the options side instead.
- **Position sizing at 60-150 day expiries** means holding through the full period to realize the
  measured spread, or unwinding early into whatever the market is offering then - the persistence
  numbers in question 1 describe month-start snapshots, not the path between them, which is exactly
  the gap the live hourly recorder (running since 2026-08-12) is positioned to eventually fill and the
  monthly archive cannot.

None of this is disqualifying. It's the cost of finding out whether the mechanism survives contact
with real order books, real margin, and a real two-venue operation - not yet paid.

## Question 2 (2026-08-20): altcoin coverage, then the opposite of the hypothesized answer

Import finished: 169,782 rows, all 89 months, 50.5 minutes. Coverage check first, per the plan -
months present with a genuine ATM call+put pair, both sides quoted, 15-150 day expiries:

| Chain | Months present | Usable | Verdict |
| --- | ---: | ---: | --- |
| XRP_USDC | 29 | 29 (100%) | usable |
| SOL_USDC | 30 | 29 (97%) | usable |
| AVAX_USDC | 12 | 7 (58%) | too patchy to trust |
| TRX_USDC | 12 | 7 (58%) | too patchy to trust |
| HYPE_USDC | 2 | 2 | not enough history yet |

Only SOL and XRP cleared the bar. Ran the same spread measurement on both (linear parity - these are
USDC-settled, so no `x underlying_price` multiplier, unlike BTC/ETH's inverse quoting; getting this
wrong would misprice the forward by exactly the spot price, which is the mistake V10's schema comment
warns about):

| Chain | Months | Mean spread | Median spread | Positive months |
| --- | ---: | ---: | ---: | ---: |
| SOL_USDC | 29 | +2.46% | +2.03% | 19/29 (66%) |
| XRP_USDC | 29 | +4.08% | +3.62% | 20/29 (69%) |

**This is the opposite of the hypothesis in the original plan** ("altcoin perp funding is far more
variable than BTC's, so the options-vs-perp gap plausibly is too"). SOL and XRP's spreads are smaller
and less consistently positive than BTC/ETH's, not larger.

Before trusting that, checked for the obvious confound: SOL/XRP only cover 2024-2026, a much shorter
and calmer window than BTC/ETH's full 2019-2026 history, which includes 2021's euphoria. Recomputed
BTC/ETH restricted to the same 2024-03 onward window:

| Chain | Months | Mean spread | Median spread | Positive months |
| --- | ---: | ---: | ---: | ---: |
| BTC (2024-03+) | 30 | +9.92% | +5.89% | 27/30 (90%) |
| ETH (2024-03+) | 30 | +9.17% | +5.78% | 25/30 (83%) |

The confound is real - BTC/ETH's own spread more than halves once matched to the same recent window,
from the 2019-2026 figures reported above. But it doesn't close the gap: time-matched, BTC/ETH still
run roughly 2-4x the mean spread of SOL/XRP, and stay positive noticeably more often (83-90% vs
65-69%). **The finding survives the confound check.** Majors show a larger, more reliably positive
options-vs-perp gap than the two altcoins with enough data to measure, in the same period.

**What this means for the plan:** questions 3 (executability) and 5 (correlation with XVF/carry) are
now more interesting on BTC/ETH than on the altcoin chains - the opposite of what the plan expected
going in. AVAX and TRX stay open questions rather than closed ones; 7 months each isn't enough to
conclude they behave like SOL/XRP, only that they can't yet be measured with confidence either way.

---

# Variance risk premium — tested 2026-08-20, and it is not there

Ran because the correlation finding above (questions 5) showed the options-forward spread is the same
funding-regime bet already traded twice. The variance risk premium is a different mechanism entirely -
being paid to bear volatility risk, not to hold a funding-rich leg - and the data to test it was
already imported, so the test cost nothing but query time.

**Method.** ATM mark IV at each month-start snapshot (nearest-to-30-day expiry, 20-45 day band),
against realized vol over that option's OWN remaining life, from daily closes. Both measured
annualised in vol points. A first pass using hourly returns over a fixed 30-day window gave a slightly
friendlier answer; it is not reported as the result because the horizon mismatch and hourly
microstructure noise both flatter the seller, and the corrected version below is the honest one.

| Chain | Months | Avg IV | Avg RV | **Avg VRP** | Median VRP | p10 VRP | Worst | Months positive |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| BTC | 79 | 57.52 | 58.29 | **-0.77** | +4.13 | -20.46 | **-187.65** | 48/79 (61%) |
| ETH | 79 | 71.04 | 77.79 | **-6.75** | -2.20 | -36.48 | **-180.81** | 35/79 (44%) |

**There is no harvestable variance risk premium in crypto ATM options over this period.** BTC's mean
is slightly negative with a positive median; ETH is negative on both. The gap between mean and median
is the whole story: a seller collects a few points most months and then loses 180+ vol points in one.
That is precisely the "smooth stream, then give back a multiple at once" shape `RESEARCH_OPTIONS.md`
already flags as the thing to detect before committing capital, measured here rather than feared.

This is also the *optimistic* version of the number. Mark IV is not executable - a seller receives bid
IV, below mark - so real-world selling is worse than the table.

**The independence test, which the premium failed to earn.** VRP correlates -0.024 (BTC) and -0.080
(ETH) with the market-wide funding regime: genuinely orthogonal, unlike the options-forward spread's
~0.8. So the factor *is* the diversifier that was wanted. It just does not pay. BTC and ETH VRP
correlate 0.858 with each other, so the two chains are one bet, not two.

**One inversion worth stating, not chasing.** A negative average VRP means realized vol exceeded
implied - crypto options have on average *underpriced* volatility here, so the profitable side was
buying vol, not selling it. That is not adoptable under this project's own standing rules: it is a
negative-carry position that requires a forecast to come true, which is the family that has been
refuted sixteen times, and the result is driven by a handful of extreme months rather than a
persistent payment.

**Status: refuted before any build.** Cost: two SQL queries against data already imported.
