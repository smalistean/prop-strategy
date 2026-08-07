package com.smalistean.propstrategy.backtester;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Replays independently generated trade opportunities against one shared account. */
public final class PortfolioSimulator {

    public record CandidateTrade(String symbol, Trade trade, BigDecimal sourceBalanceAtEntry) {
        public CandidateTrade {
            if (symbol.isBlank() || sourceBalanceAtEntry.signum() <= 0) {
                throw new IllegalArgumentException("Invalid portfolio candidate trade");
            }
        }
    }

    public record Config(BigDecimal initialBalance, int maximumOpenPositions,
                         BigDecimal maximumLeverage,
                         BigDecimal maximumCorrelatedNotionalFraction) {
        public Config {
            if (initialBalance.signum() <= 0 || maximumOpenPositions <= 0
                    || maximumLeverage.signum() <= 0
                    || maximumCorrelatedNotionalFraction.signum() <= 0) {
                throw new IllegalArgumentException("Invalid portfolio configuration");
            }
        }
    }

    public record Result(BigDecimal finalBalance, BigDecimal returnPercent,
                         BigDecimal maximumRealizedDrawdownPercent,
                         int acceptedTrades, int positionCapRejections,
                         int leverageCapRejections, int correlationCapRejections,
                         int maximumConcurrentPositions) {
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final Config config;

    public PortfolioSimulator(Config config) {
        this.config = config;
    }

    public Result run(List<CandidateTrade> candidates) {
        List<CandidateTrade> ordered = candidates.stream()
                .sorted(Comparator.comparing(candidate -> candidate.trade().entryTime()))
                .toList();
        List<OpenTrade> open = new ArrayList<>();
        BigDecimal balance = config.initialBalance();
        BigDecimal peak = balance;
        BigDecimal maximumDrawdown = BigDecimal.ZERO;
        int accepted = 0;
        int positionRejected = 0;
        int leverageRejected = 0;
        int correlationRejected = 0;
        int maximumConcurrent = 0;

        for (CandidateTrade candidate : ordered) {
            Settlement settlement = settleThrough(open, candidate.trade().entryTime(), balance,
                    peak, maximumDrawdown);
            balance = settlement.balance();
            peak = settlement.peak();
            maximumDrawdown = settlement.maximumDrawdown();

            if (open.size() >= config.maximumOpenPositions()) {
                positionRejected++;
                continue;
            }
            BigDecimal scale = balance.divide(candidate.sourceBalanceAtEntry(), MC);
            BigDecimal notional = candidate.trade().entryPrice()
                    .multiply(candidate.trade().quantity(), MC).multiply(scale, MC);
            BigDecimal openNotional = open.stream().map(OpenTrade::notional)
                    .reduce(BigDecimal.ZERO, (left, right) -> left.add(right, MC));
            BigDecimal resultingNotional = openNotional.add(notional, MC);
            if (resultingNotional.compareTo(balance.multiply(config.maximumLeverage(), MC)) > 0) {
                leverageRejected++;
                continue;
            }
            // All current instruments are crypto Futures and belong to one correlation bucket.
            if (resultingNotional.compareTo(
                    balance.multiply(config.maximumCorrelatedNotionalFraction(), MC)) > 0) {
                correlationRejected++;
                continue;
            }
            BigDecimal scaledPnl = candidate.trade().netPnl().multiply(scale, MC);
            open.add(new OpenTrade(candidate.trade().exitTime(), notional, scaledPnl));
            accepted++;
            maximumConcurrent = Math.max(maximumConcurrent, open.size());
        }

        Settlement finalSettlement = settleThrough(open, Instant.MAX, balance, peak, maximumDrawdown);
        balance = finalSettlement.balance();
        maximumDrawdown = finalSettlement.maximumDrawdown();
        BigDecimal returnPercent = balance.subtract(config.initialBalance(), MC)
                .divide(config.initialBalance(), MC).multiply(BigDecimal.valueOf(100), MC);
        return new Result(balance, returnPercent, maximumDrawdown, accepted,
                positionRejected, leverageRejected, correlationRejected, maximumConcurrent);
    }

    private Settlement settleThrough(List<OpenTrade> open, Instant time, BigDecimal startingBalance,
                                     BigDecimal startingPeak, BigDecimal startingDrawdown) {
        List<OpenTrade> closing = open.stream()
                .filter(item -> !item.exitTime().isAfter(time))
                .sorted(Comparator.comparing(OpenTrade::exitTime)).toList();
        BigDecimal balance = startingBalance;
        BigDecimal peak = startingPeak;
        BigDecimal maximumDrawdown = startingDrawdown;
        for (OpenTrade item : closing) {
            balance = balance.add(item.scaledNetPnl(), MC);
            peak = peak.max(balance);
            BigDecimal drawdown = peak.subtract(balance, MC).divide(peak, MC)
                    .multiply(BigDecimal.valueOf(100), MC);
            maximumDrawdown = maximumDrawdown.max(drawdown);
            open.remove(item);
        }
        return new Settlement(balance, peak, maximumDrawdown);
    }

    private record OpenTrade(Instant exitTime, BigDecimal notional, BigDecimal scaledNetPnl) {
    }

    private record Settlement(BigDecimal balance, BigDecimal peak, BigDecimal maximumDrawdown) {
    }
}
