package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.strategy.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropRuleEngineTest {

    private static final Instant DAY1_T00 = Instant.parse("2026-08-22T00:00:00Z");
    private static final Instant DAY1_T12 = Instant.parse("2026-08-22T12:00:00Z");
    private static final Instant DAY2_T00 = Instant.parse("2026-08-23T00:00:00Z");

    private static PropRuleEngine.PropRules rules(String maxDrawdownPct, String maxDailyLossPct,
                                                   String profitTargetPct) {
        return new PropRuleEngine.PropRules(new BigDecimal(maxDrawdownPct),
                new BigDecimal(maxDailyLossPct), new BigDecimal(profitTargetPct));
    }

    @Test
    void reproducesTheAccountsActualCheckpointBeforeThisTrade() {
        // 2026-08-22, before the discretionary BTC short: 49,927.02 of 50,000, i.e. 72.98 used
        // of the 5,000 total limit. Real Stage 1 parameters: 50,000 / 2,500 / 5,000 / 4,000.
        PropRuleEngine engine = new PropRuleEngine(rules("10", "5", "8"));
        Account account = new Account(new BigDecimal("50000"));

        PropRuleEngine.RuleCheckResult result = engine.check(account, new BigDecimal("49927.02"), DAY1_T00);

        assertTrue(result.passed());
    }

    @Test
    void reproducesTheAccountsActualCheckpointAfterClosingTheBtcShort() {
        // 2026-08-25 11:31 UTC, after closing flat: 49,223.01 of 50,000, i.e. 776.99 used.
        // Still well inside the 5,000 total limit and should not trip anything.
        PropRuleEngine engine = new PropRuleEngine(rules("10", "5", "8"));
        Account account = new Account(new BigDecimal("50000"));

        PropRuleEngine.RuleCheckResult result = engine.check(account, new BigDecimal("49223.01"), DAY1_T00);

        assertTrue(result.passed());
    }

    @Test
    void totalDrawdownIsStaticFromInitialBalanceNotATrailingPeak() {
        PropRuleEngine engine = new PropRuleEngine(rules("5", "100", "100"));
        Account account = new Account(new BigDecimal("50000"));

        // Equity runs up to 60,000 (a new peak), then gives back 4,000 to 56,000 — still a
        // 6,000 profit over the 50,000 start. A trailing-peak rule would see (60000-56000)/60000
        // = 6.67% and wrongly fail MAX_DRAWDOWN even though the account never lost money.
        assertTrue(engine.check(account, new BigDecimal("60000"), DAY1_T00).passed());
        PropRuleEngine.RuleCheckResult result = engine.check(account, new BigDecimal("56000"), DAY1_T00);

        assertTrue(result.passed());
    }

    @Test
    void totalDrawdownTriggersWhenEquityFallsBelowTheStaticThreshold() {
        PropRuleEngine engine = new PropRuleEngine(rules("5", "100", "100"));
        Account account = new Account(new BigDecimal("50000"));

        // 47,000 is a 6% drawdown from the 50,000 initial balance — over the 5% limit.
        PropRuleEngine.RuleCheckResult result = engine.check(account, new BigDecimal("47000"), DAY1_T00);

        assertEquals(PropRuleEngine.Violation.MAX_DRAWDOWN, result.violation().orElseThrow());
    }

    @Test
    void dailyLossIsMeasuredFromTheDaysRunningPeakEquityNotJustDayStartBalance() {
        PropRuleEngine engine = new PropRuleEngine(rules("100", "5", "100"));
        Account account = new Account(new BigDecimal("50000"));

        assertTrue(engine.check(account, new BigDecimal("50000"), DAY1_T00).passed());
        // Equity rises intraday to 52,000, becoming the day's peak.
        assertTrue(engine.check(account, new BigDecimal("52000"), DAY1_T12).passed());

        // 49,000 is only a 2% drop from day-start (50,000) but a 5.77% drop from the intraday
        // peak (52,000) — the conservative basis the firm's daily-loss basis was left unconfirmed
        // for, per PROP_CHALLENGE_PLAN.md #9.3, catches this; a day-start-only basis would not.
        PropRuleEngine.RuleCheckResult result = engine.check(account, new BigDecimal("49000"), DAY1_T12);

        assertEquals(PropRuleEngine.Violation.DAILY_LOSS, result.violation().orElseThrow());
    }

    @Test
    void dailyPeakResetsAtTheUtcDayBoundary() {
        PropRuleEngine engine = new PropRuleEngine(rules("100", "5", "100"));
        Account account = new Account(new BigDecimal("50000"));

        assertTrue(engine.check(account, new BigDecimal("60000"), DAY1_T00).passed());
        assertTrue(engine.check(account, new BigDecimal("59000"), DAY1_T12).passed());

        // A fresh day starts here. If yesterday's 60,000 peak carried over, 56,800 would read as
        // a 5.33% drop and wrongly fail; resetting the peak to the new day's opening equity means
        // the very first check of the day always passes with zero elapsed daily loss.
        PropRuleEngine.RuleCheckResult result = engine.check(account, new BigDecimal("56800"), DAY2_T00);

        assertTrue(result.passed());
    }

    @Test
    void profitTargetIgnoresFloatingPnlWhileAPositionIsOpen() {
        PropRuleEngine engine = new PropRuleEngine(rules("100", "100", "8"));
        Account account = new Account(new BigDecimal("50000"));
        ExecutionModel.Fill entryFill = new ExecutionModel.Fill(
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO);
        account.open(DAY1_T00, Side.LONG, new BigDecimal("100"),
                new BigDecimal("90"), new BigDecimal("200"), entryFill);

        // Floating equity is 54,500 (9% over the 50,000 start, above the 8% target) but the
        // position is still open — the target must not fire on unrealised PnL.
        BigDecimal floatingEquity = new BigDecimal("54500");
        PropRuleEngine.RuleCheckResult result = engine.check(account, floatingEquity, DAY1_T12);

        assertTrue(result.passed());
    }

    @Test
    void profitTargetFiresOnRealisedBalanceOnceFlatRegardlessOfTheReportedEquity() {
        PropRuleEngine engine = new PropRuleEngine(rules("100", "100", "8"));
        Account account = new Account(new BigDecimal("50000"));
        ExecutionModel.Fill entryFill = new ExecutionModel.Fill(
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO);
        account.open(DAY1_T00, Side.LONG, new BigDecimal("100"),
                new BigDecimal("90"), new BigDecimal("200"), entryFill);
        ExecutionModel.Fill exitFill = new ExecutionModel.Fill(
                new BigDecimal("145"), new BigDecimal("145"), BigDecimal.ZERO, BigDecimal.ZERO);
        account.close(DAY1_T12, exitFill, "test close");

        // Realised balance is now 54,500 (9% over start). Pass an unrelated "equity" value to
        // prove the target reads account.balance(), not the equity argument.
        PropRuleEngine.RuleCheckResult result = engine.check(account, new BigDecimal("50000"), DAY2_T00);

        assertEquals(PropRuleEngine.Violation.PROFIT_TARGET_REACHED, result.violation().orElseThrow());
    }
}
