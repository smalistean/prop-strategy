# XVF calculations — independent review

**Review date:** 2026-08-21  
**Scope:** `XVF_CALCULATIONS_REVIEW.md`, the current XVF signal and execution code, the supporting
analysis scripts, the AWS signal path, and the live position snapshot.  
**Change policy:** this review is documentation only. No existing source, configuration, strategy,
status, roadmap, or research file was changed as part of it.

## Executive conclusion

The live funding payments are not materially smaller than the displayed approximately 20% annualized
signal. The largest apparent discrepancy is created by comparing two different denominators:

- the XVF signal is an annualized funding spread measured against **one matched leg's notional**;
- the live review annualized the funding cash against **the gross notional of both legs**.

That creates an artificial factor-of-two shortfall. A 20% one-leg spread is a 10% gross return on two
equal legs before trading fees, basis movement, idle collateral, and other costs.

The current live book is approximately consistent with a 16-17% one-leg annualized spread after only
about ten hours. That is somewhat below 20%, but it is not the approximately 2x miss claimed in the
existing calculations review.

There are, however, genuine issues elsewhere:

1. the research used to calibrate the 0.5 stale-signal discount overlaps the signal day with the
   supposedly forward outcome;
2. the lookback experiment does not reproduce the production pair-selection logic;
3. the capital simulation understates some venue fees and differs from production in several material
   ways;
4. the AWS/dashboard ranker and the local execution ranker are currently different strategies;
5. the 20% threshold can be uneconomic for a three-day Binance-Bybit trade after measured fees.

The observed funding amount is therefore not the main problem. Signal provenance, research validity,
and cost-adjusted entry criteria are the more important next questions.

## 1. Three different numbers are being called "20%"

The documentation currently allows three concepts to be confused:

1. **Candidate funding spread.** `MIN_SPREAD_ANNUAL_PCT = 20` is an entry threshold applied to the
   annualized difference between the two venues' trailing funding. This is a one-leg measure.
2. **Portfolio return.** Older XVF documents contain approximately 18-22% portfolio backtest results.
   Those are outputs from different exploratory pipelines and are not the same thing as the 20% entry
   threshold.
3. **Real funding cash.** The venue reports dollars or cents actually paid at each funding stamp. This
   depends on notional, elapsed time, position side, and funding cadence.

`XVF_STRATEGY.md` itself records that the project has produced 7.5%, 10.98%, 18.5%, 19.0%, 19.6%,
22.0%, and 28% from pipelines that have not been reconciled into one number. Those older headline
returns should not be treated as a live promise.

## 2. Correct denominator for the XVF signal

`XvfSignalEngine.Leg.annualPct()` annualizes each venue's trailing funding sum. The selected candidate
then stores:

```text
spreadAnnualPct = shortLegAnnualPct - longLegAnnualPct
```

See:

- `src/main/java/com/smalistean/propstrategy/xvf/signal/XvfSignalEngine.java`, lines 32-38;
- the pair scan in the same file, lines 325-347.

For two equal legs with notional `N`:

```text
short-leg funding cash  =  N * short funding rate
long-leg funding cash   = -N * long funding rate
pair funding cash       =  N * (short rate - long rate)
gross two-leg notional  =  2N
```

Therefore:

```text
return on gross two-leg notional = displayed spread / 2
```

Examples:

| Displayed one-leg spread | Gross annual return on two equal legs |
|---:|---:|
| 20% | 10% |
| 25% | 12.5% |
| 30% | 15% |

This convention was handled correctly in
`CROSS_VENUE_FUNDING_PREREGISTRATION.md`: 14.2% on one-leg notional becomes 7.1% on two-leg capital.
That document also explicitly forbids reporting the one-leg return without the two-leg figure beside
it. The later calculations review lost that reporting discipline.

## 3. Reconciliation with the current live book

`XVF_LIVE_BOOK.md` reports, as of 2026-08-21 05:51 UTC:

- 20 matched pairs and 40 legs;
- approximately USD 3,304 gross notional;
- approximately USD 0.316 funding;
- oldest entry at 19:31 UTC and newest entry at 20:05 UTC the previous day.

The matched one-side notional is approximately:

```text
3,303.95 / 2 = 1,651.98 USD
```

At exactly a 20% annualized spread, the continuous-time approximation for ten hours is:

```text
expected funding
= 1,651.98 * 0.20 * 10 / 8,760
= 0.377 USD
```

The observed funding implies:

```text
one-leg-equivalent annualized spread
= 0.316 / 1,651.98 * 8,760 / 10
= approximately 16.8%
```

Using the oldest and newest entry times as bounds rather than exactly ten hours gives approximately
16.2-17.2%. The precise calculation needs time-weighted position notionals and exact funding stamps,
but the conclusion is unchanged: the live funding is reasonably close to a 20% signal after the
correct denominator is used.

The same correction applies to the earlier example in `XVF_CALCULATIONS_REVIEW.md`, lines 110-113:

```text
reported calculation:
0.80 / 3,056 * 365 = 9.6%

comparison on the signal's denominator:
0.80 / (3,056 / 2) * 365 = 19.1%
```

The 19.1% result is close to the 20% entry floor. Consequently, that live observation does **not**
confirm that stale candidates realize only half their displayed signal.

The incorrect live-confirmation statement is also repeated in:

- `src/main/java/com/smalistean/propstrategy/xvf/XvfConfig.java`, lines 75-83;
- `src/main/java/com/smalistean/propstrategy/xvf/signal/XvfSignalEngine.java`, lines 163-178;
- the header of `scripts/analysis-freshness-discount.sql`.

## 4. Why individual funding payments are only cents

A 20% annualized spread corresponds to:

```text
per day = 20% / 365 = 0.0548%
```

For an USD 85 matched leg:

```text
annual funding      = 85 * 20%          = 17.00 USD
daily funding       = 85 * 20% / 365    = 0.0466 USD
three-day funding   = 85 * 20% * 3/365  = 0.1397 USD
```

If the same annualized return were distributed evenly:

| Funding cadence | Rate per payment | Cash on an USD 85 leg |
|---|---:|---:|
| hourly | 0.00228% | USD 0.0019 |
| every 4 hours | 0.00913% | USD 0.0078 |
| every 8 hours | 0.01826% | USD 0.0155 |

The real payments will not be evenly distributed, but this shows the expected scale. An annualized
percentage can look large while each funding stamp is only a fraction of a cent or a few cents.

The displayed spread is also the **net difference between both legs**. It does not mean that each leg
individually earns 20%. A leg can pay funding, the opposite leg can receive it, and individual pair
stamps can be negative even when the complete holding period is positive. Different 1-hour, 4-hour,
and 8-hour schedules make a ten-hour snapshot especially noisy.

At exactly 20%, the current USD 3,304 gross book would be expected to earn approximately:

```text
(3,304 / 2) * 20% * 3/365 = 2.72 USD
```

over a complete three-day hold, before costs.

## 5. Finding: same-day leakage in freshness calibration

In `scripts/analysis-freshness-discount.sql`, lines 49-54:

```sql
back3 = ROWS BETWEEN 2 PRECEDING AND CURRENT ROW
back7 = ROWS BETWEEN 6 PRECEDING AND CURRENT ROW
fwd3  = ROWS BETWEEN CURRENT ROW AND 2 FOLLOWING
fwd7  = ROWS BETWEEN CURRENT ROW AND 6 FOLLOWING
```

Day `D` is in both the trailing signal and the forward outcome. For a three-day outcome, one of the
three nominally forward days has already been observed by the signal. The same structure appears in
`scripts/analysis-lookback-cadence.sql`.

This is especially important for the "first day eligible" bucket. A large funding print on day `D`
can both cause a candidate to cross the 20% threshold and be counted as part of what the candidate
subsequently realized. That can materially inflate the reported 99% first-day calibration.

The corrected research boundary should be one of:

```text
signal uses dates strictly before D; outcome starts at D
```

or:

```text
signal includes D; outcome starts at D + 1
```

The exact 0.5 stale-signal multiplier should be considered unverified until this is rerun. It may
still be a useful conservative control; this finding is not evidence that it should be removed before
the corrected measurement exists.

## 6. Finding: lookback analysis does not reproduce production pairing

`scripts/analysis-lookback-cadence.sql`, lines 66-80, uses the maximum and minimum venue rates for a
base and labels a row CEX-DEX whenever Hyperliquid is present anywhere in the available venue set.

That can label a Binance-Bybit selected pair as CEX-DEX merely because a Hyperliquid leg also exists.
It also does not perform the same explicit pairwise, pair-type-specific lookback scan used by
`XvfSignalEngine.bestCrossVenuePair()`.

The published 61.3% three-day versus 50.4% seven-day CEX-CEX result therefore should not be treated as
a reproduction of production behavior. It needs to be rerun using the same pair enumeration,
cross-venue restriction, pair-type classification, completeness filter, and ranking order as the
production engine.

## 7. Finding: capital simulation differs materially from production

`scripts/xvf-capital-simulation.py` is useful as an operational scenario, but its claim to use real
fees and exact production ranking is too strong.

### Fee mismatch

The simulator uses one fee schedule for every venue:

```text
maker = 1.8 bp
taker = 5.0 bp
```

Live fills in `XVF_LIVE_FINDINGS.md` measured:

| Venue | Maker | Taker |
|---|---:|---:|
| Binance | not established in the cited sample | 4.5 bp |
| Bybit | 3.6 bp | 10.0 bp |
| Hyperliquid | not established in the cited table | 4.5 bp |

The current book is heavily exposed to Bybit, so the 7.79% and 1.05% simulated annual results are
optimistic on fees.

### Other production mismatches

- Missing daily funding rows are silently converted to zero at
  `scripts/xvf-capital-simulation.py`, lines 101-104. Missing observations should fail or exclude the
  affected trade rather than silently assert a zero rate.
- `scripts/analysis-capital-simulation-export.sql` exports only ranks 1-20. Production uses an
  uncapped book and walks below rank 20 when a candidate cannot be opened.
- The export does not reproduce the production payment-count completeness filter.
- Row-based windows can reach further back in calendar time when a symbol has missing days.
- Weekly volume is calculated using the full calendar week's data, including later days for signals
  early in that week. Production instead uses live 24-hour volume multiplied by seven.
- A large skip count is not, by itself, proof that venue capital is the largest PnL drag. That claim
  needs a no-constraint or rank-backfill counterfactual.

These defects have mixed directions: understated fees are optimistic, while failure to backfill below
rank 20 is pessimistic about deployment. The net bias is therefore not known without a corrected run.

## 8. Finding: AWS/dashboard and execution are different signal paths

The AWS `XvfSignalHandler`:

- reads pending observations from DynamoDB;
- uses one configured lookback, defaulting to seven days;
- does not apply the local stale-signal discount;
- freezes a book in the AWS signal table.

The local `XvfExecutionApplication` currently:

- calls `XvfSignalEngine` again at startup;
- reads settled funding from `perp_funding_all` in PostgreSQL;
- uses a three-day lookback for CEX-CEX and seven days for CEX-DEX;
- applies the 0.5 stale-candidate discount;
- does not consume the frozen AWS `signalRunId`.

The AWS class documents this divergence itself at
`aws/recorder/src/main/java/com/smalistean/propstrategy/aws/XvfSignalHandler.java`, lines 31-38.

The web UI labels the AWS value only as `spread %`. Therefore a dashboard value cannot presently be
compared directly with a locally opened position. It may come from different observations, a
different cutoff, a different lookback, and different adjustment logic.

## 9. Finding: execution can use a stale historical cutoff

`XvfExecutionApplication` calls the signal with `LocalDate.now()`. The SQL in `XvfSignalEngine` ends
the funding window at:

```sql
funding_time <= asOf::date
```

A book opened around 20:00 UTC can therefore be selected using settled funding ending around the
start of that date, almost twenty hours earlier. `LocalDate.now()` also uses the process timezone
rather than expressing the cutoff explicitly in UTC.

The trailing historical signal remains useful for ranking, but it is not a forecast of the next
payment. A pre-entry guard should compare the selected pair with the latest pending rates and exact
next funding stamps in `venue_funding_observation` or the DynamoDB source.

## 10. Finding: there is no frozen per-position forecast to audit

The position snapshot reconstructs actual venue positions, fills, commissions, and funding payments.
It does not contain the entry-time signal that caused each pair to open.

For each pair, the system currently lacks a durable record tying together:

- signal run ID and strategy/configuration version;
- signal cutoff and source watermarks;
- raw trailing rates for both legs;
- raw spread and discounted ranking score;
- current pending rates and next funding stamps at entry;
- intended hold and leg notionals;
- expected funding cash over that hold;
- realized funding, trading fees, and basis PnL.

Without this record, the current aggregate calculation can check whether the book is broadly on pace,
but it cannot answer whether an individual pair delivered the forecast that opened it.

## 11. The genuine economic concern is trading cost

Funding and trading commissions must be kept separate:

- current funding income: approximately USD 0.32;
- current entry commissions: approximately USD 1.45;
- current unrealized price/basis term: approximately USD -1.37.

The funding amount can be correct while the complete trade remains uneconomic.

At the 20% entry threshold, a three-day CEX-CEX hold captures:

```text
20% * 3/365 = 0.164% = 16.4 bp
```

A Binance-Bybit round trip costs approximately:

```text
Bybit maker entry + Binance taker entry + both legs taker exit
= 3.6 + 4.5 + 10.0 + 4.5
= 22.6 bp
```

If all fills are taker, the measured round-trip cost is 29 bp. Both exceed the 16.4 bp funding
expected at the threshold, before slippage or basis movement.

The commission-only break-even annualized spread for a three-day Binance-Bybit hold using the 22.6 bp
maker/taker path is:

```text
22.6 bp * 365/3 = approximately 27.5%
```

The entry floor should therefore ultimately be pair-type-, venue-, hold-, and fee-aware rather than a
single unconditional 20% number.

## 12. Reporting formulas recommended for validation

### Realized funding spread

For a portfolio with changing notionals and staggered entries, use matched one-side notional-hours:

```text
matched notional for pair at time t
= min(abs(short-leg notional), abs(long-leg notional))

realized annualized funding spread
= total net funding USD / total matched-notional-hours * 8,760
```

This should be shown separately from:

```text
funding return on deployed gross notional
net return on total account equity
```

### Expected funding cash

For each planned funding stamp during the hold:

```text
expected pair funding
= short-leg notional * short funding rate
 - long-leg notional  * long funding rate
```

This forecast should use each venue's actual funding schedule and should be frozen at entry. It should
not assume that an annualized historical rate is paid smoothly through time.

### Suggested report fields

```text
signal_run_id
pair_id
raw_spread_annual_pct_one_leg
adjusted_ranking_score_pct
equivalent_two_leg_annual_pct
current_pending_spread_pct
matched_leg_notional_usd
expected_funding_usd_for_hold
realized_funding_usd
entry_fees_usd
exit_fees_usd
basis_pnl_usd
net_pnl_usd
return_on_account_equity_pct
```

## 13. Recommended validation order

1. Correct the presentation layer conceptually: always show one-leg spread and two-leg/account return
   side by side.
2. Freeze and persist the exact signal and expected funding cash for every opened pair.
3. Rerun freshness calibration with non-overlapping signal and outcome windows.
4. Rerun the lookback comparison using the exact production pairwise algorithm.
5. Rerun the capital simulation with venue-specific fees, missing-data rejection, production
   completeness, and candidate backfill below rank 20.
6. Add a pre-entry pending-rate check using the observation pipeline.
7. Derive a cost-aware minimum spread separately for CEX-CEX and CEX-DEX holds.

## Final verdict

| Question | Conclusion |
|---|---|
| Are the venue funding payments unexpectedly small? | No. They are approximately consistent with a 16-17% one-leg annualized spread after about ten hours. |
| Does the live snapshot prove the signal decayed by half? | No. That conclusion came from dividing by both legs and comparing with a one-leg signal. |
| Is 20% equivalent to a 20% return on deployed or account capital? | No. It is 10% on equal two-leg gross exposure, before idle capital and costs. |
| Is the current 0.5 stale discount numerically validated? | Not by the existing analysis; the signal and outcome windows overlap. |
| Can a correctly forecast 20% trade still lose money? | Yes. A three-day Binance-Bybit trade can cost more in commissions than its threshold-level funding. |
| Should conclusions be drawn from the ten-hour book now? | Not about full-hold profitability. Complete-stamp, full-hold, cost-inclusive evidence is required. |

