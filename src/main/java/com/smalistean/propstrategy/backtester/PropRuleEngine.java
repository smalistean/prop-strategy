package com.smalistean.propstrategy.backtester;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

public class PropRuleEngine {

    public record PropRules(
            BigDecimal maxTotalDrawdownPct,
            BigDecimal maxDailyLossPct,
            BigDecimal profitTargetPct
    ) {
    }

    public enum Violation {
        MAX_DRAWDOWN,
        DAILY_LOSS,
        PROFIT_TARGET_REACHED
    }

    public record RuleCheckResult(boolean passed, Optional<Violation> violation) {
        public static RuleCheckResult ok() {
            return new RuleCheckResult(true, Optional.empty());
        }

        public static RuleCheckResult fail(Violation violation) {
            return new RuleCheckResult(false, Optional.of(violation));
        }
    }

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final PropRules rules;
    private BigDecimal dayStartBalance;
    private LocalDate currentDay;

    public PropRuleEngine(PropRules rules) {
        this.rules = rules;
    }

    public RuleCheckResult check(Account account, BigDecimal equity, Instant timestamp) {
        LocalDate day = timestamp.atZone(ZoneOffset.UTC).toLocalDate();
        if (currentDay == null || !currentDay.equals(day)) {
            currentDay = day;
            dayStartBalance = equity;
        }

        BigDecimal initial = account.initialBalance();
        BigDecimal drawdownPct = account.peakBalance().subtract(equity, MC)
                .divide(account.peakBalance(), MC)
                .multiply(BigDecimal.valueOf(100), MC);
        if (drawdownPct.compareTo(rules.maxTotalDrawdownPct()) >= 0) {
            return RuleCheckResult.fail(Violation.MAX_DRAWDOWN);
        }

        BigDecimal dailyLossPct = dayStartBalance.subtract(equity, MC)
                .divide(dayStartBalance, MC)
                .multiply(BigDecimal.valueOf(100), MC);
        if (dailyLossPct.compareTo(rules.maxDailyLossPct()) >= 0) {
            return RuleCheckResult.fail(Violation.DAILY_LOSS);
        }

        BigDecimal profitPct = equity.subtract(initial, MC)
                .divide(initial, MC)
                .multiply(BigDecimal.valueOf(100), MC);
        if (profitPct.compareTo(rules.profitTargetPct()) >= 0) {
            return RuleCheckResult.fail(Violation.PROFIT_TARGET_REACHED);
        }

        return RuleCheckResult.ok();
    }
}
