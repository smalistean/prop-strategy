# Evidence-based research plan

Last updated: 2026-08-09

## Apollo expanded-universe result — rejected

The frozen B5 and C1 variants were evaluated on seven newly imported, previously
unused symbols. B5 lost $5,504.70 across 53 trades and C1 lost $2,502.55 across
38. The original eight-symbol performance does not generalize; stop threshold
search and validation for this proxy family.

## Apollo v3 assumption-test protocol

The user authorized exploratory one-variable tests to increase the Apollo v3
sample. Each run must keep the eight-symbol training universe, period,
execution model, and every non-tested property fixed; it must be recorded in
`APOLLO_V3_ASSUMPTION_TESTS.md`. B1 changed only the mapped-area proxy from
four to three pivot touches: 10 to 12 filled trades and -$2,296.85 to
$1,281.92 aggregate independent-account net PnL. It is too small to select a
configuration. Run no validation/final test and make no compounded parameter
change until a separately labelled next one-variable comparison is reviewed.

B2 then shortened the untouched-level requirement from 12 hours to four hours
while retaining B1. It raised filled trades to 35 but returned -$9,009.06,
therefore it is rejected. Subsequent one-variable comparisons retain 12-hour
freshness and the B1 three-touch map unless explicitly stated otherwise.

B3 widened only the mapped-area tolerance from 0.50 to 0.75 ATR while retaining
B1: 16 filled trades and +$756.13. The extra four observations reduced aggregate
profit and three lost, so it is inconclusive and the 0.50-ATR map tolerance
remains the reference for the next isolated comparison.

B4 extended only the reclaim deadline from six to eight 15-minute bars while
retaining B1. It produced 14 filled trades and +$234.96; both incremental
trades stopped out. It is rejected, and the six-bar reclaim deadline remains
the reference for the next isolated comparison.

B5 changed only the acceptance-count proxy from two to one full-bodied
reclaim candle while retaining the 0.20-ATR body threshold and B1's other
settings. It produced 52 filled trades and +$1,646.98. This is a lead rather
than a selection: it is below the 60-trade low-frequency evidence floor, has
negative BTC/SOL results, and remains entirely in training. Keep B5 isolated;
do not compound parameters or open validation before reviewing the next
one-variable comparison.

B6 initially revealed a configuration coupling: local-break length also
changed sweep-search length. This was corrected by making `sweepSearchBars=10`
explicit. The corrected two-bar-break-only comparison returned 18 filled trades
and +$137.78, so it is rejected and the three-bar break remains the reference.

B7 changed only the mapped-target room from 3R to 2.5R. It increased filled
trades to 27 but returned -$1,063.12, so it is rejected and the 3R target-room
requirement remains the reference.

B8 raised only break-volume confirmation to 1.20× the 20-bar average. It
returned +$4,739.19 but only six filled trades, concentrated in ADA and XRP.
It is an insufficient-sample quality lead, not a selected threshold or a basis
to open validation.

B9 raised only sweep depth from 0.10 to 0.20 ATR. It returned nine filled
trades and +$469.10, below B1, so it is rejected and the 0.10-ATR sweep depth
remains the reference.

B10 reduced only 4h pivot confirmation from two neighboring bars to one. It
produced three trades and -$1,836.74 because denser pivots changed map
selection; it is rejected.

## Objective

Find a reproducible crypto Futures portfolio capable of at least 15-20% annualized net return
after realistic fees and slippage, without relying on unacceptable leverage or hidden drawdown.
The target is a portfolio outcome; a single BTC strategy does not need to trade every day or
produce the complete return by itself.

## What the last 24 hours established

The work was not wasted. It produced reusable infrastructure and eliminated several attractive
but false assumptions:

- Historical aggregate trades now support strict maker trade-through, order-flow features, and
  causal volume-at-price profiles.
- BTCUSDT training contains 1,728,422 persisted 15-minute x $10 price bins.
- Structural stops, absolute targets, adverse-excursion scratch orders, partial exits, trailing,
  break-even logic, funding, fees, slippage, and portfolio replay are modeled.
- A large reward/risk ratio does not create an edge. Structural channel trades achieved roughly
  8.5-11.5 average win/loss but lost because only 5.5-7.2% won.
- Zero maker fees and frequent fills do not rescue negative raw expectancy.
- A maker scratch can eliminate many small losses, but occasional L1 stops still dominate when
  level reactions lack predictive power.
- Simple POC bounce, breakout, false-breakout, channel, and order-flow exhaustion rules are not
  sufficient as standalone strategies.
- Exact volume scope is useful only if the base itself is identified correctly. A fixed 16-candle
  “base” admitted ordinary trend pauses and destroyed the Apollo candidate.
- More elaborate exit management did not improve the best underlying entry edge.

## Ranked candidates

### 1. Multi-timeframe BTC flat-regime long mean reversion - highest quality, insufficient sample

- +6.31% over two training years at 0.5% risk per trade.
- Profit factor 6.51; maximum drawdown 0.82%; +5.86% under 1.5x costs.
- All four six-month periods profitable; largest subperiod contribution 51.71%.
- Only 14 trades, so the apparent edge and profit factor are not yet reliable.

This is the best risk-adjusted evidence. It should be expanded for sample size, not tuned for return.

### 2. Apollo rolling-profile base/POC retest - best new research lead

- 186 trades; -0.61% net; PF 0.957; 2.97% drawdown.
- Positive raw price PnL of +1,856.50.
- Longs earned +1,078.49 net; shorts lost -1,688.45.

It is close to break-even with a meaningful sample and contains a positive long component. The
next version must improve base semantics. The exact fixed-window variant is rejected, not promoted.

### 3. Multi-symbol portfolio - useful diversification evidence, currently in-sample

- The training-positive BTC/XRP/ADA/DOGE/LINK subset returned +9.20% with 1.55% realized drawdown.
- Selection used the same training results, and unrealized simultaneous drawdown is not modeled.

This supports portfolio research but is not evidence that the selected subset will persist.

### 4. Volume-profile breakout - diagnostic value only

- 849 trades; -13.61%; PF 0.802; average win/loss 2.23.

It supplies enough examples for studying acceptance versus failed crossings. It should be used to
develop context features, not optimized as a standalone strategy.

## Closed branches

Do not spend more time tuning these versions:

- passive high-frequency maker mean reversion;
- structural and volume-profile channel reactions;
- simple volume-profile false breakout;
- three-level L2 entry with L1 stop, including scratch and reclaim variants;
- current order-flow exhaustion rule;
- initial Donchian and volatility-compression breakouts;
- partial/trailing/combined exit overlays on the flat-long candidate.

A closed branch may be reopened only with a materially new source of edge, not different thresholds.

## Execution plan

### Stage 1 - strengthen the best existing edge

1. Freeze the current multi-timeframe flat-long rules and configuration.
2. Extend BTCUSDT history backward, preferably to the earliest reliable Futures data, without
   opening the reserved validation or final-test periods.
3. Apply the same frozen rule to the complete imported liquid universe; do not select symbols by
   their result during the run.
4. Produce pooled and per-symbol diagnostics, including trade overlap and correlation.
5. Stop if the pooled sample remains below 100 trades or raw expectancy turns negative.

Promotion gate:

- at least 100 trades before validation;
- positive raw and net expectancy;
- PF >= 1.30;
- at least 3/4 profitable chronological segments;
- positive at 1.5x execution costs;
- no single symbol or segment supplies more than 50% of profit.

### Stage 2 - Apollo labelled base/POC research

Both variable-base POC variants and the strict ordered liquidity-sequence proxy are closed: the
former was negative and the latter produced only nine all-symbol trades. Do not create another
threshold variation. Before another performance run:

1. Label source-style examples with 4h base boundaries, base-only fixed profile, POC/internal
   volume wave, freshness, sweep, reclaim, local/global break, and next target.
2. Measure whether a causal detector agrees with the labels before evaluating PnL.
3. Freeze the detector and run all symbols only if it produces sufficient labelled agreement and
   enough candidate setups.

Stop the branch unless the frozen detector has positive raw PnL, PF >= 1.10 before cost-focused
work, at least 100 trades, and two or more profitable six-month segments. Do not open validation
data merely because one training segment looks exceptional.

### Stage 3 - build frequency through cross-sectional opportunities

Do not force BTC to provide two trades daily. Build a portfolio strategy over BTC, ETH, SOL, XRP,
BNB, ADA, DOGE, and LINK:

1. Rank completed 1h momentum and relative strength across all symbols.
2. Use 15m pullbacks for entry into the strongest assets; initially avoid shorting the weakest
   because the accumulated evidence shows directional asymmetry by symbol.
3. Require broad-market and volatility-regime context.
4. Exit when relative-strength rank decays, structural invalidation occurs, or a time limit expires.
5. Cap correlated crypto exposure and simultaneous positions.
6. Target 1-2 portfolio entries per day, not 1-2 entries per symbol.

This is the next genuinely independent hypothesis. It can create frequency from breadth while
retaining selective entries.

### Stage 4 - event-driven portfolio and risk scaling

Before claiming a 15-20% annual strategy:

1. Upgrade portfolio simulation to mark every simultaneous position to market each minute.
2. Measure intraday and total unrealized drawdown, leverage, liquidation distance, and correlated
   notional, not only realized equity.
3. After an edge passes its promotion gate, compare 0.10%, 0.25%, 0.50%, and 0.75% risk per trade.
4. Reject scaling if 1.5x-cost net return is non-positive, maximum drawdown exceeds 10%, or daily
   loss behavior violates the intended account constraints.

Returns should be scaled only after evidence exists. Leverage cannot repair negative expectancy.

### Stage 5 - validation discipline

1. Choose one promoted strategy/portfolio and freeze all rules.
2. Run the reserved validation period once.
3. If validation fails, diagnose and return to training with a new version; do not tune against
   validation repeatedly.
4. Run final test once only after validation passes and implementation is locked.

## Final acceptance target

A candidate is deployable only if it demonstrates:

- 15% annualized net return at the selected risk level;
- aspirational target of 20% annualized, not achieved by uncontrolled leverage;
- maximum drawdown <= 10%;
- PF >= 1.30;
- at least 100-200 completed trades across development and validation;
- positive 1.5x-cost stress result;
- stability across time and symbols;
- no look-ahead, survivorship-based universe selection, or assumed maker fills.

## Immediate next action

Stages 1-3 are closed below. Do not optimize any of their thresholds. The next research proposal
must use a materially independent source of edge; a cost-aware funding/basis hypothesis is the
most natural candidate because it requires different data and does not depend on directional
pullback prediction.

Apollo remains closed as a fully automatic all-symbol candidate: the later 4h-map/15m-trigger v2
produced positive BTC/XRP training results but a negative unselected eight-symbol result. Reopen it
only as a labelled-example / semi-discretionary research workflow, not through more threshold tuning.

## Stage 1 result — closed, not promoted

The configuration was frozen before expansion. The complete eight-symbol 2023-2025 training
run produced 76 trades, +7,447.02 pooled independent-account net PnL, +10,324.04 raw PnL,
PF 1.45, and +5,773.87 under 1.5x execution costs. Its last six-month segment was negative and
BTC supplied 62.96% of positive symbol profit.

BTC and ETH were then extended backward to 2021-08-07 without changing any rule. Their four-year
run produced 40 trades, +4,852.87 net, +6,682.33 raw, and PF 1.57. ETH alone had PF 1.06, three
of eight half-year segments were negative, one had no trades, and BTC supplied 93.55% of the
positive BTC/ETH profit. Combining these 40 unique BTC/ETH trades with the original-window trades
from the other six symbols gives only 90 trades.

Stage 1 therefore fails the predeclared 100-trade, chronological-stability, and concentration
gates. Positive cost stress is encouraging but insufficient. Do not tune or scale this candidate;
retain it only as a low-frequency research signal and proceed to Apollo v3.

## Stage 2 result — closed, not promoted

Apollo v3 now detects causal 12-48 candle horizontal bases and computes aggregate-trade POC only
over the selected timestamps. Both-direction, long-only, and the predeclared long-only plus
completed-1h EMA alignment sensitivity all failed. The aligned version returned -5.38% with PF
0.78, 54 trades, 10.11% drawdown, one profitable half-year, and negative 1.5x-cost stress. Its
small positive raw price PnL was insufficient to cover execution. Do not tune this branch further;
proceed to the independent cross-sectional portfolio hypothesis in Stage 3.

## Stage 3 result — closed, not promoted

The frozen v1 ranked the entire eight-symbol universe by completed 1h 24-hour return, admitted
only the top three assets while BTC was above its completed 1h EMA-50 and its 24-hour move was no
larger than 10%, then entered a 15m EMA-20 pullback/reclaim with RSI 45-65 and at-least-average
volume. It used a 1.5 ATR stop, 2R target, 32-bar maximum hold, real maker entry simulation, and
a two-position portfolio cap with 1.5x correlated-notional limit.

It generated 957 training trades with -32,337.46 pooled net PnL, -507.66 raw price-plus-funding
PnL, PF 0.835, and -33.79 average net PnL per trade. The capped portfolio replay accepted 825
trades and returned -16.60% with 32.28% realized drawdown. Only DOGE (+10,024.61 net) and XRP
(+2,372.07) were positive; that is in-sample selection evidence, not a basis to retain the rule.
The 1.5x-cost independent-account result was -42,384.77. V1 is rejected before costs and must not
be tuned or validated.
