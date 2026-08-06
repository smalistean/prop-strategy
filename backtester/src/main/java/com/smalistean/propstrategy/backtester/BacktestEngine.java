package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.strategy.Signal;
import com.smalistean.propstrategy.strategy.Strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

public class BacktestEngine {

    public record BacktestConfig(
            BigDecimal initialBalance,
            BigDecimal riskFraction,
            BigDecimal stopLossPct,
            BigDecimal slippageBps,
            BigDecimal feeBps,
            PropRuleEngine.PropRules propRules
    ) {
    }

    public record BacktestResult(
            Account account,
            Optional<PropRuleEngine.Violation> terminationReason
    ) {
    }

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final BacktestConfig config;

    public BacktestEngine(BacktestConfig config) {
        this.config = config;
    }

    public BacktestResult run(Strategy strategy, List<Kline> klines) {
        Account account = new Account(config.initialBalance());
        PositionSizer sizer = new PositionSizer(config.riskFraction());
        ExecutionModel execution = new ExecutionModel(config.slippageBps(), config.feeBps());
        PropRuleEngine propRules = new PropRuleEngine(config.propRules());
        Optional<PropRuleEngine.Violation> termination = Optional.empty();

        for (int i = 0; i < klines.size(); i++) {
            Kline bar = klines.get(i);
            account.markToMarket(bar.close());

            PropRuleEngine.RuleCheckResult ruleCheck = propRules.check(account, bar.openTime());
            if (!ruleCheck.passed()) {
                if (account.hasOpenPosition()) {
                    BigDecimal exitPrice = execution.fillPrice(bar, false);
                    account.close(bar.openTime(), exitPrice);
                }
                termination = ruleCheck.violation();
                break;
            }

            Signal signal = strategy.evaluate(klines, i);
            if (signal == Signal.BUY && !account.hasOpenPosition()) {
                BigDecimal entry = execution.fillPrice(bar, true);
                BigDecimal stop = entry.multiply(BigDecimal.ONE.subtract(config.stopLossPct(), MC), MC);
                BigDecimal qty = sizer.size(account.balance(), entry, stop);
                if (qty.signum() > 0) {
                    account.openLong(bar.openTime(), entry, qty);
                    account.applyFee(execution.fee(entry.multiply(qty, MC)));
                }
            } else if (signal == Signal.SELL && account.hasOpenPosition()) {
                BigDecimal exitPrice = execution.fillPrice(bar, false);
                account.close(bar.openTime(), exitPrice);
            }
        }

        if (account.hasOpenPosition()) {
            Kline last = klines.getLast();
            account.close(last.openTime(), last.close());
        }

        return new BacktestResult(account, termination);
    }
}
