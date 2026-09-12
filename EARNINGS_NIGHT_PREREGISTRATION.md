# Earnings-night continuation: frozen retrospective screen

Declared 2026-09-12 09:43 UTC, before the expanded event ledger was joined to any post-entry price outcome.

## Status and contamination

This is a **retrospective screen**, not a clean efficacy test. The pilot already reported in `IDEA_BOARD.md`
influenced the 3% trigger, continuation direction and approximate timing: eight observations, seven positive,
median about +180 bp, with one roughly -899 bp non-earnings outlier. The 25 names available to that work are
therefore quarantined as cohort A and can only be reported descriptively. The primary cohort B is every other
eligible name in the frozen inventory.

No outcome from the expanded population has been inspected before this declaration. Before freezing it, one
APP SEC submission and one Nasdaq calendar response were inspected only to validate source fields and timezone
semantics; neither was joined to Binance prices. Current Binance `exchangeInfo` and SEC submission records are
used to construct the inventory, so current-listing survivorship and the event-source limitations below remain.

This screen does not spend a new sequential-test budget and cannot authorise a trade. Newcomer cohort N1 remains
the only active full-budget test under `IDEA_TESTING_PROTOCOL.md`. A pass here only justifies a separately frozen,
forward earnings cohort.

## Frozen population and window

- Event dates: 2025-12-01 through 2026-09-10, inclusive, in `America/New_York`.
- Starting universe: instruments returned at inventory time by Binance USD(S)-M Futures `exchangeInfo` with
  `status=TRADING`, `contractType=TRADIFI_PERPETUAL`, `underlyingType=EQUITY`, quote asset USDT and symbol ending
  USDT.
- A Binance base must map exactly to a ticker in the SEC company-ticker file. The sole predeclared alias is
  `BRKB -> BRK-B`. Ambiguous and unmatched names are excluded. The SEC submissions record must classify the
  issuer as `entityType=operating`; this excludes ETFs, trusts and foreign private issuers from both the event
  and control populations. The ticker must have a non-empty `Nasdaq`, `NYSE`, `NYSE American`, or `Cboe BZX`
  entry in the SEC submissions ticker/exchange arrays. If more than one current Binance symbol maps to one CIK,
  keep only the earliest of those tickers in the SEC ticker array; this prevents one issuer filing from being
  counted once through its common stock and again through a preferred security. An earlier SEC ticker that is
  not available on Binance does not exclude a later share class (for example `BRK-B`).
- The instrument's Binance `onboardDate` must be no later than 09:30 New York on the preceding full US session,
  so a newly listed contract contributes at least one complete cash session before the event.
- Eligible disclosures are SEC form `8-K` (not `8-K/A`) containing exact Item `2.02`. For one issuer and local
  date, keep the earliest eligible accession.
- The EDGAR `acceptanceDateTime`, parsed as the UTC timestamp supplied by the SEC submissions JSON and converted
  to New York time, must fall from 16:00:00 through 19:30:00 inclusive on a Monday-through-Thursday regular US
  session. Exclude holidays, early closes, Fridays and dates for which the next US session is not the next
  calendar day.
- The primary 8-K document must contain `press release` and a deterministic earnings-results phrase: one of
  `financial results`, `quarterly results`, `results for the quarter`, `results for its quarter`,
  `results for the fiscal year`, `full year results`, or `earnings results`. Documents containing
  `preliminary results` are excluded. This outcome-blind text rule removes generic Item 2.02 updates; it is not
  changed after the documents are downloaded.
- This strict source deliberately omits foreign private issuers reporting on 6-K and US issuers whose earnings
  release is not represented by a timely Item 2.02 filing. EDGAR acceptance proves the document was public by
  that timestamp; it is not guaranteed to equal the press-release wire time. These omissions trade recall for
  a reproducible, timestamped event definition.

The pilot-contaminated cohort A is frozen as:

`SPY, QQQ, EWJ, EWY, COIN, TSLA, MSTR, PLTR, HOOD, AAPL, AMZN, META, INTC, MU, CRCL, NVDA, LLY, JPM, QCOM, TSM, PAYP, SNDK, AAOI, AXTI, NOK`.

All eligible mapped symbols outside that set form primary cohort B. The inventory JSON, including eligible
events, unmatched instruments, onboard timestamps and source accessions, is frozen by a SHA-256 digest before
the measurement mode is allowed to read outcome prices.

## One frozen rule

All clocks below use `America/New_York`; the analysis converts each timestamp to UTC with an IANA timezone so
daylight-saving changes cannot move a bar.

1. Anchor price: close of the Binance 1-hour bar opening at 15:00 on the disclosure date (the bar ending at the
   16:00 regular close).
2. Decision price: close of the completed 1-hour bar opening at 19:00. The filing must have been accepted no
   later than 19:30, leaving at least 30 minutes before that bar closes.
3. Signal: `decision / anchor - 1`. Trigger if its absolute value is at least 3.00%; direction is the signal's
   sign. Entry is the open of the 1-hour bar opening at 20:00, strictly after the signal exists. The difference
   between decision close and entry open remains in the measured execution rather than being silently erased.
4. Exit price: open of the 1-hour bar opening at 10:00 on the next US session, the first whole-hour boundary
   after the 09:30 cash-market open.
5. A row is unusable if a required bar or positive price is absent, if any required bar reports no trades, or
   if no funding settlement is present in `(entry, exit]`. No missing funding value is replaced by zero.

Price P&L in basis points is `direction * (exit / entry - 1) * 10,000`. Funding P&L is
`-direction * sum(funding_rate) * 10,000`, using settlements strictly after entry and at or before exit.

The primary net subtracts **13 bp round-trip**: 4.5 bp taker fee per side (BNB-discount assumption) plus 2 bp
adverse slippage per side. Frozen sensitivity rows subtract 9 bp and 25 bp. No threshold is selected from the
sensitivity results.

For executability, report quote volume from the completed 19:00-20:00 decision bar and a descriptive capacity
proxy of 10% of that volume. Capacity is not an efficacy gate and no order size is authorised.

## Controls and information unit

The independent observation is the New York disclosure date: average all triggered names on a date before
computing mean, standard deviation or t-statistic. Event-weighted results are reported separately as the
return-on-deployed-names view.

The frozen generic control is every otherwise eligible cohort-B symbol/date in the same window with the same
anchor, decision, entry and exit clocks and an absolute anchor-to-decision shock of at least 3%, but no exact
Item 2.02 filing for that issuer on the local date, regardless of its acceptance time or document-text result.
Control rows use identical funding and costs. This is a pooled generic company-news shock control, not a claim
that the control dates contain no company news. All rows are used once, without replacement. To avoid comparing
different shock and calendar mixes, compute a residual for every earnings row against the arithmetic control
mean in its predeclared cell:

- direction: up or down;
- absolute shock: `[3%, 5%)`, `[5%, 10%)`, or `>=10%`.
- New York weekday and calendar quarter.

The adjusted event value is `earnings net - control-cell mean`; the headline adjusted result first averages
these residuals within New York date and then computes the mean across dates. Earnings events are therefore the
weights, not control-cell counts. If a cell has no control rows, its earnings rows and the aggregate adjusted
comparison are unavailable rather than merged or re-bucketed. BTC's same-clock return is reported only as a
market-regime diagnostic.

## Frozen outputs

Report the complete event ledger, including non-triggers and unusable rows, plus:

- eligible, triggered and usable counts for cohorts A and B;
- event-weighted and date-declustered mean, median, t-statistic, positive fraction and worst return;
- results by direction and calendar quarter;
- primary 13 bp results and the frozen 9/25 bp sensitivities;
- mean after removing the date with the highest nightly return and after removing whichever symbol causes the
  largest fall in the recomputed nightly mean; a date left with no symbols after removal is omitted;
- generic-control statistics and the date-declustered control-adjusted residual;
- funding contribution, BTC diagnostic, decision-bar quote volume and 10%-participation capacity distribution.

## Decision rule

The primary cohort-B screen **passes** only if all of the following hold at 13 bp:

1. at least 30 usable triggered events and at least 20 independent disclosure dates;
2. date-declustered mean net at least +50 bp, median net above zero and t-statistic at least 2.00;
3. at least two calendar quarters with at least three independent dates each have positive date-declustered
   mean net;
4. mean net remains above zero after removing the best date and after removing the best-contributing symbol;
5. the date-declustered control-adjusted residual is above zero.

Fewer than 30 events or 20 dates is **insufficient**, irrespective of point estimate. Any other failed condition
is **failed**. No parameter, subset, window, timing or threshold will be changed after measurement to rescue the
result. Passing means only “open a clean forward cohort”; insufficient or failed means park the idea without a
post-hoc variant.

## A1 — metadata correction before outcomes (2026-09-12 09:56 UTC)

The first inventory dry run returned zero instruments because Binance labels these contracts
`TRADIFI_PERPETUAL`, not `PERPETUAL`. No event manifest or price outcome existed. The exact contract-type value
above was corrected and the empty dry-run hash discarded before the inventory was frozen.

## A2 — listed-security and issuer deduplication before outcomes (2026-09-12 09:59 UTC)

The first non-empty source manifest revealed that SEC `entityType=operating` also admits the private-company
SPCX token and that MSTR/STRC map to the same Strategy CIK and filings. No manifest hash had been frozen and no
price outcome had been read. The listed-exchange and one-current-ticker-per-CIK rules above were added, and that
intermediate manifest was discarded.

## Frozen inventory (2026-09-12 10:04 UTC)

`research/earnings-night-events-2026-09-12.json` is frozen at SHA-256
`f318b8cf40fe06e78d4a5d2d88be8b98a3d0060ffecedd49e86e0a8727ac0d00`. It contains 155 current Binance
EQUITY/USDT contracts, 104 eligible listed operating issuers and 71 source-qualified events on 37 dates:
45 events in primary cohort B and 26 in pilot-contaminated cohort A. The measurement program refuses any other
manifest digest.

## Measurement implementation correction (2026-09-12 10:06 UTC)

The first frozen database query stopped before decoding a market row because `psql` emitted the status line
`SET`, which the PSV reader treated as data. No return or statistic was produced. The reader was changed to
ignore non-data status lines and `psql` command-status output was silenced; the manifest, rule and gate are
unchanged.
