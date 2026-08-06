package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.Account;
import com.smalistean.propstrategy.backtester.BacktestEngine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;

public class PerformanceReport {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    public record Report(
            BigDecimal initialBalance,
            BigDecimal finalBalance,
            BigDecimal netProfit,
            BigDecimal returnPct,
            DrawdownCalculator.DrawdownStats drawdown,
            TradeStatistics.Stats tradeStats,
            Optional<String> terminationReason
    ) {
        @Override
        public String toString() {
            return """
                    === Performance Report ===
                    Initial Balance : %s
                    Final Balance   : %s
                    Net Profit      : %s
                    Return          : %s%%
                    Max Drawdown    : %s (%s%%)
                    Trades          : %d (win rate %s%%)
                    Winning/Losing  : %d / %d
                    Average Win     : %s
                    Average Loss    : %s
                    Expectancy      : %s per trade
                    Profit Factor   : %s
                    Fees            : %s
                    Funding PnL     : %s
                    Slippage Cost   : %s
                    Termination     : %s
                    """.formatted(
                    initialBalance.toPlainString(),
                    finalBalance.toPlainString(),
                    netProfit.toPlainString(),
                    returnPct.toPlainString(),
                    drawdown.maxDrawdown().toPlainString(),
                    drawdown.maxDrawdownPct().toPlainString(),
                    tradeStats.totalTrades(),
                    tradeStats.winRate().toPlainString(),
                    tradeStats.winningTrades(),
                    tradeStats.losingTrades(),
                    tradeStats.averageWin().toPlainString(),
                    tradeStats.averageLoss().toPlainString(),
                    tradeStats.averagePnl().toPlainString(),
                    tradeStats.profitFactor().toPlainString(),
                    tradeStats.totalFees().toPlainString(),
                    tradeStats.totalFunding().toPlainString(),
                    tradeStats.totalSlippageCost().toPlainString(),
                    terminationReason.orElse("completed")
            );
        }
    }

    private final DrawdownCalculator drawdownCalculator = new DrawdownCalculator();
    private final TradeStatistics tradeStatistics = new TradeStatistics();

    public Report generate(BacktestEngine.BacktestResult result) {
        Account account = result.account();
        BigDecimal initial = account.initialBalance();
        BigDecimal finalBalance = account.balance();
        BigDecimal netProfit = finalBalance.subtract(initial, MC);
        BigDecimal returnPct = netProfit
                .divide(initial, MC)
                .multiply(BigDecimal.valueOf(100), MC);

        DrawdownCalculator.DrawdownStats drawdown = drawdownCalculator.calculate(account.equityCurve());
        TradeStatistics.Stats stats = tradeStatistics.calculate(account.closedTrades());
        Optional<String> termination = result.terminationReason().map(Enum::name);

        return new Report(initial, finalBalance, netProfit, returnPct, drawdown, stats, termination);
    }
}
