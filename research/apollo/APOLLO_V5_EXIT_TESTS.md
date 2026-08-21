# Apollo V5 exit/target structure tests — predeclared design (2026-08-10)

Roadmap step 2 (`APOLLO_V5_ROADMAP.md`). **Written before any result was inspected**, in response to
the multiple-testing problem documented in roadmap §3: with 56 training runs already spent, a test
design chosen after seeing results carries no evidential weight.

## Motivating observation

Across every profitable Family B run, roughly half the profit comes from the 24-hour
maximum-holding-period timeout rather than from reaching the mapped target:

| Symbol | Stop losses | Holding-period exits | Take profits |
| --- | --- | --- | --- |
| ETHUSDT | 62 trades, -$34,699 | 21 trades, **100% win**, +$18,444 | 7 trades, 100% win, +$19,383 |
| XRPUSDT | 32 trades, -$18,010 | 22 trades, 86.4% win, +$11,238 | 5 trades, 100% win, +$16,370 |

The strategy is nominally a "hold to the next mapped liquidity zone" design. It is not behaving
like one.

## Test symbols

**XRPUSDT** (60 trades) and **BTCUSDT** (34 trades), Family B — the two largest samples with
complete, stable volume-profile bin data. ETHUSDT (92 trades) is excluded until its `$1` bin
re-import completes, because querying partially-imported bins would produce meaningless results;
it will be added as a third symbol afterwards.

Both symbols are run for every variant. A result that moves one symbol and not the other is
treated as noise, not a finding.

## Predeclared hypotheses and interpretation

### Test B — holding-period sensitivity

`maximumHoldingBars` at 48 (12h), 96 (24h, baseline), 144 (36h), 192 (48h). Nothing else changes.

- **If the timeout is capturing real structure**: results vary systematically and monotonically-ish
  with holding period, with a defensible interior optimum, consistently across both symbols.
- **If the timeout is arbitrary**: results are flat, or move erratically/inconsistently between the
  two symbols. In that case the profit currently attributed to timeout exits is noise, and the
  apparent "edge" in that exit bucket is an artifact of when the clock happened to run out.

This is the decisive test. It is listed first because it can invalidate the motivating observation
outright.

### Test C — partial exit at 1R

`exit.partialEnabled=true`, `exit.partialTriggerR=1.0`, `exit.partialFraction=0.5` (engine already
supports this; defaults preserve the frozen baseline).

- **If the edge is in a long tail** (few large winners), taking half off at 1R should *reduce* net
  profit.
- **If the edge is in capturing quick moves before reversal**, it should *increase* net profit and
  reduce drawdown.

Note: partial exits are reported as separate exit legs, so trade counts are not comparable to
baseline — only net profit, drawdown, and profit factor are.

### Test A — nearer target (internal volume wave)

Requires new code: the base-only profile currently exposes only the principal POC zone and the
next mapped zone as target. Книга 2.0 p.32 / `APOLLO_COURSE_SOURCE_NOTES.md` state that internal
volume waves can be earlier, valid targets than the principal POC. Expose the strongest internal
wave between entry and the current target, and use it as the target when present.

- **If the current target is too far** (the motivating hypothesis): a nearer target should convert
  more trades to take-profit exits, raising win rate and reducing reliance on the timeout.
- **If not**: it should simply cut winners short and reduce net profit.

## What would count as a real finding

A change is only considered real if it moves **both** test symbols in the **same direction**, and
survives the 1.5x cost-stress run already built into the acceptance evaluator. Anything else is
recorded and dropped. No configuration discovered here is promoted to validation on the basis of
these tests alone — roadmap step 6 (pre-registration, revised evidence floor, walk-forward) still
applies first.

---

# Results (2026-08-10)

All three symbols ran every variant. 21 backtests total, all against the predeclared design above.

## Test B — holding-period sensitivity: INCONSISTENT (predeclared verdict: not real)

| Symbol | 48 (12h) | 96 (24h, baseline) | 144 (36h) | 192 (48h) |
| --- | ---: | ---: | ---: | ---: |
| ETHUSDT | +$3,758 (PF 1.120) | +$4,408 (PF 1.127) | +$4,220 (PF 1.109) | +$4,409 (PF 1.106, MAX_DD) |
| XRPUSDT | +$13,762 (PF 1.722) | +$9,479 (PF 1.378) | +$11,059 (PF 1.380) | +$15,777 (PF 1.535) |
| BTCUSDT | +$2,416 (PF 1.205) | +$2,411 (PF 1.174) | +$6,005 (PF 1.413) | +$5,160 (PF 1.357) |

Three symbols, three different shapes: ETH is **flat** (PF 1.106-1.127 across a 4x range of holding
period), XRP is **U-shaped** (both extremes beat the middle), BTC **improves with longer holds**.
By the predeclared criterion this is the "arbitrary" branch — the holding period is not a reliable
lever, and BTC's improvement is not corroborated.

**But the underlying mechanism is confirmed.** Comparing 96 vs 144 exit-reason breakdowns:

| Symbol | timeout exits | take-profits | stop losses |
| --- | --- | --- | --- |
| XRP 96 → 144 | 23 → 12 | 7 → 11 | 43 → 49 |
| BTC 96 → 144 | 6 → 3 | 5 → 7 | 23 → 24 |

Per-trade economics inside each bucket are stable (XRP take-profit averages $3,036 → $2,807 per
trade; stop losses -$531 → -$543), so the extra time genuinely **converts timeout exits into real
target hits** rather than inflating existing ones. It also converts some into stop losses, and the
net balance of that trade-off differs by symbol — which is exactly why the aggregate result is
inconsistent.

## Test C — partial exit at 1R: CONSISTENT (real finding)

| Symbol | Baseline net | Partial-1R net | Baseline DD | Partial-1R DD |
| --- | ---: | ---: | ---: | ---: |
| ETHUSDT | +$4,408 (PF 1.127) | +$445 (PF 1.015) | 6.57% | 4.90% |
| XRPUSDT | +$9,479 (PF 1.378) | +$7,522 (PF 1.374) | 7.21% | 5.60% |
| BTCUSDT | +$2,411 (PF 1.174) | +$626 (PF 1.054) | 5.61% | 3.60% |

**All three symbols lose profit and all three gain drawdown protection.** This is the predeclared
"edge is in a long tail" branch, confirmed unanimously: the strategy's profit depends on letting a
minority of winners run to full target, and taking half off at 1R destroys most of it. The
drawdown improvement is real and consistent, so partial exits remain a legitimate *risk* tool —
but they are not a profit improvement.

## Test A — internal volume-wave target: INCONSISTENT (predeclared verdict: not real)

| Symbol | wave off (0) | 0.20 | 0.35 |
| --- | ---: | ---: | ---: |
| ETHUSDT | +$4,408 / 92 tr | -$5,209 / 21 tr | -$9,113 / 27 tr (MAX_DD) |
| XRPUSDT | +$9,479 / 73 tr | +$5,302 / 23 tr | +$12,094 / 29 tr (PF 2.219) |
| BTCUSDT | +$2,411 / 34 tr | +$5,799 / 26 tr (PF 1.594) | +$3,310 / 30 tr |

ETH is strongly worse, BTC better, XRP mixed and non-monotonic. Rejected by the predeclared
criterion.

**Additional confound worth recording:** trade counts collapse (ETH 92→21, XRP 73→23, BTC 34→26).
A nearer target means less reward, so many candidates now fail the unchanged `minimumRewardRisk=3`
gate and never enter at all. Test A therefore does not isolate "same trades, nearer target" — it
silently also filters the entry set, so even its BTC/XRP improvements are not clean evidence about
target selection.

## Conclusion: the motivating hypothesis is refuted

The observation that started this (roughly half of profit arriving via the 24-hour timeout) was
real, but the implied remedy was wrong in both directions:

- Cutting winners shorter (Test C, partial at 1R) **destroys** profit — unanimously.
- Choosing a structurally nearer target (Test A) does **not** consistently help.
- Giving trades more time (Test B) helps some symbols and not others.

**The likely explanation is a selection effect, not a mechanism.** Trades that hit their stop exit
early as losers; trades that hit target exit early as winners; what is still open at the 24-hour
mark is disproportionately the set that has been drifting favourably but slowly. Those exits
*winning 86-100% of the time is therefore close to tautological* and is not evidence that the
timeout is creating value. Reading it as "the timeout is doing useful work that a better target
could do deliberately" was the error.

**Exits and targets are not the lever.** The frozen baseline (`maximumHoldingBars=96`, mapped-zone
target, no partial) is retained unchanged: no variant beat it consistently across all three
symbols. Partial exits are recorded as an available drawdown-reduction tool with a known profit
cost, not as an improvement.

Roadmap step 2 is closed. The next open items are step 3 (extend labelled dataset to entry
decisions) and step 4 (multi-timeframe swing hierarchy), which target *entry quality* — where,
given that the profit demonstrably lives in a minority of far-running trades, better selection of
which trades to take is more likely to matter than how they are exited.
