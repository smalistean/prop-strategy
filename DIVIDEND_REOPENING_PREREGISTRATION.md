# Equity-perp dividend reopening — retrospective discovery preregistration

**Frozen:** 2026-09-13 10:53 UTC, after adversarial static review and after freezing the
official checksum values, but before any event-window trade, index-price or mark-price archive
was downloaded or opened.

**Board item:** #17 in `IDEA_BOARD.md` and `STRUCTURAL_EVENT_RESEARCH_QUEUE.md`.

**Status:** one-look retrospective discovery screen. Historical outcomes already exist, so
even a pass cannot authorize a trade. It can authorize only a separately frozen prospective
L1 shadow cohort after the active newcomer weekend-fade N1 test no longer occupies the
repository's sole full error budget.

## 1. Hypothesis and what is not being tested

Binance makes US equity perpetuals reduce-only from 19:30 to 20:01 New York time around a
dividend adjustment. A separate negative funding settlement transfers the dividend from
shorts to longs at 20:00. The hypothesis is that a large executed-price displacement across
that constrained boundary, after adding back the dividend cash adjustment, partially reverses
after unrestricted trading resumes.

This is not:

- dividend or funding capture — entry is after both settlements;
- a claim that price must move by exactly the dividend;
- a perp/index arbitrage — Binance can construct the index from its own order book during
  some non-trading modes;
- permission to tune the threshold or horizon after seeing results.

Official sources frozen with this declaration:

- dividend workflow and `special rate = -D/M`:
  <https://www.binance.com/en/support/faq/detail/7ced719b5e9a4859a1864c2fe657309f>;
- equity Orderbook-EWMA index mode effective 2026-05-16:
  <https://www.binance.com/en/support/announcement/detail/53bfc17634f54f2f90666dbc396f5cee>;
- up to 15 seconds of deviation in actual funding transaction time:
  <https://www.binance.com/en/support/faq/detail/360033525031>;
- USDT/USD conversion effective 2026-07-16 and current TradFi price modes:
  <https://www.binance.com/es/support/faq/detail/fe7dcdf24f1943d98b368f5f9f744398>;
- TradFi mark-price basis change effective 2026-08-31:
  <https://www.binance.com/en/support/announcement/detail/1e9c1d2dff0b4d48a04c09f84a39fcb8>.

## 2. Frozen population and observation window

Start from the outcome-blind inventory
`research/dividend-reopening-feasibility-2026-09-12.json`, SHA-256
`7294a753d8a3b1300474cb876bdd07e5ccff403cf0cbbb165adaf8371ec20786`.
Its source universe is the 155 Binance USDT instruments frozen on 2026-09-12 with
`contractType=TRADIFI_PERPETUAL` and `underlyingType=EQUITY`, including operating companies,
funds, ADRs and other instruments rather than selecting types after outcomes.

The event fingerprint is fixed before price data:

1. five hourly funding buckets from 16:00 through 20:00 New York;
2. a separate negative row more than zero and no more than five seconds after the ordinary
   20:00 settlement;
3. event earlier than `2026-09-12T00:00:00Z`.

The inventory contains 51 events / 41 symbols / 32 New York process dates. Exclude only the
single AAPL event processed 2026-05-10, because it predates the current equity Orderbook-EWMA
regime effective 2026-05-16. The frozen primary population is therefore **50 events, 41
symbols and 31 dates**, processed 2026-05-20 through 2026-09-09. No inclusion decision depends
on an event's dividend size or future return.

The 2026-07-16 USDT/USD conversion creates two predeclared reporting strata: 23 events / 14
dates before it and 27 events / 17 dates after it. The 2026-08-31 mark-price change leaves
only 9 events / 5 dates afterward and is diagnostic, not a separately powered gate.

## 3. Frozen clocks and signal

All clock construction uses `America/New_York`, including daylight saving time. Let the
event's documented nominal settlement boundary be `T = 20:00` on its process date.

- `P_minus`: close of the executed-trade 1-minute bar opened at 19:58. This bar ends more
  than one minute before nominal settlement and cannot straddle the documented 15-second
  settlement-time uncertainty.
- `M_proxy`: open of the mark-price 1-minute bar opened at 20:00. The funding-history rows
  contain no mark, so this is the closest archived proxy for the formula's execution mark.
- `f_div`: the isolated negative second funding row in the event fingerprint. Do not add the
  ordinary 20:00 rate.
- `D_hat = -f_div * M_proxy`: estimated dividend cash paid per one long contract.
- `P_plus`: close of the executed-trade 1-minute bar opened at 20:01, after reduce-only is
  scheduled to end. It is knowable only when that bar closes at 20:02.

The decision statistic is the dividend-adjusted boundary displacement:

```text
gap_bp = 10,000 * (P_plus - P_minus + D_hat) / P_minus
```

This is deliberately called a displacement, not fair-value error. News and legitimate price
discovery can make it nonzero.

The frozen trigger and direction are:

- `gap_bp >= +50`: short;
- `gap_bp <= -50`: long;
- otherwise: no trade.

Fifty basis points is twice the primary round-trip cost and was chosen before seeing any
price displacement. Special-funding magnitude is not a trigger.

## 4. Entry, exit, validity and P&L

- Decision becomes available after 20:02.
- Historical entry proxy: open of the 20:03 executed-trade bar. The unused minute is an
  explicit latency allowance; do not substitute the 20:02 open.
- Nominal primary exit: open of the 20:18 executed-trade bar, 15 minutes after entry. If that
  minute has no executed trade, use the open of the first positive-volume bar from 20:19 through
  20:23 inclusive. Record the delay. This deterministic fallback prevents future exit liquidity
  from making an entered trade disappear.
- Direction `q` is `-1` for the positive-gap short and `+1` for the negative-gap long.

```text
gross_bp = q * (exit_price / entry_price - 1) * 10,000
net_25_bp = gross_bp - 25
```

The primary complete round-trip cost is **25 bp**, deliberately above the project's observed
ordinary futures fee because 2026 event-window bid/ask history is unavailable. Report 13 bp
and 40 bp cost sensitivities; neither may rescue a failed primary result.

Signal validity is determined before entry: the selected `P_minus` and `P_plus` close must be
positive and each source bar must have positive volume and at least one trade; the selected
`M_proxy` open must be positive. Missing or inactive signal inputs stay in the 50-event signal-
attrition denominator. They are never silently removed.

For a triggered signal, the selected 20:03 entry open must be positive and its bar must have
positive volume and at least one trade. The exit uses the rule above and the same requirements.
A triggered signal lacking an entry or any allowed exit is retained as an **execution failure**;
it is not dropped from the trigger count and causes the screen to fail. Non-triggered events do
not need future entry or exit bars to establish their signal validity.

Entry-minute quote volume and trade count are reported as capacity diagnostics only. They
cannot filter this historical sample because the intended order size and executable book were
not observed.

## 5. Controls

For each triggered event, form candidate non-event controls from the same symbol and weekday
in the preceding 56 calendar days, no earlier than the 2026-05-16 regime start. Exclude dates
that are dividend-process dates for that symbol. A control can match only inside both of the
event's structural strata: the same side of the 2026-07-16 USDT-conversion boundary and the
same side of the 2026-08-31 mark-method boundary.

For each control use the identical clocks and set `D_hat = 0`. Its boundary displacement is
therefore `10,000 * (P_plus/P_minus - 1)`. Choose the same-sign control whose absolute gap is
closest to the event's absolute gap; break a tie by choosing the more recent date. A proposed
match is eligible only when `0.8 <= abs(control_gap) / abs(event_gap) <= 1.25`; otherwise the
event has no matched control. A control does not have to cross 50 bp because matching, rather
than strategy frequency, is the purpose. The control trade uses its own gap's fade direction
and the identical entry, delayed-exit and cost rules.

Matching is without replacement. Process triggered events by `(process_date, symbol)` ascending
and never assign a control symbol-date twice. Besides the pairwise caliper, the mean matched
absolute-gap ratio, computed as the pair-weighted arithmetic mean across valid matched pairs,
must lie from 0.9 through 1.1. This prevents a systematically smaller control move from making
event-minus-control P&L appear positive merely through scale imbalance.

Control selection uses only information through its completed `P_plus` signal. For every
triggered signal, including an event later found to lack an executable entry or exit, lock and
reserve the closest signal/regime/caliper match before checking either side's entry or exit. A
selected pair is a valid match only when both the event and control execute under the frozen
rules. If either side fails execution, retain the selection as an unavailable pair, keep the
control reserved under the without-replacement rule, and do not rematch using future liquidity.
Report those execution failures and the standalone-control trigger denominator explicitly.

Report:

- number of triggered events and distinct event dates/control dates with an eligible match;
- absolute-gap matching distance;
- matched absolute-gap ratios and their mean;
- event-minus-control net P&L, first per pair and then averaged by event date.

At least eight distinct event dates and eight distinct control dates must have a valid match
for the control gate to be evaluable. Also report the identical standalone 50 bp fade rule over
every unique candidate control date, but that secondary control ledger cannot rescue the primary
result.

## 6. Information unit and reports

The primary information unit is one New York process date: average all triggered event P&Ls
on that date, then compute the mean, median, standard deviation, t-statistic and worst date.
The date-declustered t-statistic is `mean / (sample_sd / sqrt(n_dates))`.

Also report:

- event-weighted mean/median and long/short split;
- each symbol and date contribution;
- trigger rate and data attrition;
- pre/post 2026-07-16 USDT-conversion strata;
- the five-date post-2026-08-31 mark-method stratum, labelled underpowered;
- leave-best-date and leave-best-symbol means. The best date is the one with the largest
  date-level mean `net_25_bp`, with the earliest date removed on a tie. The best symbol is the one
  with the largest total `net_25_bp`, with the lexicographically first symbol removed on a tie;
- share of total positive event P&L supplied by the best symbol, calculated by summing
  `max(net_25_bp, 0)` within symbol and dividing by that quantity across all events;
- start and exit dividend-adjusted displacement, with no filtering on the exit value;
- perp/index and `M_proxy` diagnostics, explicitly not independent fair value;
- a complete `D_hat = -f_div * P_minus` proxy sensitivity: trigger events/dates, direction
  disagreements, and date/event-weighted 25 bp-cost P&L. It diagnoses mark-proxy dependence,
  is never a decision gate and cannot replace the primary signal.

Five- and 30-minute exits may be printed only in a clearly labelled appendix after the primary
verdict. They cannot change the verdict or become a new rule without a new dataset and embargo.

## 7. One-look discovery gate

There is one reveal and no sequential look. The historical discovery screen advances only if
**every** condition holds:

1. pre-entry signal-bar attrition is no more than 20%;
2. at least 12 independent dates trigger;
3. there are zero triggered entry/exit execution failures;
4. date-weighted mean `net_25_bp` is at least **+25 bp**;
5. event-weighted mean `net_25_bp` is at least **+25 bp**;
6. date-declustered t-statistic is at least **2.0**;
7. at least eight distinct event dates and control dates have matched controls, the mean
   matched absolute-gap ratio is 0.9-1.1, and date-weighted event-minus-control mean is positive;
8. date-weighted mean remains positive after removing the best date;
9. event-weighted mean remains positive after removing the best-contributing symbol;
10. the 40 bp-cost date-weighted mean is non-negative;
11. both pre- and post-2026-07-16 date-weighted means are non-negative; and
12. no symbol supplies more than 25% of total positive event P&L.

Failure of any economic, control, stability or concentration condition closes #17. Failure
only because there are fewer than 12 triggered dates or fewer than eight matched-control dates
is recorded as **insufficient historical information**, which still does not authorize a
prospective test or capital automatically.

Any other unevaluable gate is a failure. In particular, an undefined t-statistic caused by
zero date-level variance fails rather than being relabelled insufficient. The matched economic
and balance conditions are unevaluable, rather than failed, only when the declared eight-event-
date/eight-control-date minimum itself is not met.

A full pass authorizes one thing: design and review of a future prospective L1 shadow cohort.
It does not authorize live or tiny-live trading.

## 8. Data acquisition and integrity

The outcome-blind availability file
`research/dividend-reopening-archive-availability-2026-09-12.json`, SHA-256
`5549244bf26ebee72962f88c3a150ed58b1e4ae0aabb7f166d76458b6a29e8a6`, checked headers for the
complete 245-object plan. All **171 primary-scoped** trade/index/mark archives and checksums
exist (116,634,373 compressed bytes, about 111 MiB). Of 106 control-scoped objects, 96 exist;
the ten absent objects are older trade months for contracts that had not yet produced an
archive. Because 32 objects serve both scopes, the 235 unique available archives total
172,591,677 bytes (about 165 MiB). The exact official SHA-256 value of every one of those 235
archives is frozen in the same file. Only the small checksum text bodies were read; no archive
content was read.

All 310 frozen symbol-date control candidates remain in the ledger. A candidate whose source
object is absent is marked unavailable and cannot match; this never removes its associated
primary event or changes the primary attrition denominator.

After this declaration:

1. download only the available Binance official 1-minute `klines`, `markPriceKlines` and
   `indexPriceKlines` files needed for primary events and the frozen 56-day controls;
2. verify every available official checksum before parsing;
3. retain a machine-readable source manifest with URL, period, byte count and SHA-256;
4. check every parsed archive row for duplicate timestamps, reject conflicts, and count
   identical duplicates before filtering to event minutes;
5. do not fill a missing trade minute from mark or index data;
6. distinguish absent source archives, absent minute rows, inactive bars and triggered execution
   failures in the ledger;
7. freeze and verify the outcome-script SHA-256, make every constant match this document, and add
   boundary/timing self-checks;
8. write the complete event and control ledger once before summarizing it, refuse to overwrite a
   prior ledger/result, and reconstruct the ledger from checksummed sources before the verdict.

## 9. Leakage and stopping rules

The following void the screen: changing the 50 bp trigger, direction, clocks, primary horizon,
cost, population, matching rule or gate after archive content is opened; excluding a losing
instrument type; inspecting a result before the complete ledger exists; or choosing between
mark/index/trade definitions by performance.

A parser or timestamp bug is corrected and disclosed under `IDEA_TESTING_PROTOCOL.md`; it is
not permission to change an economic definition. If official files needed for a primary event
are absent despite the completed HEAD audit, record the absence and apply the declared attrition
rule. Leave this declaration byte-stable after the freeze. Once the primary outcome is revealed,
record the verdict in `DIVIDEND_REOPENING_RESULT.md`, `IDEA_BOARD.md`, `PROJECT_STATUS.md` and
`ROADMAP.md` before starting board #19.
