package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

public final class BacktestEngine {

    public record BacktestConfig(
            BigDecimal initialBalance,
            BigDecimal riskFraction,
            BigDecimal maxLeverage,
            BigDecimal slippageBps,
            BigDecimal takerFeeBps,
            PropRuleEngine.PropRules propRules
    ) {
        public BacktestConfig {
            if (initialBalance.signum() <= 0 || riskFraction.signum() <= 0
                    || riskFraction.compareTo(BigDecimal.ONE) > 0
                    || maxLeverage.signum() <= 0) {
                throw new IllegalArgumentException("Invalid backtest balance/risk configuration");
            }
        }
    }

    public record BacktestBar(Kline candle, FeatureSnapshot features) {
        public BacktestBar {
            if (!candle.openTime().equals(features.candleOpenTime())) {
                throw new IllegalArgumentException("Candle and feature timestamps must match");
            }
        }
    }

    public record BacktestResult(
            Account account,
            Optional<PropRuleEngine.Violation> terminationReason
    ) {
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final BacktestConfig config;

    public BacktestEngine(BacktestConfig config) {
        this.config = config;
    }

    public BacktestResult run(Strategy strategy, List<BacktestBar> bars,
                              List<FundingRate> fundingRates) {
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("Backtest requires at least one bar");
        }
        Account account = new Account(config.initialBalance());
        PositionSizer sizer = new PositionSizer(config.riskFraction());
        ExecutionModel execution = new ExecutionModel(config.slippageBps(), config.takerFeeBps());
        PropRuleEngine propRules = new PropRuleEngine(config.propRules());
        List<FeatureSnapshot> history = bars.stream().map(BacktestBar::features).toList();
        StrategyDecision pending = StrategyDecision.hold();
        Optional<PropRuleEngine.Violation> termination = Optional.empty();
        int fundingIndex = 0;

        for (int index = 0; index < bars.size(); index++) {
            BacktestBar frame = bars.get(index);
            Kline bar = frame.candle();

            executePending(pending, account, sizer, execution, bar);
            applyFundingThrough(account, fundingRates, bar, fundingIndex);
            while (fundingIndex < fundingRates.size()
                    && !fundingRates.get(fundingIndex).fundingTime().isAfter(bar.closeTime())) {
                fundingIndex++;
            }
            executeProtectiveOrders(account, execution, bar);
            account.incrementBarsHeld();
            BigDecimal equity = account.markToMarket(bar.close());

            PropRuleEngine.RuleCheckResult ruleCheck = propRules.check(account, equity, bar.closeTime());
            if (!ruleCheck.passed()) {
                if (account.hasOpenPosition()) {
                    closeAt(account, execution, bar.closeTime(), bar.close(), "prop rule termination");
                }
                termination = ruleCheck.violation();
                break;
            }

            pending = strategy.evaluate(history, index, account.positionView());
        }

        if (account.hasOpenPosition()) {
            Kline last = bars.getLast().candle();
            closeAt(account, execution, last.closeTime(), last.close(), "end of data");
        }
        return new BacktestResult(account, termination);
    }

    private void executePending(StrategyDecision pending, Account account,
                                PositionSizer sizer, ExecutionModel execution, Kline bar) {
        if (pending instanceof StrategyDecision.Exit exit && account.hasOpenPosition()) {
            closeAt(account, execution, bar.openTime(), bar.open(), exit.reason());
            return;
        }
        if (!(pending instanceof StrategyDecision.Enter entry) || account.hasOpenPosition()) {
            return;
        }

        boolean buy = entry.side() == Side.LONG;
        ExecutionModel.Fill unitFill = execution.fill(bar.open(), buy, BigDecimal.ONE);
        BigDecimal quantity = sizer.size(account.balance(), unitFill.fillPrice(),
                entry.side() == Side.LONG
                        ? unitFill.fillPrice().subtract(entry.stopDistance(), MC)
                        : unitFill.fillPrice().add(entry.stopDistance(), MC));
        BigDecimal maximumQuantity = account.balance().multiply(config.maxLeverage(), MC)
                .divide(unitFill.fillPrice(), MC);
        quantity = quantity.min(maximumQuantity);
        if (quantity.signum() <= 0) {
            return;
        }
        ExecutionModel.Fill fill = execution.fill(bar.open(), buy, quantity);
        BigDecimal stop = entry.side() == Side.LONG
                ? fill.fillPrice().subtract(entry.stopDistance(), MC)
                : fill.fillPrice().add(entry.stopDistance(), MC);
        BigDecimal target = entry.side() == Side.LONG
                ? fill.fillPrice().add(entry.targetDistance(), MC)
                : fill.fillPrice().subtract(entry.targetDistance(), MC);
        if (stop.signum() <= 0 || target.signum() <= 0) {
            return;
        }
        account.open(bar.openTime(), entry.side(), quantity, stop, target, fill);
    }

    private static void executeProtectiveOrders(Account account, ExecutionModel execution,
                                                Kline bar) {
        if (!account.hasOpenPosition()) {
            return;
        }
        if (account.side() == Side.LONG) {
            if (bar.low().compareTo(account.stopPrice()) <= 0) {
                BigDecimal reference = bar.open().compareTo(account.stopPrice()) < 0
                        ? bar.open() : account.stopPrice();
                closeAt(account, execution, bar.closeTime(), reference, "stop loss");
            } else if (bar.high().compareTo(account.targetPrice()) >= 0) {
                closeAt(account, execution, bar.closeTime(), account.targetPrice(), "take profit");
            }
        } else if (bar.high().compareTo(account.stopPrice()) >= 0) {
            BigDecimal reference = bar.open().compareTo(account.stopPrice()) > 0
                    ? bar.open() : account.stopPrice();
            closeAt(account, execution, bar.closeTime(), reference, "stop loss");
        } else if (bar.low().compareTo(account.targetPrice()) <= 0) {
            closeAt(account, execution, bar.closeTime(), account.targetPrice(), "take profit");
        }
    }

    private static void applyFundingThrough(Account account, List<FundingRate> rates,
                                            Kline bar, int startIndex) {
        for (int i = startIndex; i < rates.size(); i++) {
            FundingRate rate = rates.get(i);
            if (rate.fundingTime().isAfter(bar.closeTime())) {
                break;
            }
            if (!rate.fundingTime().isBefore(bar.openTime()) && account.hasOpenPosition()) {
                BigDecimal price = rate.markPrice() == null ? bar.close() : rate.markPrice();
                BigDecimal payment = price.multiply(account.quantity(), MC)
                        .multiply(rate.fundingRate(), MC);
                account.applyFunding(account.side() == Side.LONG ? payment.negate() : payment);
            }
        }
    }

    private static void closeAt(Account account, ExecutionModel execution,
                                java.time.Instant time, BigDecimal referencePrice, String reason) {
        boolean buy = account.side() == Side.SHORT;
        ExecutionModel.Fill fill = execution.fill(referencePrice, buy, account.quantity());
        account.close(time, fill, reason);
    }
}
