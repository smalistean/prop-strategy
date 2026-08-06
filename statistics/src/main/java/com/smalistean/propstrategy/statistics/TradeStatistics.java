package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.Trade;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

public class TradeStatistics {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    public record Stats(
            int totalTrades,
            int winningTrades,
            int losingTrades,
            BigDecimal winRate,
            BigDecimal totalPnl,
            BigDecimal averagePnl,
            BigDecimal profitFactor
    ) {
    }

    public Stats calculate(List<Trade> trades) {
        if (trades.isEmpty()) {
            return new Stats(0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        int wins = 0;
        int losses = 0;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        BigDecimal totalPnl = BigDecimal.ZERO;

        for (Trade trade : trades) {
            totalPnl = totalPnl.add(trade.pnl(), MC);
            if (trade.pnl().signum() > 0) {
                wins++;
                grossProfit = grossProfit.add(trade.pnl(), MC);
            } else if (trade.pnl().signum() < 0) {
                losses++;
                grossLoss = grossLoss.add(trade.pnl().abs(), MC);
            }
        }

        BigDecimal winRate = BigDecimal.valueOf(wins)
                .divide(BigDecimal.valueOf(trades.size()), MC)
                .multiply(BigDecimal.valueOf(100), MC);
        BigDecimal averagePnl = totalPnl.divide(BigDecimal.valueOf(trades.size()), MC);
        BigDecimal profitFactor = grossLoss.signum() == 0
                ? grossProfit
                : grossProfit.divide(grossLoss, MC);

        return new Stats(trades.size(), wins, losses, winRate, totalPnl, averagePnl, profitFactor);
    }
}
