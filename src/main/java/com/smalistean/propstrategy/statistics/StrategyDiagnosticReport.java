package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestEngine;
import com.smalistean.propstrategy.backtester.Trade;
import com.smalistean.propstrategy.strategy.Side;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class StrategyDiagnosticReport {

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int REGIME_LOOKBACK_BARS = 96;
    private static final BigDecimal REGIME_MOVE_PERCENT = BigDecimal.valueOf(2);

    public String generate(List<Trade> trades, List<BacktestEngine.BacktestBar> bars,
                           BigDecimal takerFeeBps, BigDecimal makerFeeBps,
                           boolean makerExecutionEnabled) {
        StringBuilder output = new StringBuilder("=== Strategy Diagnostics ===\n");
        Totals totals = totals(trades);
        BigDecimal turnover = trades.stream().map(this::turnover)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal zeroCost = totals.rawPricePnl().add(totals.fundingPnl(), MC);
        BigDecimal breakEvenBps = turnover.signum() == 0 ? BigDecimal.ZERO
                : zeroCost.multiply(BPS, MC).divide(turnover, MC);

        output.append("Price PnL before costs : ").append(number(totals.rawPricePnl())).append('\n')
                .append("Funding PnL            : ").append(number(totals.fundingPnl())).append('\n')
                .append("Fees                    : -").append(number(totals.fees())).append('\n')
                .append("Slippage                : -").append(number(totals.slippage())).append('\n')
                .append("Net PnL                 : ").append(number(totals.netPnl())).append('\n')
                .append("Zero-cost PnL           : ").append(number(zeroCost)).append('\n')
                .append("Break-even all-in bps   : ").append(number(breakEvenBps)).append(" per fill\n");
        if (makerExecutionEnabled) {
            output.append("Execution model         : 1m strict maker trade-through; taker stops/fallback\n");
        } else {
            BigDecimal makerCounterfactual = trades.stream()
                    .map(trade -> makerCounterfactual(trade, takerFeeBps, makerFeeBps))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            output.append("Maker-filled scenario   : ").append(number(makerCounterfactual))
                    .append(" (entry + normal exit maker, protective exit taker)\n");
        }

        appendGroups(output, "By side", group(trades, trade -> trade.side().name()));
        appendGroups(output, "By exit reason", group(trades, Trade::exitReason));
        appendGroups(output, "By entry regime (prior 24h move)", regimeGroups(trades, bars));
        appendMonthly(output, trades);
        appendPathStatistics(output, trades, bars);
        return output.toString();
    }

    private void appendGroups(StringBuilder output, String title,
                              Map<String, List<Trade>> groups) {
        output.append("-- ").append(title).append(" --\n");
        groups.forEach((name, trades) -> {
            Totals totals = totals(trades);
            long wins = trades.stream().filter(trade -> trade.netPnl().signum() > 0).count();
            BigDecimal winRate = trades.isEmpty() ? BigDecimal.ZERO
                    : BigDecimal.valueOf(wins).multiply(HUNDRED, MC)
                    .divide(BigDecimal.valueOf(trades.size()), MC);
            output.append(name).append(" | trades=").append(trades.size())
                    .append(" | winRate=").append(number(winRate)).append("%")
                    .append(" | raw=").append(number(totals.rawPricePnl()))
                    .append(" | costs=").append(number(totals.fees().add(totals.slippage(), MC)))
                    .append(" | net=").append(number(totals.netPnl())).append('\n');
        });
    }

    private static void appendMonthly(StringBuilder output, List<Trade> trades) {
        Map<YearMonth, BigDecimal> monthly = new java.util.TreeMap<>();
        trades.forEach(trade -> monthly.merge(
                YearMonth.from(trade.exitTime().atZone(ZoneOffset.UTC)),
                trade.netPnl(), BigDecimal::add));
        long profitable = monthly.values().stream().filter(value -> value.signum() > 0).count();
        int longestLossStreak = 0;
        int currentLossStreak = 0;
        for (BigDecimal value : monthly.values()) {
            currentLossStreak = value.signum() < 0 ? currentLossStreak + 1 : 0;
            longestLossStreak = Math.max(longestLossStreak, currentLossStreak);
        }
        output.append("-- Calendar consistency --\n")
                .append("Active months=").append(monthly.size())
                .append(" | profitable=").append(profitable)
                .append(" | losing=").append(monthly.size() - profitable)
                .append(" | longest losing-month streak=").append(longestLossStreak).append('\n');
        monthly.forEach((month, pnl) -> output.append(month).append(" | net=")
                .append(number(pnl)).append('\n'));
    }

    private static void appendPathStatistics(StringBuilder output, List<Trade> trades,
                                             List<BacktestEngine.BacktestBar> bars) {
        if (trades.isEmpty()) {
            return;
        }
        BigDecimal totalHours = BigDecimal.ZERO;
        BigDecimal totalMfe = BigDecimal.ZERO;
        BigDecimal totalMae = BigDecimal.ZERO;
        long maxMinutes = 0;
        int consecutiveLosses = 0;
        int maximumConsecutiveLosses = 0;
        for (Trade trade : trades) {
            long minutes = Duration.between(trade.entryTime(), trade.exitTime()).toMinutes();
            totalHours = totalHours.add(BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), MC));
            maxMinutes = Math.max(maxMinutes, minutes);
            BigDecimal favorable = BigDecimal.ZERO;
            BigDecimal adverse = BigDecimal.ZERO;
            for (BacktestEngine.BacktestBar bar : bars) {
                if (bar.candle().openTime().isBefore(trade.entryTime())
                        || !bar.candle().openTime().isBefore(trade.exitTime())) {
                    continue;
                }
                if (trade.side() == Side.LONG) {
                    favorable = favorable.max(bar.candle().high().subtract(trade.entryPrice(), MC));
                    adverse = adverse.max(trade.entryPrice().subtract(bar.candle().low(), MC));
                } else {
                    favorable = favorable.max(trade.entryPrice().subtract(bar.candle().low(), MC));
                    adverse = adverse.max(bar.candle().high().subtract(trade.entryPrice(), MC));
                }
            }
            totalMfe = totalMfe.add(favorable.max(BigDecimal.ZERO)
                    .multiply(HUNDRED, MC).divide(trade.entryPrice(), MC));
            totalMae = totalMae.add(adverse.max(BigDecimal.ZERO)
                    .multiply(HUNDRED, MC).divide(trade.entryPrice(), MC));
            consecutiveLosses = trade.netPnl().signum() < 0 ? consecutiveLosses + 1 : 0;
            maximumConsecutiveLosses = Math.max(maximumConsecutiveLosses, consecutiveLosses);
        }
        BigDecimal count = BigDecimal.valueOf(trades.size());
        output.append("-- Trade path --\n")
                .append("Average holding hours=").append(number(totalHours.divide(count, MC)))
                .append(" | maximum holding hours=")
                .append(number(BigDecimal.valueOf(maxMinutes).divide(BigDecimal.valueOf(60), MC)))
                .append(" | average MFE=").append(number(totalMfe.divide(count, MC))).append("%")
                .append(" | average MAE=").append(number(totalMae.divide(count, MC))).append("%")
                .append(" | max consecutive losses=").append(maximumConsecutiveLosses).append('\n');
    }

    private Map<String, List<Trade>> regimeGroups(List<Trade> trades,
                                                  List<BacktestEngine.BacktestBar> bars) {
        Map<java.time.Instant, Integer> barIndexes = new java.util.HashMap<>();
        for (int index = 0; index < bars.size(); index++) {
            barIndexes.put(bars.get(index).candle().openTime(), index);
        }
        return group(trades, trade -> {
            long fifteenMinutes = java.time.Duration.ofMinutes(15).toMillis();
            java.time.Instant signalBar = java.time.Instant.ofEpochMilli(
                    Math.floorDiv(trade.entryTime().toEpochMilli(), fifteenMinutes)
                            * fifteenMinutes);
            Integer index = barIndexes.get(signalBar);
            if (index == null || index < REGIME_LOOKBACK_BARS) {
                return "UNKNOWN";
            }
            BigDecimal current = bars.get(index).candle().open();
            BigDecimal previous = bars.get(index - REGIME_LOOKBACK_BARS).candle().close();
            BigDecimal move = current.subtract(previous, MC)
                    .multiply(HUNDRED, MC).divide(previous, MC);
            if (move.compareTo(REGIME_MOVE_PERCENT) > 0) {
                return "BULL";
            }
            if (move.compareTo(REGIME_MOVE_PERCENT.negate()) < 0) {
                return "BEAR";
            }
            return "FLAT";
        });
    }

    private BigDecimal makerCounterfactual(Trade trade, BigDecimal takerFeeBps,
                                           BigDecimal makerFeeBps) {
        BigDecimal rawPricePnl = rawPricePnl(trade);
        BigDecimal entryFee = trade.entryPrice().multiply(trade.quantity(), MC)
                .multiply(makerFeeBps, MC).divide(BPS, MC);
        boolean protectiveExit = trade.exitReason().equals("stop loss")
                || trade.exitReason().contains("prop rule")
                || trade.exitReason().equals("end of data");
        BigDecimal exitFeeBps = protectiveExit ? takerFeeBps : makerFeeBps;
        BigDecimal exitFee = trade.exitPrice().multiply(trade.quantity(), MC)
                .multiply(exitFeeBps, MC).divide(BPS, MC);
        BigDecimal remainingSlippage = protectiveExit ? trade.exitSlippageCost() : BigDecimal.ZERO;
        return rawPricePnl.add(trade.fundingPnl(), MC)
                .subtract(entryFee, MC).subtract(exitFee, MC).subtract(remainingSlippage, MC);
    }

    private Map<String, List<Trade>> group(List<Trade> trades,
                                           Function<Trade, String> classifier) {
        Map<String, List<Trade>> groups = new LinkedHashMap<>();
        trades.stream().sorted(Comparator.comparing(classifier)).forEach(trade ->
                groups.computeIfAbsent(classifier.apply(trade), ignored -> new ArrayList<>()).add(trade));
        return groups;
    }

    private Totals totals(List<Trade> trades) {
        BigDecimal raw = BigDecimal.ZERO;
        BigDecimal funding = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal slippage = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (Trade trade : trades) {
            raw = raw.add(rawPricePnl(trade), MC);
            funding = funding.add(trade.fundingPnl(), MC);
            fees = fees.add(trade.entryFee(), MC).add(trade.exitFee(), MC);
            slippage = slippage.add(trade.entrySlippageCost(), MC)
                    .add(trade.exitSlippageCost(), MC);
            net = net.add(trade.netPnl(), MC);
        }
        return new Totals(raw, funding, fees, slippage, net);
    }

    private BigDecimal rawPricePnl(Trade trade) {
        return trade.grossPnl().add(trade.entrySlippageCost(), MC)
                .add(trade.exitSlippageCost(), MC);
    }

    private BigDecimal turnover(Trade trade) {
        return trade.entryPrice().add(trade.exitPrice(), MC).multiply(trade.quantity(), MC);
    }

    private static String number(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private record Totals(BigDecimal rawPricePnl, BigDecimal fundingPnl,
                          BigDecimal fees, BigDecimal slippage, BigDecimal netPnl) {
    }
}
