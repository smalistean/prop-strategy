# Three-level range research

## Hypothesis

For three confirmed historical levels `L1 < L2 < L3`, treat `L2-L3` as the active range:

- buy a rejection at L2;
- take profit 30-50% into the L2-L3 channel;
- retain a wider structural stop at L1;
- after price moves 20% into the L2-L1 channel, place a persistent maker sell at
  fee-adjusted break-even;
- if the maker scratch never fills, keep the L1 protective stop active.

Levels are clustered from prior pivot highs and lows only. They require multiple confirmations,
and entry requires a bullish rejection close at L2. Position sizing uses the complete distance
to L1, so a large structural stop does not increase configured account risk.

## Execution implementation

The backtester now supports an adverse-excursion scratch attached to a structural entry.
Aggregate-trade 1-minute ranges activate the scratch after crossing its threshold. The order
cannot fill in the same minute that activated it, remains pending afterward, and requires strict
trade-through of the fee-adjusted maker price. The L1 taker stop remains live throughout.

## Experiment 1 - 15-minute touch with scratch

Frozen configuration used a 192-bar lookback, two pivot confirmations, 0.20 ATR clustering,
minimum 3 ATR L2-L3 width, 40% target, 20% scratch trigger, and maximum 3:1 risk/reward.

- 274 trades in two years: approximately 0.38 per day.
- 105 scratch exits, 67 targets, and 101 full stops.
- Raw target profits +7,686.09 versus raw stop losses -9,782.25.
- Raw price PnL -929.89; net return -6.16%; PF 0.531; drawdown 6.17%.
- Every six-month subperiod lost; 1.5x-cost return was -8.24%.

The scratch mechanism prevented many losses but could not compensate for more full stops than
targets. The resolved target rate was 67 / (67 + 101) = 39.9%.

## Experiment 2 - 5-minute touch with scratch

The frequency version used a one-day 288-bar level window and a target at 30% of the channel.

- 696 trades: approximately 0.95 per day.
- 230 scratch exits, 164 targets, and 302 full stops.
- Raw price PnL -6,488.93; net return -33.39%; PF 0.243; drawdown 33.39%.
- Every active month and every six-month subperiod lost.

This reaches the lower edge of the requested frequency, but demonstrates that frequency without
edge compounds losses. Zero-cost PnL was already negative.

## Experiment 3 - 5-minute adverse reclaim entry

Instead of buying the first touch, this version waited for price to trade 20% below L2 and close
back above L2 before entering. It retained the L1 stop and targeted 40% of the upper channel.

- 533 trades; 187 targets and 346 stops.
- Raw price PnL -5,479.85; net return -31.24%; PF 0.390; drawdown 31.24%.
- Every active month and every six-month subperiod lost.

## Conclusion

The implementation confirms the proposed order behavior but rejects the signal family on BTCUSDT.
Confirmed pivot clusters at L2 do not provide a sufficient probability of movement toward L3.
The wide-stop/smaller-target payoff requires a much higher hit rate than observed, while taker
stops are especially expensive. Threshold optimization is not justified because all periods lose
and raw expectancy is negative.

A credible 1-2-trade-per-day objective should come from multiple liquid symbols or independent
strategies, not by forcing more BTC trades. The next high-frequency hypothesis must establish
positive raw expectancy before maker optimization. Cross-sectional momentum or relative-strength
rotation across the already imported liquid universe is a materially different candidate.
