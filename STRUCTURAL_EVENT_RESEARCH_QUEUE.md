# Structural-event research queue

Opened 2026-09-12 15:43 UTC after the funding, liquidation, earnings-continuation,
price-pattern and indicator families had already been measured or closed.

This is the ordered source of truth for the five new leads. They are hypotheses, not
strategies. Work proceeds one item at a time; a later item is not implemented before the
current item's result is reviewed and recorded.

Historical observations already exist for all five mechanisms. Any first measurement is
therefore a **retrospective discovery screen** and cannot authorize capital. The newcomer
weekend-fade cohort N1 remains the repository's sole active full-error-budget prospective
test under `IDEA_TESTING_PROTOCOL.md`. A discovery-screen pass can authorize only a newly
frozen prospective shadow cohort.

| Order | Board | Candidate | Status |
|---:|---:|---|---|
| 1 | 17 | Equity-perp dividend-adjustment reopening residual | **CLOSED — frozen one-look FAIL; net25 -29.7 bp across five executed dates** |
| 2 | 19 | Delisting deadline / compulsory-settlement basis | **CLOSED — outcome-blind hedge-data FAIL; 9 contracts / 6 current-regime clocks versus 12 required** |
| 3 | 18 | Scheduled transfer-rail suspension basis | **NEXT FEASIBILITY GATE, DATA-BLOCKED — needs maintenance ledger plus cross-venue spot history** |
| 4 | 20 | Unlock-to-exchange realized flow | **BLOCKED — needs reliable vesting and exchange-wallet labels** |
| 5 | 21 | Pre-IPO external-reference / contract-size transition | **WATCH — exact event is measurable, sample is tiny** |

## 1. Board #17 — equity-perp dividend reopening

### Mechanism

For US equity perpetuals, Binance changes the funding interval to one hour at 16:00
New York time, makes the contract reduce-only at 19:30, applies a separate negative
dividend settlement immediately after ordinary funding at 20:00, and restores normal
trading at 20:01. The documented cash-dividend adjustment is `special rate = -D/M`.

Source: <https://www.binance.com/en/support/faq/detail/7ced719b5e9a4859a1864c2fe657309f>

### Causal claim

After normal trading reopens, a price move across the reduce-only boundary that is materially
larger or smaller than the isolated dividend adjustment may reverse over the next several
minutes. The direction must be chosen from completed post-reopening data and entry must
occur after that data exists.

This is **not funding harvesting**: the proposed trade opens after the special settlement,
earns no dividend payment and risks only the post-reopening residual. Funding supplies the
event clock and the size of the mechanical adjustment, not the P&L.

### Cheapest falsifier

Use every clean historical US-equity special settlement, Binance 1-minute trade/index/mark
archives, one frozen post-reopening decision minute, one entry, one exit, conservative
round-trip cost and date-level de-clustering. Require both profitable trade returns and
reversal of the dividend-adjusted displacement. Same-symbol, same-weekday non-event
observations are the control. Perp/index convergence is diagnostic only: during some
non-trading modes Binance constructs that index from the same order book, so it is not an
independent fair-value anchor.

### Data and current status

`DIVIDEND_REOPENING_FEASIBILITY.md` and the outcome-blind frozen inventory identified 51
events across 41 symbols and 32 independent New York dates. The current-regime one-look
population was 50 events / 31 dates. The checksum-bound reveal is recorded in
`DIVIDEND_REOPENING_RESULT.md`: only 34 signals were usable, six crossed 50 bp on six dates,
one lacked an executable exit, and the five executed trades averaged -4.7 bp gross / -29.7 bp
after 25 bp cost (t=-0.81). No eligible caliper-matched controls existed, the non-event fade was
also negative, and the sole winner supplied all positive P&L. Board #17 is closed; do not mine a
new threshold or horizon from this sample.

## 2. Board #19 — delisting deadline / compulsory settlement

### Mechanism

Binance announces a fixed close and auto-settlement deadline, can stop new orders shortly
before it, and can change liquidation, ADL, leverage, margin, index and price-protection
rules near the deadline. Holders who cannot or do not close voluntarily become forced flow.

Example source: <https://www.binance.com/en/support/announcement/detail/ba5d61807b474b0ca9f40250e7fa782c>

### Causal claim

Before the no-new-orders cutoff, a Binance-perp deviation from the same asset's spot or
another venue may converge into the announced settlement. Only paired/basis exposure is in
scope; an unhedged short has unacceptable squeeze and rule-change tails.

### Overlap and cheapest falsifier

This is a new forced-deadline event, but it reuses the project's existing basis and delisted-
instrument archive infrastructure. First build a complete announcement ledger without
prices. Kill it if exact cutoff/settlement terms cannot be reconstructed or if fewer than a
useful number of events have simultaneous hedge data.

### Data-gate result

`DELISTING_DEADLINE_FEASIBILITY.md` records the outcome-blind rejection. After incorporating two
OMGUSDT postponements that the initial title filter missed, 59 contracts / 26 independent clocks
remained in the current 30-minute settlement regime. Monthly and daily official archive checks
found only nine contracts / six clocks with the required same-asset Binance spot hedge, below the
frozen 12-clock minimum. Official futures bookTicker archives covered zero current events, so even
those six clocks lack quote-level execution evidence. No price or return was opened. Board #19 is
closed unless a separately frozen independent hedge source supplies the missing denominator before
any outcome join.

## 3. Board #18 — scheduled transfer-rail suspension basis

### Mechanism

During wallet or network maintenance, deposits and withdrawals can be disabled while
trading continues. Physical cross-venue arbitrage is then inventory-constrained even though
both order books remain open.

Example source: <https://www.binance.com/en/support/announcement/detail/d90fb725497b4ebda0ac897f3983acba>

### Causal claim

With capital pre-funded on both venues, pair unusually large spot-price deviations after a
public suspension begins and close both legs locally if the basis converges. The trade must
not require a transfer to enter or exit.

### Overlap and cheapest falsifier

This is a new scheduled isolation trigger inside the already familiar cross-venue basis
family, not a wholly new family. Build the announcement ledger first. Then compare basis
width and convergence with matched non-maintenance windows. It is blocked until a second-
venue spot history and defensible maintenance start times exist; reopening times are often
not announced.

## 4. Board #20 — unlock-to-exchange realized flow

### Mechanism and causal claim

Published unlock calendars are widely known. The narrower hypothesis begins only when
previously locked tokens actually leave a vesting contract and move toward a labelled
exchange wallet. Test beta-neutral underperformance after the observable transfer rather
than before the scheduled unlock.

### Cheapest falsifier and blocker

Start with a small set of contracts whose vesting code and recipient path are unambiguous.
Require the transfer to precede the trade decision. Compare against scheduled unlocks that
do not reach exchanges. This remains blocked because vesting-to-holder, market-maker, OTC
and exchange-deposit transfers are economically different and public labels can be late or
wrong.

## 5. Board #21 — pre-IPO external-reference transition

### Mechanism

Before the underlying IPO, Binance's pre-market perp can be driven by Binance order flow
without an external equity index. It later transitions to ordinary TradFi pricing and may
also receive a contract-size correction when the actual share count becomes known.

Sources:
<https://www.binance.com/en/support/faq/detail/87bf29a506b647159fc6ace70d38f252>
and <https://www.binance.com/en-AU/support/faq/detail/789d3d23b87a45f2971ae046d73f5f88>

### Causal claim and status

An exact reference or contract-size transition can create a one-off re-anchoring error.
Record each transition and compare executable perp price with the newly valid reference
only after Binance publishes the conversion. The mechanism is precise, but the event count
is too small for a general strategy; keep it as a watchlist until the ledger becomes large
enough.
