# Project Status

## Course-derived level strategies (2026-08-07)

- Audited all user-requested course assets: 34 PDFs / 655 rendered pages and 298 PNGs. JPG/video assets were outside scope.
- Added `GERCHIK_COURSE_STRATEGY_RESEARCH.md` with source-derived concepts, explicit crypto formalizations, limitations, and frozen BTCUSDT training results.
- Added configurable `gerchik-level` reactions: bounce, breakout, and false-breakout, with pivot-cluster BSU/BPU proxy, ATR level tolerance, structural stop, and >=3R target.
- Frozen v1 BTCUSDT 15m training results: bounce -0.76% (3 trades); false breakout -48.64% (2,637); corrected breakout 0.00% (0 trades). An earlier permissive breakout-compression defect produced -98.52% / 5,271 trades and is retained only as a rejected diagnostic result.
- Conclusion: one/two-bar price-pattern proxies are insufficient. Next iteration should add strict compression/reclaim semantics, opposing-level room, higher-timeframe context, and rejection-reason diagnostics before any parameter search.
- Verification: 69 tests pass on JDK 25.

Last updated: 2026-08-10

### 15-symbol historical cutoff extended to 2022-10-01 (2026-08-11)

All importable historical data for BTC, ETH, SOL, XRP, BNB, ADA, DOGE, LINK,
LTC, AVAX, BCH, TRX, AAVE, DOT, and ETC USDT perpetuals now starts at
2022-10-01 UTC: 1m/5m/15m/1h candles, funding rates, aggregate-trade-minute
records, and their import metadata. The completed aggregate-trade cutoff is
2026-08-09 23:59 UTC (the final fully archived UTC day); candles and funding
are additionally current into 2026-08-11. No `futures_volume_profile_bin` rows
were created or modified for this backfill: that table's earliest row remains
2023-01-01. Open-interest and trader-ratio statistics remain unavailable for
historical backfill because Binance exposes only the latest 30 days.

### Full 15-symbol history import in progress (2026-08-10)

The requested universe is BTC, ETH, SOL, XRP, BNB, ADA, DOGE, LINK, LTC,
AVAX, BCH, TRX, AAVE, DOT, and ETC against USDT perpetuals. Funding-rate
history is complete for all 15: 3,953 events per symbol from 2023-01-01 through
the latest available event. A gap-aware, idempotent candle backfill is actively
filling 1m/5m/15m/1h from 2023-01-01 and the current suffix without replaying
already valid data. Aggregate-trade-minute and 15-minute volume-profile-bin
imports remain the large next stage.

Binance only exposes the most recent 30 days of open-interest and trader-ratio
statistics, and no `BINANCE_API_KEY` is configured locally. Those supporting
tables cannot be backfilled to 2023 from the documented API; this is a source
availability limit, not a missing import loop.

### Apollo V5: multi-day map search, volume-first base ranking, third-touch discount

A separate, parallel strategy alongside V4 (`apollo-v5-base-poc-continuation`,
`ApolloV5BasePocContinuationStrategy`, `VariableBaseDetectorV5`,
`VolumeProfileFeatureAssemblerV5`; `APOLLO_V5_DESIGN.md`). V4's own code,
config, and frozen results below are unchanged. Following a source review that
watched the labelled concept clips and sampled daily BTC/ETH chart videos
alongside the two PDFs, V5 fixes three gaps versus the course material: the
base detector now searches up to `baseMapLookbackDays` (7) days back instead
of only the 12-48 candle window ending immediately before a breakout; among
geometrically valid candidates the map prefers the one with the strongest
volume concentration (highest POC share) rather than the largest flat shape,
with the multi-day tier preferred over the short one whenever it qualifies
(a raw POC-share comparison always favors a shorter window, so ranking across
scales rather than within one silently makes the wider search a no-op); and a
boundary already approached three or more times before its own breakout is
discounted, per Книга 2.0 p.98 and the `xrp.mp4` example.

Instrumented verification (not part of the frozen run) confirmed the map does
real work: over the two-year BTC training window the wider search evaluates
22,269 geometrically valid multi-day candidates versus 31,868 short-scale;
2,796 pass breakout confirmation, 1,225 survive the third-touch filter and
produce a valid profile. Frozen re-run on `[2023-05-07, 2025-05-07)`: 2
trades, +$329.80 net (+0.33%), PF 1.63, 0.63% maximum drawdown — far below the
60-trade evidence floor. Re-run on the post-final-test 2026-05-07–2026-08-01
video-review window (86 days, where V4.1 produced a flat zero-entry false
negative): 126 bases mapped (10 multi-day) and 118 consumed, but still 0
completed trades — the map now functions where it previously produced
nothing, but the swing-reversal and reward/risk gates downstream remain the
bottleneck. Neither run justifies threshold tuning; the next step is
labelled-base comparison against the now-functioning map.

### Apollo V5 nine-symbol training run and improvement roadmap (2026-08-10)

BTC, ETH, SOL, XRP, BNB, ADA, DOGE, TRX, and LINK were the first nine symbols
with complete aggregate-trade and volume-profile-bin coverage (the remaining
six — LTC, AVAX, BCH, AAVE, DOT, ETC — are still being imported). Each ran
independently over `[2023-05-07, 2025-05-07)`: 15 trades pooled, -$273.51 net.
Only BTC (+$329.80, 2 trades) and BNB (+$67.40, 3 trades) were positive; SOL
was a clean loser (0/3, PF 0); XRP, ADA, DOGE, TRX, and LINK produced zero
entries over the full two years. No symbol clears the evidence floor, and
extending the universe repeated the project's established pattern (e.g. the
B5/C1 rejection): a BTC-calibrated proxy does not transfer broadly. Full
per-symbol table in `APOLLO_V5_DESIGN.md`.

This result, together with the instrumented finding above that the
swing-reversal/reward-risk gates (not the base map) are now the binding
constraint, sets a four-step improvement roadmap recorded in
`APOLLO_V5_DESIGN.md`: (1) build a labelled base/entry dataset from the course
videos and check the detector against it — the step `APOLLO_COURSE_SOURCE_NOTES.md`
has called for since it was written and that has still never been done; (2)
implement the liquidity/POC-limit entry family (source Family B), which does
not depend on the swing-reversal gate; (3) implement the hook-trigger entry
family (source Family C); (4) add an explicit 1h/4h swing-hierarchy filter,
since the current swing-reversal check only ever reasons about 15m pivots.
Widening `minimumRewardRisk` or the volume-ratio thresholds to manufacture
more trades is explicitly out of scope, per this project's standing rule that
post-hoc threshold changes are not evidence.

### Apollo V5 labelled dataset and touch-filter re-scoping (2026-08-10)

`APOLLO_LABELLED_EXAMPLES.md` records a first batch of 20 source-derived examples and checks the
V5 detector against the two with exact prices/dates: it finds candidates matching the labelled
ETHUSDT and BTCUSDT August-2026 boxes to within a few dollars, over windows only reachable via the
new multi-day search — the first time any Apollo version has been checked against ground truth
rather than just backtested. Dumping the ETH match's touch detail found its 3rd counted low-boundary
touch (2026-08-03, extreme 1,827.18) is the documented course entry trigger itself, not noise;
`maximumBoundaryTouches` was scoped to pre-breakout base formation, which isn't what Книга 2.0
p.98's warning is about, and that risk is already handled post-breakout by "first revisit consumes
the base." The filter is disabled (`strategy.maximumBoundaryTouches=999`) on this conceptual
finding. Re-running the nine-symbol diagnostic with it disabled produced nominally worse aggregate
numbers on BTC/ETH/SOL; this is recorded rather than acted on, since every affected sample (3-7
trades) is far below the evidence floor and reversing on that swing would repeat the same
post-hoc-tuning mistake in the other direction.

### Apollo V5 Family B (liquidity/POC-limit entry): first candidate past the trade-count floor (2026-08-10)

`apollo-v5-liquidity-limit` reuses V5's exact map unchanged and differs from Family A only in the
entry decision: it acts on a zone's first revisit and reclaim directly, without waiting for the
completed swing reversal Family A requires. This confirms the swing-reversal gate was the dominant
bottleneck: ETHUSDT went from 7 trades to 92, BNBUSDT from 3 to 37, BTCUSDT from 2-3 to 34.
**ETHUSDT passes 6 of 8 acceptance criteria** — net profit +$4,408.26, PF 1.13, 6.57% max drawdown,
**trade count 92 (>=60, the first Apollo variant ever to clear this floor)**, average win/loss
2.33, and positive stressed-cost profit — but fails subperiod stability (2 of 4 profitable) and
concentration (83.5% of profit from one subperiod). BNBUSDT is similarly strong (+$5,566.56, PF
1.56, 37 trades) but short of the trade-count floor. BTCUSDT is negative (34 trades, -$835.21).
XRP, ADA, DOGE, TRX, and LINK remain at zero trades under both families — the block for those is
upstream, at the map/volume-quality gates, not the entry-timing choice. Not a validation candidate:
concentration/stability failure is this project's standing signal a result isn't robust yet, and
validation/final-test data remain unopened. Full detail in `APOLLO_V5_DESIGN.md`.

### Volume-profile price-bin fix and first-ever training acceptance pass (2026-08-10)

The zero-trade-symbol block (XRP, ADA, DOGE, TRX under both Apollo V5 entry families) traced to a
degenerate volume-profile price bin: a single hardcoded `profilePriceStep=$10` for every symbol
meant XRP/ADA/DOGE/TRX's entire training-window price history (all under $10) collapsed into one
bin, making the mapped zone always exactly `[0, 10)` and a reclaim beyond it mathematically
impossible. Fixed with a declared per-symbol step table (`VolumeProfilePriceSteps`), wired as the
default everywhere `profilePriceStep` is read; the five affected symbols' stale bins were deleted
and re-imported at 402-674 distinct price levels each instead of 1-4.

Re-running both Apollo V5 entry families on the fixed data: **XRPUSDT Family B (liquidity/POC-limit)
passes all 8 training acceptance criteria** - net +$10,146.42, PF 1.55, 60 trades, 3 of 4 profitable
subperiods, 54.3% concentration (cap 60%). This is the first strategy of any kind in this project's
history to fully pass. It is treated as a promising lead, not a validated result: the account hit
`PROFIT_TARGET_REACHED` and stopped trading with the last ~3 months of the training window
untested, the edge is almost entirely short-side ($9,857 short vs. $290 long net), subperiod 4's
12.66 PF is 70% one calendar month, and subperiod 3 lost -$6,039 at PF 0.12. Validation/final-test
remain closed. DOGE and LINK Family B both hit `MAX_DRAWDOWN` termination and are rejected; ADA (9
trades) and TRX (37 trades, -$6,060.82) are also below the evidence floor or negative. Full detail
in `APOLLO_V5_DESIGN.md`.

### Apollo V5 review and fresh roadmap (2026-08-10)

`APOLLO_V5_ROADMAP.md` supersedes the four-point "Next research steps" list in
`APOLLO_V5_DESIGN.md`. Its key finding is methodological: **56 backtests were run against the same
training window in one session**, and across five bin-granularity configurations the same
symbol/family flips sign repeatedly (BNBUSDT Family B: +$5,567 → -$732 → +$835 → -$1,880 →
-$3,013). A real edge does not flip sign five times on bin width, so most of these differences are
noise at 3-92 trade samples, and the XRPUSDT all-criteria training pass must be treated as a
best-of-56 result rather than a validation candidate.

Three findings do survive: the swing-reversal gate (not base detection) was the binding constraint;
the multi-day search matches real course-labelled structures to within a few dollars (the only
result immune to the multiple-testing problem, since it is checked against ground truth rather than
P&L); and a coarser analysis bin mechanically inflates POC/zone share, whose correction fixed a real
BTC regression.

New highest-value lead: across every profitable Family B run, roughly half the profit comes from the
**24-hour maximum-holding-period timeout**, not from reaching the mapped target (ETH: 21
holding-period exits win 100% for +$18,444 against 62 stop-losses for -$34,699). The strategy is not
doing what it is nominally designed to do, target selection is the likely cause, and this has never
been investigated. Roadmap order is therefore: freeze granularity → exit/target structure →
extend labelled entries → multi-timeframe hierarchy → hook-trigger (deprioritized: 0 of 20 labelled
examples show it) → fix the evidence standard (pre-registration, winner-count floor, walk-forward)
before opening validation.

### Apollo V5 exit/target tests: motivating hypothesis refuted, step 2 closed (2026-08-10)

Roadmap step 2 executed with a **predeclared** design (`APOLLO_V5_EXIT_TESTS.md`, written before any
result was inspected — the response to the 56-run multiple-testing problem). 21 backtests across
ETHUSDT/XRPUSDT/BTCUSDT:

- **Test C (partial exit at 1R): consistent, real.** All three symbols lose profit (ETH +$4,408 →
  +$445, XRP +$9,479 → +$7,522, BTC +$2,411 → +$626) and all three gain drawdown protection
  (6.57%→4.90%, 7.21%→5.60%, 5.61%→3.60%). Confirms the edge lives in a long tail; partial exits
  are a legitimate risk tool with a known profit cost, not an improvement.
- **Test B (holding period 12/24/36/48h): inconsistent.** ETH flat (PF 1.106-1.127 across a 4x
  range), XRP U-shaped, BTC improving. The underlying mechanism *is* confirmed — longer holds
  genuinely convert timeout exits into target hits with stable per-trade economics (XRP 96→144:
  timeouts 23→12, take-profits 7→11) — but the net balance differs by symbol.
- **Test A (internal volume-wave target): inconsistent and confounded.** ETH strongly worse, BTC
  better, XRP mixed. Trade counts also collapse (ETH 92→21) because a nearer target fails the
  unchanged 3R gate, so the test silently filters the entry set rather than isolating target choice.

**Conclusion: exits/targets are not the lever, and the motivating observation was a selection
effect.** Trades hitting stop or target exit early; what remains open at 24h is disproportionately
the set drifting favourably but slowly, so those exits winning 86-100% is near-tautological rather
than evidence the timeout creates value. The frozen baseline is retained unchanged and verified
byte-identical (ETH 92 trades, +$4,408, PF 1.127). Next open items are roadmap steps 3 (extend
labelled dataset to entry decisions) and 4 (multi-timeframe hierarchy) — both target entry quality,
which matters more given the profit lives in a minority of far-running trades.

### Research calendar shifted three months earlier (2026-08-11)

At the user's request, the active calendar moved from training `[2023-05-07, 2025-05-07)` /
validation `[2025-05-07, 2025-11-07)` / final `[2025-11-07, 2026-05-07)` to **training
`[2023-02-07, 2025-02-07)` / validation `[2025-02-07, 2025-08-07)` / final
`[2025-08-07, 2026-02-07)`**. Purpose: place every 2026 course review video *after* the reserved
final-test window, so the ~77 ETHUSDT.P daily videos (and a further Feb-May 2026 set the user can
supply) can be used for labelling without burning held-out data. Everything from 2026-02-07 onward
is now post-final-test. Volume-profile bins start 2023-01-01, so the new training start retains
map-lookback runway. Frozen historical experiment configs keep their original dates per existing
convention; **results produced before this change are not directly comparable to results after it.**

Family B re-baselined on the new window (old → new): ETH 92 tr/+$4,408/PF 1.127 → 98 tr/+$3,099/PF
1.086; XRP 73 tr/+$9,479/PF 1.378 → **92 tr/+$13,319/PF 1.475**; BTC 34 tr/+$2,411/PF 1.174 →
**40 tr/-$3,791/PF 0.776**; SOL 14 tr/+$3,999 → 23 tr/+$3,643/PF 1.418; BNB 5 tr/-$3,013 → 6
tr/-$3,680. BTC flipping from +$2,411 to -$3,791 on a three-month window shift, with no code change,
is another instance of the instability documented in `APOLLO_V5_ROADMAP.md` §3 and should be read
that way rather than as a BTC-specific result.

XRPUSDT Family B now passes **7 of 8** acceptance criteria on the new window (net +$13,319, PF 1.475,
8.82% drawdown, 92 trades, 51.7% concentration, 2.00 win/loss, positive cost-stress), failing only
profitable-subperiod count (2 of 4, needs 3). This is *not* treated as progress toward validation:
it is the same candidate re-measured on a different window after ~80 training runs, which is exactly
the best-of-N problem roadmap §3 warns about. Roadmap step 6 (pre-registration, revised evidence
floor, walk-forward) still gates any validation attempt.

### Apollo V4.1 persistent-base implementation: training diagnostic rejected

V4.1 replaces the immediate-breakout-only feature path with a causal map of
profiled bases. A base is published only after its breakout and acceptance
candle; it remains fresh until its first later POC-zone touch, then is consumed
and exposed for one lower-timeframe confirmation window. An entry now requires
sweep/reclaim, a completed named swing break, a retest/hold, and sufficient room
to the nearest still-fresh mapped node. The map target replaces the former
fixed 3R target. This is an initial automatic detector, not the video-labelled
map promised by the course.

On shifted BTC training `[2023-05-07, 2025-05-07)`, it completed 3 trades for
-$218.61, PF 0.80 (1.07% maximum drawdown). ETH over the same window completed
8 trades for -$1,914.73, PF 0.14 (2.23% drawdown). Both are rejected as a
performance candidate: negative and far below the minimum evidence sample.
The BTC post-final video-review diagnostic `[2026-05-07, 2026-08-01)` made no
entries. It is not a validation result; it directly confirms that the current
automatic base detector does not yet reproduce enough of the bases visible in
the videos. ETH has no May-August 2026 aggregate-trade/profile coverage yet,
so its equivalent recent run is pending data import rather than reported as a
zero-trade strategy result.

### Apollo expanded-universe transfer test: B5 and C1 rejected

Seven deferred symbols (LTC, AVAX, BCH, TRX, AAVE, DOT, ETC) were imported with
verified 1m/5m/15m/1h Futures histories. The frozen B5 variant returned
-$5,504.70 across 53 new-symbol trades; the C1 B5+B8 combination returned
-$2,502.55 across 38. The earlier eight-symbol positives therefore did not
transfer to a previously unused liquid universe. Neither may proceed to
validation; this proxy family is rejected as a broad systematic strategy.

### Apollo V4 direction: base POC revisit, then confirmation

The user clarified the intended course pattern: a selected flat base provides a
base-only POC; after breakout and later revisit, POC is an alert zone and entry
requires sweep/reclaim plus local trend-break confirmation in the original
breakout direction. `APOLLO_V4_DESIGN.md` freezes this state machine and starts
with labelled base examples, avoiding the rejected fixed-window POC retest and
pivot-cluster proxy.

### Apollo V4 BTC-only first diagnostic: base detector rejected

The new BTC-only V4 uses stored aggregate-trade base profiles and requires
breakout, first POC-zone revisit/reclaim, and separate local continuation break.
Its initial mechanical base detector nevertheless produced 64 trades, -$5,617.25
net, PF 0.724, and maximum-drawdown termination. V4's entry sequence is now
testable, but the automatic base-selection proxy is too permissive; next work is
labelled base/map comparison, not threshold tuning or validation.

### Apollo V4 BTC-only base-volume quality gate: still negative

The automatic V4 base proxy now requires a principal POC share of at least 5%
of base volume and total base quote volume at least 1.20x the immediately prior
same-duration window. This reduced the old training diagnostic from 64 to 13
trades and avoided drawdown termination, but remained negative: -$2,504.15 net,
PF 0.45, four wins and nine losses. The result diagnoses base selection; it is
not a validation result and no more threshold tuning is justified before labels.

### Active research calendar moved three months earlier

At the user's request, the active default calendar is now training
`[2023-05-07, 2025-05-07)`, validation `[2025-05-07, 2025-11-07)`, and final
`[2025-11-07, 2026-05-07)`. Frozen historical experiment configurations retain
their original dates so their recorded results remain reproducible. BTC aggregate
trade archives and 15-minute profile bins were extended through the added
May–July 2023 history.

### Apollo V4 shifted-window BTC training: rejected diagnostic

With the unchanged V4 volume-quality settings over the shifted two-year training
period, the result was 20 trades, -$2,697.22 net, PF 0.60, and 4.05% maximum
drawdown. Only one of four six-month subperiods was profitable; 1.5x-cost stress
was -$3,162.46. This remains training-only and confirms that the current
automatic base detector is not a viable implementation of the course pattern.

### Apollo V4 swing-confirmation revision: nearer breakeven, insufficient

Following the later Apollo videos, V4 no longer treats a three-bar range break
as “слом тренда.” It requires a completed 15m swing sequence: sweep/reclaim,
pivot in the reversal direction, a higher low/lower high, then close through the
intervening pivot. With all other V4 settings fixed, shifted BTC training produced
16 trades, -$292.03 net, PF 0.945, and 2.75% maximum drawdown; 1.5x-cost stress
was -$519.72. This is much less negative than the prior -$2,697.22 diagnostic,
but the sample is far below 60 and only one half-year was profitable. It does not
justify validation or parameter tuning; it supports moving to labelled bases and
the course's hierarchical map.

### Apollo V4 May–June 2026 video replay: automatic map false negative

Public Binance BTCUSDT aggregate trades and 15-minute profile bins were imported
for the supplied 7 May–1 June 2026 video period. The revised V4 produced zero
entry decisions across its 2,496 15-minute bars. This is not a strategy result:
the videos identify higher-timeframe bases/ranges that can be days or weeks old,
whereas V4's current `VariableBaseDetector` accepts only a 12–48 candle base
ending immediately before breakout. The replay therefore confirms a mismatch in
our map builder, not that the course setups are absent. Exact price comparisons
also require care because the videos use CME BTC futures while the code uses
Binance BTCUSDT perpetual.

### Apollo V4.1 requirements frozen from the May–August video review

The course examples consistently require persistent multi-day base/POC maps,
first-visit consumption, named-swing break plus retest, and next volume-node or
liquidity targets. Fixed 3R targets, RSI divergence, and position splitting are
not to be treated as the core entry edge. `APOLLO_V4_DESIGN.md` records these
requirements before V4.1 implementation and labelled-video audit.

### Apollo v3 one-variable test B1: three touches — insufficient sample

At the user's request, a single exploratory training-only comparison reduced
only the mapped 4h pivot-touch proxy from four to three; all other Book 2.0,
entry, risk, and execution assumptions were unchanged. Across the full eight-
symbol universe, filled trades increased from 10 to 12 and independent-account
aggregate net PnL changed from -$2,296.85 to +$1,281.92. Every symbol except
ADA was identical; the two additional ADA observations were one TP and one
maximum-holding-period exit. This is a hypothesis lead, not an edge: 12 trades
cannot establish profitability. The auditable configuration and comparison are
in `apollo-ordered-liquidity-sequence-v3-map-3-touches.properties` and
`APOLLO_V3_ASSUMPTION_TESTS.md`. No validation or further data was opened.

### Apollo v3 one-variable test B2: four-hour freshness — rejected

Keeping the B1 three-touch map but shortening the untouched-level proxy from
12 hours to four hours increased filled trades from 12 to 35, but aggregate
independent-account net PnL collapsed from +$1,281.92 to -$9,009.06. Six of
eight symbols lost, and the added observations were predominantly stop-outs.
The 12-hour freshness rule remains fixed for the next isolated comparison.
The complete per-symbol result is recorded in `APOLLO_V3_ASSUMPTION_TESTS.md`;
no validation/final data was opened.

### Apollo v3 one-variable test B3: 0.75-ATR map tolerance — inconclusive

Keeping B1's three-touch, 12-hour-fresh setup but widening only the map-area
cluster tolerance from 0.50 to 0.75 ATR increased filled trades from 12 to 16.
Aggregate independent-account net PnL fell from +$1,281.92 to +$756.13; three
of four added trades lost. With this sample it neither validates nor rules out
the broader map proxy, but does not justify replacing the 0.50-ATR reference.
No validation/final data was opened.

### Apollo v3 one-variable test B4: eight-bar reclaim deadline — rejected

Keeping B1's settings but allowing eight rather than six 15-minute bars for
the reclaim increased filled trades from 12 to 14 and reduced aggregate
independent-account net PnL from +$1,281.92 to +$234.96. The only two added
trades (SOL and LINK) stopped out. The six-bar deadline remains the reference;
no validation/final data was opened.

### Apollo v3 one-variable test B5: one acceptance candle — research lead

Keeping B1's three-touch, 12-hour-fresh, 0.50-ATR map and six-bar reclaim
deadline but requiring one rather than two 0.20-ATR-bodied reclaim candles
increased filled trades from 12 to 52 and aggregate independent-account net
PnL from +$1,281.92 to +$1,646.98. This is still below the 60-trade
low-frequency evidence floor and BTC/SOL remain materially negative, so it is
not a strategy candidate or a basis to open validation. It is the first
meaningful Apollo assumption lead and is fully recorded in
`APOLLO_V3_ASSUMPTION_TESTS.md`.

### Apollo v3 one-variable test B6: two-bar structure break — rejected

The B6 audit found that break length had been coupled to sweep-search range.
`sweepSearchBars=10` is now explicit, preserving the prior B1 behavior while
allowing a genuine isolated break-length test. With only the local break reduced
from three bars to two, filled trades increased from 12 to 18 but aggregate
independent-account net PnL declined to +$137.78. The three-bar break remains
the reference; no validation/final data was opened.

### Apollo v3 one-variable test B7: 2.5R target room — rejected

Keeping B1's entry logic but lowering only the opposing mapped-target room from
3R to 2.5R increased filled trades from 12 to 27, but aggregate
independent-account net PnL was -$1,063.12. The 3R room requirement remains
the reference; no validation/final data was opened.

### Apollo v3 one-variable test B8: 1.20× break-volume confirmation — insufficient sample

Keeping B1 but raising only structural-break volume from 1.00× to 1.20× the
20-bar average reduced filled trades from 12 to 6 and returned +$4,739.19.
The apparent result is concentrated in two ADA observations and one XRP trade,
so it is not evidence for the threshold or a basis to open validation. The
complete comparison is in `APOLLO_V3_ASSUMPTION_TESTS.md`.

### Apollo v3 one-variable test B9: 0.20-ATR sweep — rejected

Raising only minimum sweep depth from 0.10 to 0.20 ATR reduced filled trades
from 12 to 9 and aggregate independent-account net PnL from +$1,281.92 to
+$469.10, including filtering the XRP winner. The 0.10-ATR sweep remains the
reference; no validation/final data was opened.

### Apollo v3 one-variable test B10: one-bar pivots — rejected

Reducing only 4h pivot confirmation from two neighboring bars to one did not
broaden the selected map: it yielded only three filled trades and -$1,836.74.
The denser pivots changed map selection, so two-bar pivots remain the reference.

### Apollo v3 one-variable test B11: 96-bar map lookback — rejected

Extending only the 4h map from 72 to 96 bars produced the same 12 filled trades
and +$1,266.87 versus B1's +$1,281.92. It did not increase the sample and is
rejected; 72 bars remains the reference.

### Apollo v3 one-variable test B12: 0.30-ATR acceptance bodies — insufficient sample

Raising only acceptance body size from 0.20 to 0.30 ATR selected six trades
and +$4,739.19—the same six trades as the B8 volume filter. This is an
overlapping insufficient-sample subset, not independent threshold evidence.

### Latest research gate: multi-timeframe expansion rejected

The frozen eight-symbol 2023-2025 run produced 76 trades, pooled PF 1.45, positive raw/net PnL,
and positive 1.5x-cost stress. A backward BTC/ETH extension to 2021 raised the combined unique
sample to only 90 trades and exposed weak stability and BTC profit concentration. It failed the
predeclared Stage-1 gate and was not promoted. Validation and final-test periods remain unopened.

### Apollo v3 variable-base gate: rejected

Variable 12-48 candle base selection, selected-window aggregate-trade POC, first-retest execution,
and completed-1h EMA alignment are implemented. The final predeclared sensitivity returned -5.38%,
PF 0.78, and 54 trades before drawdown termination; only one half-year was profitable and 1.5x
cost stress remained negative. The Apollo standalone branch is closed without opening validation.

The evidence-based forward plan is consolidated in `NEXT_RESEARCH_PLAN.md`. It ranks
multi-timeframe BTC flat-long first, Apollo base/POC research second, and a new
cross-sectional multi-symbol strategy as the preferred path to 1-2 daily portfolio
opportunities. Rejected branches are explicitly closed to prevent threshold churn.

### Cross-sectional long-pullback v1: rejected

The frozen eight-symbol training-only run ranked completed 1h 24-hour returns, required a
healthy BTC 1h regime, and entered 15m EMA-20 pullback reclaims only in the top three assets.
It produced 957 trades with -32,337.46 pooled net PnL, -507.66 raw price-plus-funding PnL,
and PF 0.835. The two-position, 1.5x-correlated-notional portfolio replay returned -16.60%
with 32.28% realized drawdown. DOGE and XRP were positive, but the remaining six symbols lost;
1.5x-cost independent-account net PnL was -42,384.77. The failure begins before execution costs,
so this exact rule family is closed without validation or final testing.

### Apollo liquidity-sweep reversal v1: insufficient sample

The new causal proxy detects an unswept pivot-cluster pool, a sweep beyond it, a reclaim, a
three-bar local structural break, and above-average confirmation volume; it requires the nearest
opposing pool to leave at least 3R. Its frozen BTCUSDT 15m training run made zero entry decisions
and therefore zero trades. This is not a performance result. It rejects the numerical proxy as too
restrictive to evaluate; no thresholds were loosened and validation/final data remain unopened.

### Apollo higher-timeframe liquidity-sweep v2: rejected

V2 replaced the 15m-only map with a causal 4h pivot-cluster liquidity map derived from stored 1h
candles; 15m supplied the sweep, reclaim, local-break, and volume-confirmation trigger, while the
4h map supplied the target. The complete eight-symbol training run was not stable: BTC +10.13%
(65 trades, PF 1.381) and XRP +10.11% (78 trades, PF 1.342), while ETH, SOL, BNB, ADA, DOGE, and
LINK lost between 3.03% and 10.01%; five reached the 10% drawdown termination. The independent
account aggregate net PnL was -28,498.42. This is not a portfolio candidate and must not be
rescued by selecting BTC/XRP after inspecting the same training set.

### Apollo ordered-liquidity-sequence v3: insufficient sample

V3 is a stricter source-derived proxy, distinct from the earlier variable-base experiment also
called v3: it requires a recently untouched mapped 4h level, a separate 15m sweep, a directional
reclaim within four bars, and only then a completed 15m three-bar structural break with 1.20x volume
and 3R room to the opposing mapped level. Across the unselected eight-symbol training universe it
made only 9 filled trades: BTC 0, ETH 1 (-0.54%), SOL 2 (-0.13%), XRP 0, BNB 2 (+0.04%), ADA 2
(-0.25%), DOGE 1 (-0.52%), and LINK 1 (-0.51%). Aggregate independent-account net PnL was
-1,904.53. This is far below the evidence floor, so it is not evidence of an edge or of its absence;
the automatic proxy is too restrictive to evaluate and must not be loosened by threshold searching.
The next legitimate Apollo work is labelled visual examples/base selection.

### Apollo Book 2.0 assumption set A: insufficient sample

`Книга 2.0.pdf` was fully reviewed and its durable rules are recorded in
`APOLLO_COURSE_SOURCE_NOTES.md`. With the user's authorization to make ambiguity assumptions,
Apollo's configurable v3 proxy was rerun using four 4h clustered pivot touches over 12 days as the
base proxy, 12-hour freshness, a 0.10 ATR sweep, two 15m 0.20-ATR-bodied acceptance candles within
six bars, a three-bar local break, and 3R opposing-map room. The all-symbol training result was
10 filled trades: BTC +0.30% (1), ETH 0 (0), SOL -1.07% (2), XRP +1.91% (1), BNB -0.73% (1), ADA
-0.55% (1), DOGE -0.55% (1), LINK -1.61% (3). Independent-account aggregate net PnL was
-2,296.85. This set is not a strategy candidate; it is a documented insufficient-sample result.
All assumptions are configuration properties, so a later alternate assumption set can be compared
without silently changing the implementation.

### Dataset-discipline exception: final-test examples opened

At the user's explicit request on 2026-08-09, BTCUSDT and ETHUSDT hourly candles around 2026-08-04
were read from the reserved final-test period solely to interpret supplied 4h Apollo chart examples.
The final-test period can no longer be described as unopened or used as an unbiased final evaluation
of an Apollo version informed by these examples. No performance backtest was run over it.

## Current position

The BTCUSDT and ETHUSDT historical pipelines now contain at least three years
of 1m, 5m, 15m, and 1h data, with reusable incremental closed-candle
synchronization for both. Phase 3 is complete for both symbols: three years of funding
rates and the full Binance-retained window of 5m supporting statistics are
stored. The agreed initial Phase 4 BTCUSDT 15m feature slice is complete
and ready to feed the backtester. Phase 5 now has a configurable, extensible
backtesting engine and one deliberately unoptimized EMA-pullback baseline.
Phase 6 metrics and chronological dataset controls are complete. Phase 7 now
has three materially different strategy candidates and a shared automated
acceptance gate; all initial training baselines were rejected. The RSI/ATR
candidate is the closest so far. Validation and final-test results remain
unopened.

## Completed

- The project uses one Maven module and Java 25.
- Binance USD(S)-M Futures is the selected market; Spot is out of scope.
- The Futures REST API retrieves BTCUSDT klines from one year ago.
- Jackson parses all required kline fields.
- PostgreSQL 17.10 is installed locally with Homebrew and runs as a service.
- The local development database is named `prop_strategy`.
- Flyway migration `V1__create_futures_kline.sql` creates one unified
  `futures_kline` table.
- `(symbol, interval, open_time)` is the table's primary key.
- The JDBC repository performs transactional, idempotent batch upserts.
- The production importer writes 1,000-row batches directly to PostgreSQL.
- Imports are resumable when existing rows form a complete prefix; sparse or
  gapped data causes a safe idempotent restart from the requested beginning.
- Binance calls are paced and retry transient I/O failures, HTTP 418/429, and
  HTTP 5xx responses with backoff.
- Sixteen parsing, pagination, cursor, and interval-alignment unit tests pass.
- Three complete years of BTCUSDT were imported and verified for all four
  selected intervals.
- ETHUSDT was imported from 2023-08-06 UTC through the latest closed candle and
  verified for all four intervals: 1,579,518 x 1m, 315,903 x 5m, 105,301 x
  15m, and 26,325 x 1h.
- The incremental sync resumes after each interval's latest database candle,
  excludes the currently open candle, and verifies the final timestamp.
- An incremental run appended 52 new closed candles: 42 x 1m, 8 x 5m, and
  2 x 15m; 1h was already current.
- Flyway migrations V2 and V3 create `futures_funding_rate` and preserve
  unavailable historical mark prices as SQL `NULL`.
- The funding-rate importer is paginated, paced, retryable, idempotent, and
  resumes after the latest stored funding event.
- Three years of BTCUSDT funding-rate history were imported and verified.
- ETHUSDT funding history contains 3,291 verified events from 2023-08-06
  through 2026-08-06.
- Binance limits open-interest and trader-ratio REST history to approximately
  one month. The top-trader account and position endpoints also require a
  Binance API key.
- Flyway migration V4 creates separate open-interest and typed trader-ratio
  tables with idempotent primary keys.
- Supporting-statistics pagination runs backward because Binance returns the
  newest records up to `endTime`; this behavior was verified against the live
  API and prevents older pages from being skipped.
- The complete rolling 30-day BTCUSDT window is stored at 5m resolution for
  open interest, global account ratio, top-trader account ratio, and top-trader
  position ratio. Each dataset has zero non-5m gaps.
- ETHUSDT also has 8,640 rows each for 5m open interest, global account ratio,
  top-trader account ratio, and top-trader position ratio.
- The project builds successfully with JDK 25.
- Recent BTCUSDT 15m candles load from PostgreSQL in chronological order.
- `FeatureGenerator` calculates EMA 20/50, Wilder RSI 14, Wilder ATR 14,
  20-period return volatility and volume ratio, candle return, and candle-shape
  percentages in one chronological pass.
- `FeatureRow` records candle close availability and the earliest next-candle
  execution time. Warm-up ends only after 50 candles.
- Funding, open interest, and trader ratios align using the latest observation
  at or before candle close; missing context remains `null`.
- A live preview generated 151 feature rows from 200 recent 15m candles and
  printed the latest 10 with all Futures context populated.
- Fifty tests cover downloads, cursors, formulas, warm-up, chronology,
  no-look-ahead alignment, long/short execution, fees, stops, and funding.
- Engine and strategy configuration live in separate tracked properties files.
- A strategy registry selects factories by type; each factory owns typed
  parameters, and each strategy declares parameterized feature requirements.
- `ParameterizedFeatureGenerator` supports arbitrary EMA, RSI, and ATR periods
  requested by a strategy without changing `FeatureSnapshot`.
- Strategy decisions express long/short entries with stop and target distances,
  explicit exits, or holds.
- Signals derived at candle close fill no earlier than the next candle open.
- The execution engine supports long and short positions, risk sizing, leverage
  caps, conservative same-bar stop/target ordering, adverse slippage, two-sided
  taker fees, funding cash flows, time/strategy exits, and prop-rule termination.
- Trade records separate gross PnL, entry/exit fees, funding, slippage costs,
  net PnL, and exit reason.
- The first database-backed run used 3,000 BTCUSDT 15m candles and 2,951
  post-warm-up feature bars. It produced 49 trades and stopped at the configured
  maximum drawdown: -9.95% return, 36.73% win rate, and 0.477 profit factor.
  This rejects the default parameters as a strategy candidate while validating
  the end-to-end engine path.
- Active UTC periods are training `[2023-05-07, 2025-05-07)`, validation
  `[2025-05-07, 2025-11-07)`, and final test
  `[2025-11-07, 2026-05-07)`.
- Range loading includes only the preceding candles required for indicator
  warm-up; warm-up bars cannot trade or affect period metrics.
- Final-test mode fails closed unless `-DconfirmFinalTest=true` is supplied.
- Phase 6 reports net profit/return, win rate, win/loss counts, average win and
  loss, expectancy, profit factor, drawdown, trade count, fees, funding,
  slippage costs, and prop termination.
- The untouched training-period run evaluated 70,154 bars. The baseline hit
  maximum drawdown after 27 trades with -10.62% net return, 18.52% win rate,
  -393.20 expectancy, and 0.167 profit factor. Fees were 6,479.38 versus only
  47.69 positive funding PnL, confirming the baseline should be rejected.
- A Donchian breakout candidate uses prior-candle entry/exit channels, volume
  confirmation, ATR-based stops, a configurable reward/risk target, and a
  maximum holding period. Its rolling channels and volume baseline explicitly
  exclude the current candle to prevent look-ahead.
- Acceptance thresholds are tracked independently from strategy and engine
  parameters and run by default for every training backtest. The evaluator
  checks overall profitability, profit factor, drawdown, trade count, four
  six-month subperiods, concentration of profits, average win/loss ratio, and
  a 1.5x fee/slippage stress run.
- The initial Donchian training run evaluated 70,163 bars and failed seven of
  eight acceptance checks: -9.05% return, 0.435 profit factor, 10.07% maximum
  drawdown, 33 trades, and only one profitable subperiod. Its 1.956 average
  win/loss ratio passed, but stressed net profit was -9,727.29. The baseline is
  rejected without opening validation or final-test data.
- The volatility-compression breakout requires a low previous-candle
  Bollinger-bandwidth percentile followed by a range break, ATR expansion, and
  above-average volume. Its initial training run evaluated 70,087 bars and
  failed seven of eight checks: -9.89% return, 0.271 profit factor, 10.14%
  maximum drawdown, 25 trades, and one profitable subperiod. Average win/loss
  ratio was 1.424, while stressed net profit was -10,706.52. It is rejected.
- The RSI/ATR mean-reversion strategy trades fresh RSI extreme crossings only
  with the EMA 200 trend, rejects fast ATR expansion, uses an ATR stop, and
  exits at RSI mean reversion or trend failure. It completed all 70,004
  training bars with -2.55% return, 0.755 profit factor, 3.80% drawdown, and 34
  trades. Two subperiods were profitable. It passed only the drawdown criterion
  and is rejected, but is the strongest baseline so far. Fees were 4,275.18
  and modeled slippage was 1,710.07.
- A reusable diagnostic report now separates raw price PnL, funding, fees,
  slippage, and net PnL; groups trades by side, exit reason, month, and prior
  24-hour trend regime; and reports holding time, MFE, MAE, consecutive losses,
  break-even cost, and execution-model details.
- The taker-only diagnostics showed that RSI/ATR had a positive raw edge and
  justified implementing maker execution rather than assuming every limit
  filled.
- The engine now resolves maker orders against 1m candles. It requires strict
  trade-through, expires entries after five minutes, charges the configured
  Binance Futures fees of 1.8 bps maker and 4.5 bps taker, uses maker targets,
  and keeps stops at the taker fee plus 2 bps modeled slippage.
  Strategy exits try maker and use a timed taker fallback. Fill and expiry
  counts are reported.
- A configurable break-even stop activates after a favorable R multiple and
  solves its stop price from the actual entry fee plus expected taker fee and
  slippage. Ambiguous trigger-and-reversal ordering within a 1m candle is
  resolved conservatively. BTC RSI/ATR comparisons rejected enabling it:
  disabled returned +1.253%, versus +0.254% at 1.5R, -1.973% at 1.0R, and
  -2.737% at 0.75R. The feature remains available but is disabled by default.
- BTC RSI/ATR now returns +1.05% with 32/35 maker entries filled, 3.25% maximum
  drawdown, and 1.125 profit factor. It still fails acceptance because it has
  only 32 trades, one profitable subperiod, concentrated profit, and negative
  stressed-cost PnL.
- Acceptance has separate frequency profiles. The high-frequency profile
  requires at least 1,460 filled trades over two training years (approximately
  two per day). The default low-frequency profile requires at least 60 trades
  for strategies expected to trade less than daily. Both retain the same
  profitability, drawdown, subperiod-stability, win/loss, and cost-stress gates.
- A new intraday flat-market mean-reversion baseline uses RSI 7, EMA 20 slope
  and deviation, ATR expansion filtering, ATR protection, short holding time,
  and real maker execution. It was rejected on both symbols. BTC reached
  maximum drawdown at -9.73% after 48 trades; ETH reached it at -10.22% after
  51. The four separately restarted subperiods totaled only 277 BTC and 300 ETH
  trades, around 0.4 per day, and raw PnL was negative before costs.
- With real maker fills and conservative protective-order sequencing, ETH
  compression is -3,325.16 net. ETH RSI/ATR has +770.56 zero-cost PnL and
  -1,069.35 net. Both fail acceptance.
- Directional behavior differs by symbol: BTC RSI/ATR longs were profitable
  and shorts were poor; ETH RSI/ATR shorts earned 1,317.49 and longs lost
  2,386.83. This argues against hard-coding one directional bias
  across symbols.
- Return-improvement experiments 1–5 are complete. Their complete comparable
  results and conclusions are consolidated below rather than split between this
  status file and the roadmap.
- Validation and final-test periods have not been run.
- The frozen cross-sectional 1h-strength/15m-pullback v1 was rejected on the complete eight-symbol
  training universe: 957 trades, -32,337.46 net pooled PnL, PF 0.835, and negative raw PnL.
  Its two-position portfolio replay returned -16.60%; validation and final-test periods remain closed.
- Historical volume-at-price infrastructure is complete for BTCUSDT training data.
  Flyway V6 stores actual aggregate-trade notional in 15-minute × $10 price bins,
  including aggressive buy/sell quote volume. All 30 existing archives covering
  `[2023-08-07, 2025-08-07)` were imported idempotently into 1,728,422 rows.
- Rolling volume profiles calculate 24-hour, 72-hour, and 168-hour POC and merged
  high-volume zones, zone share, aggressor delta, and exact-bin POC stability.
  The profile at a candle timestamp uses completed earlier buckets only; deterministic
  tests prove that volume from the current bucket cannot affect its own signal.
- The first end-to-end preview at 2025-07-31 23:45 UTC found different prior-only
  POCs by horizon: $118,370 (24h), $117,500 (72h), and $118,000 (168h). Lookback,
  price step, and adjacent-bin threshold remain training hypotheses, not optimized
  or validated parameters.
- Volume-profile strategy steps 5–7 are complete for three distinct reactions. The
  frozen 72h BTCUSDT training runs rejected breakout (-13.61%, PF 0.802, 849 trades),
  false breakout (-5.45%, PF 0.483, 81 trades), and channel (-63.28%, PF 0.386,
  788 trades). All maintained greater than 2.2 average win/loss ratios, confirming
  that structural stops/targets and the 3R filter work mechanically; poor signal
  selection and execution costs cause the losses. Validation/final data stayed closed.
- The complete 44-page Apollo Crypto `методичка 2,0.pdf` was visually and textually
  reviewed. `apollo-base-poc-retest` translates its base, trend-break, fixed-volume
  POC, first-retest, breakout-volume, structural-stop, and minimum-3R principles into
  explicit rules. Its frozen BTC training result was -0.61% with PF 0.957, 186 trades,
  and 2.97% drawdown. Raw price PnL was +1,856.50; longs were +1,078.49 net while
  shorts lost -1,688.45. The candidate fails acceptance but is a credible follow-up
  for exact-base POC and predeclared higher-timeframe/long-only experiments.
- A second source-grounded review is recorded in `APOLLO_COURSE_SOURCE_NOTES.md`. It clarifies
  the distinction between global, local, and early (`крючок`) breaks; defines liquidity as a base
  / unfilled-order concentration rather than simply high volume; and documents the missing
  hierarchy, acceptance, consumed-liquidity, and opposing-level-room rules in the prior proxy.
- Apollo experiment 2 replaced the 72h proxy with an exact profile over the preceding
  fixed 16-candle base while holding other rules constant. It was decisively worse:
  -21.17%, PF 0.641, 619 trades, 21.92% drawdown, and -3,003.44 raw price PnL.
  Every subperiod lost. This rejects the fixed-window base detector: exact volume over
  an incorrectly identified base creates excessive narrow-zone signals. Variable-length
  base semantics must be solved before direction or higher-timeframe sensitivities.
- A three-level long-range strategy now models L2 entry, partial-channel target, L1
  structural stop, and a persistent fee-adjusted maker scratch activated 20% into
  the L2-L1 channel. The execution model does not allow a same-minute fictional fill.
  The 15m version produced 274 trades and -6.16% (PF 0.531). The 5m frequency version
  reached 696 trades, approximately 0.95/day, but lost -33.39% (PF 0.243). Waiting
  for an adverse sweep and L2 reclaim still lost -31.24% across 533 trades. Every
  month and subperiod lost in both 5m runs, with negative raw PnL, so this family is
  rejected rather than threshold-tuned.

## Current database

- PostgreSQL: 17.10
- Service: `postgresql@17`
- Database: `prop_strategy`
- Schema version: Flyway V6
- BTCUSDT training volume-profile bins (15m × $10): 1,728,422 rows
- BTCUSDC Futures contract metadata onboarded at 2024-01-03 12:30 UTC; its first
  actual 1m candle is 2024-01-04 12:31 UTC.
- BTCUSDC 1m: 1,362,009 rows
- BTCUSDC 5m: 272,402 rows
- BTCUSDC 15m: 90,800 rows
- BTCUSDC 1h: 22,700 rows
- BTCUSDC funding rates: 2,838 rows

- BTCUSDT 1m: 1,578,282 rows
- BTCUSDT 5m: 315,656 rows
- BTCUSDT 15m: 105,218 rows
- BTCUSDT 1h: 26,304 rows
- ETHUSDT 1m: 1,579,518 rows
- ETHUSDT 5m: 315,903 rows
- ETHUSDT 15m: 105,301 rows
- ETHUSDT 1h: 26,325 rows
- Total ETHUSDT Futures klines: 2,027,047 rows
- BTCUSDT funding rates: 3,288 rows
- ETHUSDT funding rates: 3,291 rows
- Funding-rate window: 2023-08-07 through 2026-08-06
- Historical funding rows without a Binance mark price: 256 (stored as NULL)
- BTCUSDT 5m open-interest statistics: 8,640 rows
- BTCUSDT 5m global account ratios: 8,640 rows
- BTCUSDT 5m top-trader account ratios: 8,640 rows
- BTCUSDT 5m top-trader position ratios: 8,640 rows
- ETHUSDT 5m open-interest statistics: 8,640 rows
- ETHUSDT 5m global account ratios: 8,640 rows
- ETHUSDT 5m top-trader account ratios: 8,640 rows
- ETHUSDT 5m top-trader position ratios: 8,640 rows
- Supporting-statistics window: 2026-07-07 18:35 UTC through 2026-08-06
  18:30 UTC
- `futures_kline` table and indexes: approximately 386 MB after import
- Import window: 2023-08-06 through the last closed candle on 2026-08-06
- Full import runtime: 20 minutes 56 seconds

Local credentials belong to the dedicated `prop_strategy_app` role and are
stored in the ignored `.env` file. The application reads `DB_URL`, `DB_USER`,
and `DB_PASSWORD` from its process environment. Binance credentials are stored
separately in ignored `.env.binance`; Phase 3 only sends the API key to the two
read-only market-data endpoints that require it and never uses the secret key.

### Direct-Binance BTCUSDC high-frequency research

BTCUSDC is treated separately from prop-account research. The reproducible
training window is `[2024-02-01, 2026-02-01)`; validation is reserved through
2026-05-01 and final test through 2026-08-01. The observed regular-account fee
profile is configurable at 0 bps maker and 3.6 bps taker, with 2 bps modeled
taker slippage and strict 1m maker trade-through. Direct-Binance research uses
0.1% risk per trade and does not terminate at prop-account loss thresholds;
the acceptance profile still rejects drawdown above 10%.

The existing intraday flat mean-reversion rules were run unchanged at three
timeframes over the complete training period:

- 15m: 2,869 trades, -30.10%, 0.778 profit factor, 30.97% drawdown.
- 5m: 13,919 trades, -97.92%, 0.607 profit factor, 97.93% drawdown.
- 1m: 43,507 trades, -100%, 0.368 profit factor, 100% drawdown.

A new `passive-maker-mean-reversion` candidate was frozen before its first run.
On 1m data it uses EMA-60, RSI-7, a 15-bar flatness guard, 0.15 ATR minimum
deviation, 2 ATR protective stop, 0.75 ATR maker target, and 30-minute maximum
holding period. It completed 79,288 trades (about 109 per day), with 62.76% win
rate, but lost 100% with a 0.335 profit factor. The average win was $1.01 and
average loss $5.10 as equity decayed; fees were $39,387 and modeled slippage
$21,882. Each independently restarted six-month subperiod lost effectively all
capital, and the 1.5x-cost run also lost 100%.

Conclusion: frequency is achievable, but neither tested rule family has
positive expectancy. Zero maker fees do not compensate for adverse selection,
taker stop/fallback costs, and a payoff distribution where occasional losses
erase several maker wins. Both branches are rejected without tuning, and the
reserved validation/final periods remain unopened.

BTCUSDC aggregate-trade follow-up:

- All 24 monthly training archives were downloaded to ignored
  `data/order-flow/BTCUSDC`; checksums passed and retained ZIPs use 2.4 GB.
- 191,841,827 aggregate-trade rows produced 1,052,024 stored minute rows out of
  1,052,640 calendar minutes. There are zero duplicate or missing aggregate IDs.
- Reconciliation is exact for 1,040,073 minutes and mismatched for 11,951. The
  total base-volume difference versus klines is -23.919 BTC across 14,222,094
  BTC of aggregate volume.
- Of 616 calendar minutes without aggregate rows, 615 have zero kline volume
  and trades. One active kline minute at 2024-02-04 10:11 UTC lacks an aggregate
  row and remains an explicit source anomaly.
- The backtest can now select aggregate-trade execution minutes. These use
  actual first/last/minimum/maximum prices and last-event timestamps; they do
  not claim to reproduce maker queue position.
- The frozen passive-maker rerun still filled 79,288 entries and lost 100%.
  Profit factor changed only from 0.335393 with kline trade-through to 0.335248
  with aggregate trades; wins changed from 49,765 to 49,763. Aggregate trades
  improve timestamps and enable flow features, but do not rescue this rule.

### Structural channel experiment

The frozen `structural-channel` strategy implements stops as actual market
invalidation levels rather than arbitrary distances. It forms a channel from
the prior 96 completed 15m bars, requires at least two touches at both boundaries
within 0.25 ATR, and allows entry only within 0.35 ATR of support or resistance.
The stop is fixed 0.25 ATR beyond that saved boundary. The target is the opposite
boundary inset by 0.25 ATR; a trade is rejected unless channel width is at least
6x risk and expected reward is at least 3x risk.

The engine now supports absolute structural stop/target prices. A signal remains
pending until the next bar's strict maker trade-through; the entry is discarded
if its actual fill is no longer between the saved stop and target. This preserves
the original support/resistance invalidation level across execution.

Full training results with 0.1% risk per trade:

| Symbol | Trades | Win rate | Profit factor | Return | Max drawdown |
|---|---:|---:|---:|---:|---:|
| BTCUSDC | 415 | 6.75% | 0.799 | -10.28% | 18.73% |
| ADAUSDT | 598 | 7.19% | 0.794 | -15.09% | 21.49% |
| DOGEUSDT | 392 | 6.38% | 0.785 | -10.29% | 16.84% |
| XRPUSDT | 303 | 6.60% | 0.711 | -11.60% | 13.83% |
| SOLUSDT | 508 | 6.50% | 0.707 | -17.71% | 21.20% |
| LINKUSDT | 678 | 5.90% | 0.692 | -24.35% | 25.57% |
| ETHUSDT | 298 | 7.05% | 0.673 | -12.41% | 14.39% |
| BTCUSDT | 312 | 6.73% | 0.640 | -15.67% | 17.47% |
| BNBUSDT | 470 | 5.53% | 0.499 | -32.94% | 36.56% |

The requested asymmetric payoff was achieved: average winning trades were about
8.5-11.5 times average losing trades, comfortably above 3:1. Nevertheless, only
5.5-7.2% of entries won, below the net break-even hit rate. All nine symbols
failed profitability and drawdown acceptance.

Conclusion: channel width and reward/risk are necessary but not sufficient. A
rolling high/low with two nearby touches often describes a trend leg or pause,
not a stable auction between defended levels. Do not tune v1 thresholds. A new
hypothesis should construct levels from clustered pivots, demand alternating
time-separated tests, reject sloped/drifting channels, and enter only after a
boundary rejection or false-break reclaim. BTCUSDC aggregate-flow confirmation
can then be tested as an additional predeclared filter.

## Verification commands

``` shell
brew services list

set -a
source .env
set +a

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn test

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.database.KlinePersistenceApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcHistoricalImportApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.EthHistoricalImportApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcIncrementalSyncApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.EthIncrementalSyncApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcFundingRateImportApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.EthFundingRateImportApplication

set -a
source .env.binance
set +a

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.marketdownloader.BtcSupportingMarketDataImportApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.feature.BtcFeaturePreviewApplication

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DengineConfig=config/backtests/engine.properties \
  -DstrategyConfig=config/backtests/ema-pullback.properties \
  -DbacktestDataset=TRAINING

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DstrategyConfig=config/backtests/donchian-breakout.properties \
  -DbacktestDataset=TRAINING

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DstrategyConfig=config/backtests/volatility-compression-breakout.properties \
  -DbacktestDataset=TRAINING

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DstrategyConfig=config/backtests/rsi-atr-mean-reversion.properties \
  -DbacktestDataset=TRAINING \
  -Ddiagnostics=true

JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home \
PATH=/opt/homebrew/opt/openjdk@25/bin:$PATH \
mvn exec:java \
  -Dexec.mainClass=com.smalistean.propstrategy.statistics.BacktestApplication \
  -DstrategyConfig=config/backtests/intraday-flat-mean-reversion.properties \
  -DbacktestDataset=TRAINING \
  -Ddiagnostics=true

PGPASSWORD=$DB_PASSWORD /opt/homebrew/opt/postgresql@17/bin/psql \
  -h localhost -U "$DB_USER" -d "$DB_NAME" \
  -c "SELECT symbol, interval, COUNT(*) FROM futures_kline GROUP BY symbol, interval;"
```

## Return-improvement experiment results

All results below use the two-year training window. Validation and final-test
periods remain closed.

### Experiment 1 — BTC long-only RSI/ATR

| Candidate | Return | Profit factor | Max drawdown | Trades |
| --- | ---: | ---: | ---: | ---: |
| Corrected-fee two-sided baseline | +1.25% | — | 3.19% | — |
| Long-only | +5.59% | 2.68 | 1.00% | 18 |

All four six-month periods were profitable and 1.5x cost stress returned
+4.99%, but one period supplied 72.17% of positive subperiod profit. The sample
failed the 60-trade evidence floor. Removing shorts improved the candidate, but
did not establish anything close to 5% monthly returns.

### Experiment 2 — market regimes

The classifier uses only the completed 24-hour move: above +2% is bull, below
-2% is bear, and the remainder is flat.

| Component | Return | Profit factor | Max drawdown | Trades |
| --- | ---: | ---: | ---: | ---: |
| Flat-regime longs | +6.77% | 4.05 | 1.00% | 16 |
| Flat-regime shorts | -4.02% | — | — | — |
| Bull-regime longs | -1.10% | — | — | — |
| Bear-regime shorts | -0.09% | — | — | — |
| Combined router | +1.25% | — | — | — |

Flat-regime longs were the only strong component, but still failed the trade
count and subperiod-concentration gates. The combined router reproduced the
two-sided baseline because EMA-200 already imposed a similar direction filter.

### Experiment 3 — multi-timeframe execution

| Candidate | Return | Profit factor | Max drawdown | Trades | 1.5x cost stress |
| --- | ---: | ---: | ---: | ---: | ---: |
| 15m flat-long baseline | +6.77% | 4.05 | 1.00% | 16 | — |
| 1h/15m/5m/1m multi-timeframe | +6.31% | 6.51 | 0.82% | 14 | +5.86% |

The multi-timeframe candidate improved quality and consistency but reduced the
already-small sample. All four subperiods were profitable and the largest
positive-subperiod contribution fell to 51.71%.

### Experiment 4 — more liquid symbols

Each new symbol has 1,579,642 x 1m, 315,928 x 5m, 105,309 x 15m, and 26,327 x
1h candles. It also has 3,292 funding events and 8,640 rows for each supporting
5m statistic.

| Symbol | Frozen 15m return | Trades | Multi-timeframe return | Trades |
| --- | ---: | ---: | ---: | ---: |
| SOLUSDT | -1.43% | 16 | -0.17% | 13 |
| XRPUSDT | +2.00% | 6 | +1.49% | 5 |
| BNBUSDT | -2.85% | 14 | -0.19% | 10 |
| ADAUSDT | +0.39% | 10 | +0.97% | 5 |
| DOGEUSDT | +1.83% | 12 | +1.25% | 10 |
| LINKUSDT | +1.02% | 9 | -0.22% | 7 |

The independent-run sums were approximately +0.97% from 67 trades for the 15m
candidate and +3.13% from 50 trades for multi-timeframe. These are not portfolio
returns: shared capital, overlapping positions, and correlation were not modeled.
No individual symbol passed acceptance.

### Experiment 5 — improved exits

Completed on the frozen BTCUSDT 15m flat-long training candidate:

| Exit policy | Return | Profit factor | Max drawdown | Reported exit legs |
| --- | ---: | ---: | ---: | ---: |
| Baseline | +6.77% | 4.05 | 1.00% | 16 |
| Partial 50% at 1R | +5.00% | 3.27 | 1.00% | 28 |
| Initial-ATR trailing | +1.96% | 1.89 | 1.06% | 16 |
| Eight-bar lack of progress | +5.95% | 3.55 | 1.00% | 16 |
| Flat-regime stop/target | +6.67% | 3.68 | 1.21% | 17 |
| Combined | +2.61% | 2.19 | 1.00% | 28 |

Partial exits are reported as separate exit legs, so their count is not a count
of independently opened positions. No overlay beat the baseline, and no exit
variant was promoted to the other symbols.

### Experiment 6 — portfolio-level shared risk

The experiment replays the frozen 15m flat-long opportunities chronologically
against one account. Position sizes scale to shared balance at entry. Admission
enforces simultaneous-position, total-leverage, and correlated-crypto-notional
caps. PnL retains each source trade's modeled maker/taker fees, slippage, and
funding. Reported drawdown is realized-equity drawdown; a future event-driven
portfolio engine must add simultaneous intratrade mark-to-market drawdown.

| Universe and limits | Return | Realized DD | Accepted | Rejected | Max concurrent |
| --- | ---: | ---: | ---: | ---: | ---: |
| All 7; 3 positions, 3x leverage, 1.5x crypto notional | +6.75% | 3.19% | 71 | 12 | 2 |
| All 7; 2 positions, 2x leverage, 1.0x crypto notional | +1.78% | 2.28% | 57 | 26 | 1 |
| Training-positive 5; 3 positions, 3x leverage, 1.5x crypto notional | +9.20% | 1.55% | 45 | 8 | 2 |
| Training-positive 5; 2 positions, 2x leverage, 1.0x crypto notional | +4.05% | 1.55% | 37 | 16 | 1 |

The all-symbol result is effectively no better than BTC alone (+6.77%); SOL and
BNB dilute the portfolio. The BTC/XRP/ADA/DOGE/LINK subset is stronger, but was
selected from the same training results and is therefore in-sample research,
not validation. Strict 1.0x correlated exposure rejects too many opportunities
and materially reduces return. No portfolio passes the project's return target
or evidence requirements.

## Global research conclusion

Experiments 1–6 found a small, low-frequency flat-regime long mean-reversion
effect. BTC is the strongest single candidate; multi-timeframe confirmation
improves profit factor but reduces frequency; the effect transfers weakly to a
few altcoins; and more elaborate exits do not help. Shared-capital replay raises
the best in-sample return to +9.20% over two years, roughly 4.5% annualized before
considering unrealized portfolio drawdown. This is nowhere near the aspirational
60% annual return and no candidate meets the minimum trade-count acceptance
criterion. Increasing leverage would magnify an insufficiently established edge
rather than solve the evidence problem.

## Next step

The user selected order-flow research before validation because the completed
strategies mostly rearrange price-derived indicators. The ordered plan is in
`ROADMAP.md` under **Order-flow research sequence**.

Proceed only one confirmed step at a time. Steps 1 through 7 are complete. The
approved download scope is BTCUSDT training only: `[2023-08-07, 2025-08-07)`.
The first order-flow research branch is closed by its frozen stopping rule. Do
not tune the exhaustion thresholds, inspect validation/final-test performance,
or import additional aggregate-trade symbols for this rejected candidate.

Historical completeness is a project rule for new signals. Do not use the
approximately 30-day open-interest/trader-ratio history in the two-year training
backtest.
Liquidations and order-book streams are deferred because equivalent full-window
USD-M history is not currently established. The portfolio mark-to-market upgrade
and validation plan remain future work after the order-flow candidate is assessed.

### Order-flow Step 1 — BTCUSDT archive audit

Audit date: 2026-08-07. No bulk history was downloaded and PostgreSQL was not
changed.

- Required target window: `[2023-08-06, 2026-08-07)` UTC, 1,097 days and at
  most 1,579,680 one-minute aggregate rows.
- Available monthly archives: all 36 files from 2023-08 through 2026-07.
- Available daily tail: 2026-08-01 through 2026-08-05.
- Missing tail: 2026-08-06 archive and checksum both returned HTTP 404. The
  archive normally appears after the UTC day has been processed, so coverage
  must be checked again before the final import.
- Every available archive has an HTTP 200 checksum companion.
- Available compressed size: 19,904,613,739 bytes (19.90 GB / 18.54 GiB).
- Largest monthly archive: 1,006,713,302 bytes for 2026-02.
- Mean monthly archive: approximately 551.6 MB.
- Verified sample: BTCUSDT 2026-08-01, checksum passed; 5,049,749-byte ZIP,
  26,515,205-byte CSV, and 399,219 data rows plus one header.
- Sample schema: aggregate-trade ID, price, quantity, first/last trade IDs,
  transaction time, and buyer-maker flag. Transaction timestamps are 13-digit
  Unix milliseconds. The first and last sample events fall inside the expected
  UTC day.
- Sample compression ratio: 5.25x. Linear estimates are approximately 104.5 GB
  expanded and 1.57 billion raw aggregate-trade rows. These are planning
  estimates; activity and compression vary by month.
- Measured sample download: 2.44 MB/s. At that rate, 19.90 GB takes about 2.3
  hours. Allow roughly 3–6 hours for resumable download, checksum verification,
  Java CSV parsing, aggregation, and database insertion.
- Disk availability at audit time: 721 GiB. Retaining compressed archives needs
  about 20 GB; streaming ZIP entries avoids the extra estimated 104.5 GB.
- Expected PostgreSQL feature footprint: approximately 0.3–0.7 GB for 1.58
  million rows, depending on the Step 2 column types and indexes.

Step 1 identified a one-day publication lag at the original three-year endpoint.
That gap is outside the subsequently approved training-only scope and does not
block the `[2023-08-07, 2025-08-07)` import.

### Order-flow Step 3 — importer implementation

- Flyway V5 is applied locally and verified. It creates
  `futures_agg_trade_minute` and `futures_agg_trade_import`.
- Downloads use `.part` files and HTTP Range requests for resume, validate the
  official checksum before rename, and reuse already verified archives.
- ZIP entries are streamed directly into deterministic minute aggregation.
- Persistence uses 1,000-row transactional upsert batches and is idempotent.
- The archive manifest records checksum, byte size, source/minute row counts,
  status, and completion time.
- Minute rows are reconciled against existing BTCUSDT 1m kline volume with an
  explicit difference and `MATCHED`, `MISMATCH`, or `KLINE_MISSING` status.
- A synthetic unit test verifies aggressor direction, quote delta, size buckets,
  underlying trade counts, and missing-ID detection.
- The real checksum-aware dry run read 399,219 aggregate trades from the verified
  2026-08-01 sample and generated all 1,440 expected UTC minutes, with zero
  duplicates and zero missing IDs. `orderFlowPersist` remained false.
- No aggregate-trade rows have been inserted yet. Bulk training download remains
  Step 4.

### Order-flow Step 4 — BTCUSDT training import

- Exact window: `[2023-08-07, 2025-08-07)` UTC.
- Archives: 24 monthly files plus six daily tail files; all 30 checksums passed.
- Compressed bytes retained under ignored `data/order-flow/BTCUSDT`: 12,712,704,057
  bytes (about 12 GB on disk). No `.part` file remains.
- Archive source rows: 996,290,953.
- Rows outside the training boundary: 4,063,849.
- In-range aggregate trades: 992,227,104.
- Represented underlying trades: 2,541,354,833.
- Stored minute rows: 1,052,606 of a 1,052,640 calendar maximum.
- The 34 absent minutes are two exchange inactivity windows (19 minutes on
  2023-09-12 and 15 minutes on 2024-10-28); every corresponding kline has zero
  volume and zero trades.
- Aggregate-trade ID gaps: zero. Duplicate aggregate IDs: zero.
- First/last stored minutes: 2023-08-07 00:00 UTC and 2025-08-06 23:59 UTC.
- PostgreSQL size: 467 MB for minute data and 64 kB for the manifest.
- Elapsed completion span: approximately 45 minutes, including diagnosis,
  cleanup, and corrected replay of the unordered October 2023 archive.

The October 2023 official CSV interleaves two distant time ranges. The initial
reader assumed global chronology, producing fragmented partial minutes. The run
was stopped, exactly 44,640 invalid October rows and its manifest entry were
deleted transactionally, the reader was changed to order-independent minute
aggregation, and the same checksum-verified archive then produced the correct
44,640 rows with zero gaps. A regression test now covers interleaved input.

Kline reconciliation:

- `MATCHED`: 874,678 minutes (83.1%).
- `MISMATCH`: 177,928 minutes (16.9%).
- Median absolute mismatch among mismatches: 0.038 BTC; 90th percentile 0.926
  BTC; 99th percentile 4.968 BTC; 462 minutes exceed 10 BTC.
- Aggregate-trade total base volume is 185,382,349.052 BTC versus
  185,380,958.755 BTC in klines, a net difference of 1,390.297 BTC or about
  0.00075% of total volume.

Many differences cancel in adjacent minutes, indicating timestamp-boundary or
historical revision drift between independently obtained Binance archives and
kline API data. A few exchange-data anomalies are materially larger. The import
keeps every difference and quality status visible; it does not rewrite order
flow to match klines. Aggregate trades are the source of truth for Step 5 flow
features, while reconciliation status remains available for sensitivity checks.

### Order-flow Step 5 — no-look-ahead features

Implemented deterministic rolling features for 5m, 15m, 60m, and 240m windows:
order-flow imbalance, rolling quote delta, >=100k trade imbalance, data coverage,
exact-reconciliation quality, price returns, 5m-vs-60m delta acceleration, sell
absorption, sell exhaustion, and price/flow divergence. Exact formulas are
frozen in `ORDER_FLOW_DESIGN.md`.

Every snapshot is timestamped at the source minute close and can first execute
one millisecond later. A unit test changes a future minute by an extreme amount
and proves the earlier snapshot is unchanged.

A read-only real-data preview used 8,881 klines/order-flow minutes including
warm-up and produced all 8,640 snapshots for 2025-08-01 through 2025-08-06. The
final snapshot had full 240m coverage and 95.83% exact-reconciliation quality.
No strategy rules, thresholds, or returns were evaluated in Step 5.

### Order-flow Step 6 — frozen strategy and acceptance

Added the long-only `order-flow-exhaustion` strategy and registered it in the
strategy factory. It evaluates completed 5m bars and requires a 15m aggressive
sell shock with >=100k seller participation, price absorption, sell exhaustion,
5m flow recovery versus 60m, positive price/flow divergence, nearly complete
coverage, and acceptable reconciliation quality. A broad EMA-200 guard avoids
entries more than 2% below trend.

Risk is fixed at a 1.25 ATR stop and 2R target. Signal exits occur after +10%
5m buy imbalance, renewed -15% sell imbalance, or one hour. Entry signals are
spaced by at least six 5m bars.

The primary quality floor is 75%. One predeclared sensitivity configuration
changes only that floor to 95%; it cannot rescue a failed primary strategy.

The new acceptance profile requires >=8,000 net profit, >=1.20 profit factor,
<=5% drawdown, >=120 trades, three profitable subperiods, <=50% concentration,
>=1.20 average win/loss, and positive net profit under 1.5x costs. Failure on
profit, evidence count, stability, or cost stress stops this research branch;
threshold searching after the result is prohibited.

Strategy tests verify a complete entry, rejection by the quality sensitivity,
and the recovered-buy-flow exit. At the end of Step 6, no performance data had
been inspected; the frozen runs are recorded below.

### Order-flow Step 7 — BTC training diagnostics

The backtest application now assembles completed 5m EMA/ATR features with
database-calculated 5m/15m/60m/240m order-flow features. Feature availability is
the later of the technical and flow snapshots, so a decision cannot execute
before the final source minute closes. Existing 1m strict maker trade-through,
funding, fees, slippage, prop limits, subperiod evaluation, and 1.5x cost stress
were retained. The full JDK 25 test suite passes: 62 tests.

Primary frozen configuration (`minimumQuality=0.75`):

- 48 completed trades; 14.58% win rate; 7 wins and 41 losses.
- Net -$10,285.18 (-10.29%); profit factor 0.072; maximum drawdown 10.29%.
- Raw price PnL before costs -$1,849.00; fees $6,953.69; slippage $1,482.48.
- 48 of 100 maker entries filled; 52 expired. The account hit `MAX_DRAWDOWN`.
- Every one of the four independently replayed six-month subperiods lost roughly
  $9,609 to $10,285 and zero met the stability requirement.
- The 1.5x-cost run lost $10,322.33. Every acceptance criterion failed.

Predeclared sensitivity (`minimumQuality=0.95`, all other rules unchanged):

- 80 completed trades; 32.50% win rate; 26 wins and 54 losses.
- Net -$10,358.43 (-10.36%); profit factor 0.287; maximum drawdown 10.18%.
- Raw price PnL before costs +$1,519.25, but fees of $10,338.94 and slippage of
  $1,538.74 produced $11,877.68 in execution costs.
- 80 of 115 maker entries filled; 35 expired. The account hit `MAX_DRAWDOWN`.
- All four independent six-month subperiods lost money; the 1.5x-cost run lost
  $10,299.71. Every acceptance criterion failed.

Conclusion: the primary signal has no raw edge. Stricter data-quality filtering
reveals only a very small gross move whose scale is incompatible with the chosen
short holding period and real Binance futures costs. Because neither run is
stable and both breach the loss limit, the frozen stopping rule rejects the
candidate. No validation or final-test data was opened, and no additional symbol
archive is justified for this hypothesis.

The first experiment-4 import universe is SOLUSDT, XRPUSDT, BNBUSDT, ADAUSDT,
DOGEUSDT, and LINKUSDT. LTCUSDT, AVAXUSDT, BCHUSDT, TRXUSDT, AAVEUSDT,
DOTUSDT, and ETCUSDT are recorded as deferred candidates.
