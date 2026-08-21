# Gerchik V2 — strategy set, pre-registration (2026-08-11)

Written **before implementation and before any backtest**. Supersedes the `gerchik-level` v1
implementation, whose results are void: `GERCHIK_COURSE_REVIEW_2026-08-11.md` shows it substituted
an ATR-tolerance pivot cluster for the specified БСУ/БПУ model and so admitted 849-5,271 trades where
the specification permits a handful.

## The prediction this set exists to test

`GERCHIK_LABELLED_EXAMPLES.md` records nine outcomes. Sorted by model, the separation is total:

| Model | Examples | Result |
| --- | --- | --- |
| **ЛП (false breakout)** | 09.11 SBRF, 28.09 EU, 4.10 GAZR | **3 wins, 0 losses** |
| **Пробой + закрепление** | 13.10 SBRF, 5.10 RI, 16.10 RI | **0 wins, 3 losses** |
| СЛП / return | 17.10 SBRF | 1 breakeven |

n = 7, so this is anecdote, not evidence. But it is **source-derived** - it comes from the author's
own worked examples, not from our data, and our data has never seen it. That makes it a legitimate
pre-registered prediction rather than a fitted one.

**Registered prediction: G1 (ЛП) > G2 (отбой) > G3 (пробой) on per-trade edge.** If G3 beats G1, the
labelled-set ordering was noise and this framing is refuted regardless of whether any variant is
profitable.

This is deliberately different from the nine Apollo hypotheses, every one of which asked "does adding
X help". This one asks "does the source's own ranking of its models reproduce" - a question with an
informative answer either way.

## Constants: what transfers and what does not

Measured 15m ATR as a percentage of price, 2024, from `futures_kline`:

| Symbol | ATR % | 0.2% stop equals |
| --- | ---: | --- |
| BTCUSDT | 0.381% | 0.53 x ATR |
| ETHUSDT | 0.464% | 0.43 x ATR |
| XRPUSDT | 0.589% | 0.34 x ATR |
| ADAUSDT | 0.668% | 0.30 x ATR |
| SOLUSDT | 0.706% | 0.28 x ATR |

**The 0.2% calculated stop does not transfer.** On crypto 15m it sits inside a single bar's range and
would be noise-stopped constantly. It is dropped, and this is recorded as **our adaptation, not
Gerchik's rule**. Its removal is also supported by the source itself: the 4.10 GAZR example used
0.327%, exceeding both the 0.2% stop and the "ТС <= РС + 20%" bound, because the stop was anchored
*"за хвост ЛП"* - structure overrode the formula.

Everything else in the course is ATR-relative or price-relative and transfers unchanged:

| Constant | Value | Status |
| --- | --- | --- |
| Люфт | `price x 0.04%` | Gerchik's; confirmed exactly once (09.11: 21375 -> 21365) |
| False-breakout depth | `<= 1/3 ATR` | Gerchik's; already ATR-relative |
| Minimum reward:risk | 3:1 | Gerchik's; confirmed by 1/3 and 1/4 target labels |
| Channel tradeable | width >= 8 stops | Gerchik's |
| Stop anchor | structural: behind level, or behind the ЛП wick | Gerchik's |
| Stop buffer | `0.10 x ATR` | **ours** - reuses this repo's existing declared constant |
| БСУ/БПУ price tolerance | `0.05 x ATR` | **ours** - see below |

**"Копейка в копейку" needs a tolerance on crypto.** The rule presumes a coarse tick grid: on SBRF
futures one point on 20003 is 0.005% of price, roughly 0.05 x the instrument's short-timeframe ATR.
`0.05 x ATR` is that ratio carried across, not a number chosen to produce trades. It is declared here
and **will not be swept** - sweeping it would recreate exactly the v1 error.

## Shared component: the level map

Not the v1 pivot cluster. Levels are built as the course specifies:

- **Sources, in priority order**: зеркальный уровень (support becoming resistance or vice versa) from
  1H and Daily; yesterday's high/low; prior-period extremum. Mirror levels are 5 of 8 in the labelled
  set, so they rank first.
- **БСУ** — the bar forming the level. **БПУ-1** — a later bar touching it within `0.05 x ATR`. Any
  number of bars between; the level may be broken between them.
- **Persistence**: levels survive for **months**, not the 42 days Apollo used. The 29.10.2017 scenario
  trades a level drawn the previous January. A level is retired only when structurally invalidated,
  not on a timer.
- **Unresolved ambiguity, flagged**: the конспект says БПУ-1 and БПУ-2 *"должны идти друг за другом"*.
  Read strictly this means adjacent bars; the Block 4 practice video shows several bars between them.
  Adjacency changes setup frequency by an order of magnitude. **Both readings will be implemented
  behind a flag, the loose one used as primary, and the strict one reported alongside** - rather than
  picking whichever produces a better number.

## The three strategies

### G1 — `gerchik-false-breakout` (highest prior)

Level is broken, price fails to hold beyond it and returns.

- Breakout depth beyond the level **<= 1/3 ATR**.
- Entry: **stop order** on the opposite side, placed as the reclaiming bar completes.
- Stop: behind the false-breakout wick + `0.10 x ATR`.
- Target: **nearest opposing mapped level**; reject if that gives < 3R. Structural targets come from
  the PCG example - *"первый выход возле ближайшего уровня"*.

### G2 — `gerchik-bounce` (the course's core model)

БСУ/БПУ approach to an untested level.

- БСУ + БПУ-1 within tolerance; БПУ-2 per the adjacency flag; БПУ-1 and БПУ-2 on the same side.
- Entry: **limit** at `level ± люфт`. This is the mechanic the engine gained on 2026-08-11
  (`EnterAtLimit`) and could not previously express.
- Stop: behind the level + `0.10 x ATR`.
- Target: nearest opposing level, >= 3R.
- Cancel the resting order if price closes 2 stops away (Gerchik's rule).

### G3 — `gerchik-breakout` (control, lowest prior)

- Approach on compressed bars; break; acceptance by a 1H close beyond the level; entry on the retest.
- Entry: **stop-limit**, per the source's slippage warning.
- Stop: behind the level + `0.10 x ATR`. Target: nearest opposing level, >= 3R.

Included **because** it is predicted to lose. A set where every variant is expected to win cannot
falsify anything.

## Evidence standard

- 15-symbol unselected universe reported every time; top-5 may be shown beside it but never alone.
- Block bootstrap, not per-trade.
- Training window first, then **one** confirmatory run on 2022-01-01..2023-02-07.
- No sweeps. No threshold changes after seeing a result.
- Validation and final-test windows stay closed.

## What this set cannot do

`GERCHIK_LABELLED_EXAMPLES.md` documents discretionary exits worth 30-60% of nominal risk on two of
three losing examples, plus a winner closed early and exits keyed to "anomalous volume". A mechanical
implementation holds its stop and target by construction and therefore **forgoes a component that
measurably carries money in the source's own examples**.

So a flat or mildly negative result here does not falsify the method as practised. It would establish
the narrower claim: this mechanisation of it carries no edge on crypto. That distinction must survive
into however the results are reported.

## Stopping rule

If G1 does not beat G3 on per-trade edge in the training window, the labelled-set ordering is refuted
and the set closes without an out-of-sample run - there would be nothing left to confirm.

If the ordering holds but no variant reaches a positive out-of-sample per-trade edge, the finding is
recorded as: the source's model ranking reproduces, but not at a magnitude this data can establish.

---

# G1 RESULT (2026-08-11): implemented, run, and NOT evaluable at 15m

| Universe | Trades | Net | Per-trade | **Gross** | Costs | Win | P(profit) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 15 symbols unselected | 430 | -$134,300 | -$312.33 | **+$3,355** | $137,655 | 20.2% | 0.0% |
| top 5 | 142 | -$39,041 | -$274.93 | **+$5,898** | $44,938 | 19.7% | 0.0% |

**Costs are 41x gross profit.** The model is marginally gross-positive and catastrophically
net-negative. This is arithmetic, not variance.

## Why: the model's stop geometry is incompatible with crypto perp fees

The false-breakout entry sits at the level and its stop sits just past a wick that, by the source's
own rule, poked through by at most 1/3 ATR. Total stop is therefore ~0.43 x ATR by construction -
Gerchik's intended "короткие стопы". Measured consequences:

| Timeframe | ATR % of price | Stop at 0.43 x ATR | Round-trip cost as % of risk |
| --- | ---: | ---: | ---: |
| 15m | 0.517% | 0.222% | **58%** |
| 1h | 1.061% | 0.456% | **28%** |

Round trip is taker entry (4.5 bp) + taker stop exit (4.5 bp) + 2 x 2 bp slippage = ~13 bp of
notional. Because the stop is tight, a fixed 0.5% account risk pins position size at the 3x leverage
cap - median notional $292,260 against a $300,000 cap - so the cost is levied on a maximal position.

On equities and futures, where this method was developed, commissions are per-share or per-contract
and a 0.15% stop is unremarkable. On crypto perpetuals it is not. **This is an asset-class
incompatibility, not evidence about the model.**

## The pre-registered G1 > G3 comparison cannot be answered here

Costs run ~$320 per trade while any plausible difference between the models is worth tens of dollars
gross. The noise floor exceeds the signal. Running G3 at 15m would measure the same cost sink, so
the ordering prediction from `GERCHIK_LABELLED_EXAMPLES.md` remains **untested**, not refuted.

## Two further caveats on this run

- **Truncated sample.** Only 7 four-week blocks across 15 symbols over a nominal two years, because
  most symbols hit the 10% max-drawdown rule and terminated early. This is "until each symbol blew
  the drawdown limit", not two years of trading.
- **Win rate 20.2% against a 3R minimum** is close to the breakeven line even before costs, and the
  slight gross profit implies realised winners ran beyond 3R. With 430 trades over 7 blocks this is
  not a reliable estimate of either quantity.

## What follows

Testing at 1h is the first setting where costs (28% of risk) no longer swamp the comparison. That is
**a new pre-registration, not a re-run of this one** - changing the timeframe after seeing a negative
result is precisely the tuning move this project has been avoiding, and it only escapes that
objection because the reason is a measured cost identity fixed in advance of the new test, not a
search for a better number.

The course's own structure argues for it independently: Gerchik draws levels on the **daily** chart
and enters on **M5** - a far wider separation between level and entry timeframe than the 1h/15m pair
used here.

---

# SET RESULT (2026-08-11): ordering REFUTED, set closed

Full training window, 15 symbols, no prop terminations (`engine-research.properties`). Models are
compared on **gross** per-trade edge, where the cost identity that sank G1 largely cancels.

| Model | 15 symbols gross/trade | top 5 gross/trade | trades (15) | blocks | Predicted rank |
| --- | ---: | ---: | ---: | ---: | --- |
| **G3 breakout (control)** | **+$24.97** | **+$23.93** | 6,859 | 29 | worst |
| G1 false breakout (ЛП) | +$6.04 | -$0.13 | 6,114 | 29 | **best** |
| G2 bounce loose | -$45.83 | -$64.87 | 1,766 | 29 | middle |
| G2 bounce strict | -$45.71 | -$66.27 | 1,165 | 28 | middle |

**Predicted G1 > G2 > G3. Observed G3 > G1 > G2.** The control included because it was expected to
lose is the best model in both universes; the course's core model is the worst.

The stopping rule declared in advance applies: G1 did not beat G3, so the labelled-set ordering is
refuted and the set closes without an out-of-sample run.

## What that says about the labelled examples

`GERCHIK_LABELLED_EXAMPLES.md` showed a perfect split - ЛП 3 wins 0 losses, пробой 0 wins 3 losses.
On roughly 15,000 mechanical trades the ordering inverts. Nine hand-picked teaching examples carried
no predictive information about relative model performance, which is what n=7 is worth and why the
prediction was registered rather than assumed.

## Secondary findings

**The БПУ adjacency ambiguity is immaterial.** It was flagged as possibly changing setup frequency by
an order of magnitude, and both readings were implemented rather than one being chosen. Trade count
does move - 1,766 loose against 1,165 strict - but gross edge does not: -$45.83 against -$45.71. The
question that could not be settled from the text or the practice video turns out not to matter.

**No model clears crypto transaction costs.** The best gross figure, G3 at +$24.97 per trade, sits
against roughly $187 per trade in fees and slippage. The cost identity established for G1 - a stop of
~0.43 x ATR against a ~13 bp round trip - applies to all three, because all three take tight
structural stops by design. Net P(profit) is 0.0% for every model in every universe.

## Status

Gerchik V2 closes. Three models implemented from the source documents alone, compared on a
pre-registered prediction, refuted on a 15,000-trade sample. Validation and final-test windows were
never opened.

The narrower claim this establishes: **these three mechanisations carry no edge on crypto perpetuals
at 15m, and their relative ordering does not follow the source's own worked examples.** It does not
establish anything about the method as practised discretionarily on equities, where the
`GERCHIK_LABELLED_EXAMPLES.md` exits worth 30-60% of nominal risk are available and the fee structure
is entirely different.
