# Evidence-based research plan

Last updated: 2026-08-07

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

### Stage 2 - Apollo base/POC v3

Implement a variable-length base detector before another performance run:

1. Detect 12-48 candle horizontal candidates.
2. Require most candle bodies to remain inside stable upper/lower boundaries.
3. Reject excessive center-line drift, slope, and repeated boundary penetration.
4. Require a distinct entrance, minimum residence time, and clean volume-supported exit.
5. Calculate aggregate-trade POC over the selected base timestamps only.
6. Trade only the first return; preserve the liquidity-zone-plus-25% stop and 3R minimum.
7. Predeclare two sensitivities: both directions and long-only with 1h structural alignment.

Stop the branch unless v3 has positive raw PnL, PF >= 1.10 before cost-focused work, at least
100 trades, and two or more profitable six-month segments. Do not open validation data merely
because one training segment looks exceptional.

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

Stage 1 is closed below. Proceed to Stage 2 by implementing the predeclared variable-length
Apollo base detector and exact selected-window POC without revisiting Stage-1 thresholds.

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
