package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeHarnessTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final BigDecimal BALANCE = new BigDecimal("50000");

    /** Stop and target both 10 points from a 100 open, so a bar resolves one or the other. */
    private enum Bar {
        /** high clears the 110 target, low never reaches the 90 stop. */
        WIN("100", "115", "95", "100"),
        /** high clears a far 200 target too, for testing a single outsized payout. */
        BIG_WIN("100", "250", "95", "100"),
        /** low pierces the 90 stop, high never reaches the 110 target. */
        LOSE("100", "105", "85", "100"),
        /** neither level trades; the position stays open. */
        QUIET("100", "104", "96", "100");

        private final String open;
        private final String high;
        private final String low;
        private final String close;

        Bar(String open, String high, String low, String close) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }
    }

    @Test
    void producesOneAttemptPerStartDateThatHasAFullWindowOfDataBehindIt() {
        // 40 daily bars, 10-day windows spaced 10 days apart: starts at day 0, 10, 20 and 30 all
        // have a full window; a start at day 40 would run past the data and must not be attempted.
        List<BacktestEngine.BacktestBar> bars = dailyBars(repeat(Bar.QUIET, 40));

        ChallengeHarness.Summary summary = harness(Duration.ofDays(10), Duration.ofDays(10), 5)
                .run("quiet", ChallengeHarnessTest::neverTrades, bars, List.of(), List.of());

        assertEquals(4, summary.attempts());
        assertEquals(START, summary.allAttempts().getFirst().startInclusive());
        assertEquals(START.plus(Duration.ofDays(30)), summary.allAttempts().getLast().startInclusive());
    }

    @Test
    void reachingTheProfitTargetOverEnoughTradingDaysCountsAsAPass() {
        // One winning daily trade per day at 1% risk and 1:1 reward compounds past the 8% target on
        // the eighth day - comfortably more than the five-trading-day minimum.
        List<BacktestEngine.BacktestBar> bars = dailyBars(repeat(Bar.WIN, 20));

        ChallengeHarness.Summary summary = harness(Duration.ofDays(20), Duration.ofDays(20), 5)
                .run("winner", ChallengeHarnessTest::alwaysEntersLong, bars, List.of(), List.of());

        ChallengeHarness.Attempt attempt = summary.allAttempts().getFirst();
        assertEquals(ChallengeHarness.Outcome.PASSED, attempt.outcome());
        assertTrue(attempt.tradingDays() >= 5, "expected the minimum-day rule to be satisfied");
        assertEquals(0, summary.passRate().compareTo(BigDecimal.ONE));
    }

    @Test
    void reachingTheProfitTargetTooFastIsReportedSeparatelyRatherThanAsAPass() {
        // A single 10:1 payout clears the whole 8% target on day one. The money rule is satisfied and
        // the trading-day rule is not, and conflating the two is exactly what this outcome prevents.
        List<BacktestEngine.BacktestBar> bars = dailyBars(repeat(Bar.BIG_WIN, 20));

        ChallengeHarness.Summary summary = harness(Duration.ofDays(20), Duration.ofDays(20), 5)
                .run("fast-winner", () -> alwaysEnters(Side.LONG, "10", "100"),
                        bars, List.of(), List.of());

        ChallengeHarness.Attempt attempt = summary.allAttempts().getFirst();
        assertEquals(ChallengeHarness.Outcome.TARGET_HIT_BUT_TOO_FEW_TRADING_DAYS, attempt.outcome());
        assertEquals(1, attempt.tradingDays());
        assertEquals(0, summary.passRate().compareTo(BigDecimal.ZERO));
    }

    @Test
    void spreadingLossesAcrossDaysBreachesTheTotalLimitRatherThanTheDailyOne() {
        // One losing trade per day at 1% never approaches the 5% daily limit, so the run must end on
        // the 10% total limit. This is the case that separates the two rules from each other.
        List<BacktestEngine.BacktestBar> bars = dailyBars(repeat(Bar.LOSE, 40));

        ChallengeHarness.Summary summary = harness(Duration.ofDays(40), Duration.ofDays(40), 5)
                .run("loser", ChallengeHarnessTest::alwaysEntersLong, bars, List.of(), List.of());

        ChallengeHarness.Attempt attempt = summary.allAttempts().getFirst();
        assertEquals(ChallengeHarness.Outcome.FAILED_MAX_LOSS, attempt.outcome());
        assertEquals(0, summary.failRate().compareTo(BigDecimal.ONE));
    }

    @Test
    void stackingLossesInsideOneDayBreachesTheDailyLimitFirst() {
        // Six losing 15-minute trades land on the same UTC date. The daily limit is reached at five,
        // well before the total limit could be, so the daily rule must be what ends the attempt.
        List<BacktestEngine.BacktestBar> bars = intradayBars(repeat(Bar.LOSE, 40));

        ChallengeHarness.Summary summary =
                harness(Duration.ofHours(10), Duration.ofHours(10), 5)
                        .run("intraday-loser", ChallengeHarnessTest::alwaysEntersLong,
                                bars, List.of(), List.of());

        assertEquals(ChallengeHarness.Outcome.FAILED_DAILY_LOSS,
                summary.allAttempts().getFirst().outcome());
    }

    @Test
    void worstDrawdownIsMeasuredFromTheStartingBalanceSoAGiveBackOfProfitReadsAsZero() {
        // Eight wins lift equity to roughly 54,100, then two losses give back about 1,080. A
        // trailing-peak measure would call that a 1,080 drawdown; the firm's static rule does not,
        // because the account never traded below the 50,000 it started with.
        List<BacktestEngine.BacktestBar> bars = dailyBars(
                concat(repeat(Bar.WIN, 8), repeat(Bar.LOSE, 2)));

        ChallengeHarness.Summary summary = harness(Duration.ofDays(10), Duration.ofDays(10), 5,
                new PropRuleEngine.PropRules(new BigDecimal("10"), new BigDecimal("5"),
                        new BigDecimal("100")))
                .run("give-back", ChallengeHarnessTest::alwaysEntersLong, bars, List.of(), List.of());

        ChallengeHarness.Attempt attempt = summary.allAttempts().getFirst();
        assertTrue(attempt.netProfit().signum() > 0, "expected the window to end in profit");
        assertEquals(0, attempt.worstDrawdown().compareTo(BigDecimal.ZERO));
    }

    @Test
    void anAttemptThatNeitherPassesNorBreachesIsUnresolvedRatherThanCountedEitherWay() {
        List<BacktestEngine.BacktestBar> bars = dailyBars(repeat(Bar.QUIET, 20));

        ChallengeHarness.Summary summary = harness(Duration.ofDays(20), Duration.ofDays(20), 5)
                .run("no-trades", ChallengeHarnessTest::neverTrades, bars, List.of(), List.of());

        ChallengeHarness.Attempt attempt = summary.allAttempts().getFirst();
        assertEquals(ChallengeHarness.Outcome.UNRESOLVED, attempt.outcome());
        assertEquals(0, attempt.trades());
        assertEquals(0, summary.passRate().compareTo(BigDecimal.ZERO));
        assertEquals(0, summary.failRate().compareTo(BigDecimal.ZERO));
        assertTrue(summary.medianCalendarDaysToPass().isEmpty());
    }

    @Test
    void everyAttemptGetsAnIdenticalWindowSoOutcomesFromDifferentErasAreComparable() {
        List<BacktestEngine.BacktestBar> bars = dailyBars(repeat(Bar.QUIET, 60));

        ChallengeHarness.Summary summary = harness(Duration.ofDays(5), Duration.ofDays(20), 5)
                .run("quiet", ChallengeHarnessTest::neverTrades, bars, List.of(), List.of());

        assertFalse(summary.allAttempts().isEmpty());
        summary.allAttempts().forEach(attempt -> assertEquals(Duration.ofDays(20),
                Duration.between(attempt.startInclusive(), attempt.endExclusive())));
    }

    private static ChallengeHarness harness(Duration spacing, Duration attempt, int minimumDays) {
        // 10% total, 5% daily, 8% target - the real Stage 1 shape against a 50,000 balance.
        return harness(spacing, attempt, minimumDays, new PropRuleEngine.PropRules(
                new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("8")));
    }

    private static ChallengeHarness harness(Duration spacing, Duration attempt, int minimumDays,
                                            PropRuleEngine.PropRules rules) {
        BacktestEngine.BacktestConfig config = new BacktestEngine.BacktestConfig(
                BALANCE, new BigDecimal("0.01"), new BigDecimal("100"),
                new BacktestEngine.ExecutionConfig(false, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, 5, true, false, BigDecimal.ONE),
                rules);
        return new ChallengeHarness(config,
                new ChallengeHarness.HarnessConfig(spacing, attempt, minimumDays));
    }

    private static Strategy alwaysEntersLong() {
        return alwaysEnters(Side.LONG, "10", "10");
    }

    private static Strategy alwaysEnters(Side side, String stopDistance, String targetDistance) {
        return new Strategy() {
            @Override
            public String name() {
                return "always-enter";
            }

            @Override
            public Set<FeatureKey> requiredFeatures() {
                return Set.of(FeatureKey.close());
            }

            @Override
            public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                             PositionView position) {
                return position.isOpen() ? StrategyDecision.hold()
                        : new StrategyDecision.Enter(side, new BigDecimal(stopDistance),
                        new BigDecimal(targetDistance));
            }
        };
    }

    private static Strategy neverTrades() {
        return new Strategy() {
            @Override
            public String name() {
                return "never-trade";
            }

            @Override
            public Set<FeatureKey> requiredFeatures() {
                return Set.of(FeatureKey.close());
            }

            @Override
            public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                             PositionView position) {
                return StrategyDecision.hold();
            }
        };
    }

    private static List<Bar> repeat(Bar bar, int count) {
        List<Bar> bars = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            bars.add(bar);
        }
        return bars;
    }

    private static List<Bar> concat(List<Bar> first, List<Bar> second) {
        List<Bar> combined = new ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }

    private static List<BacktestEngine.BacktestBar> dailyBars(List<Bar> specs) {
        return bars(specs, Duration.ofDays(1));
    }

    private static List<BacktestEngine.BacktestBar> intradayBars(List<Bar> specs) {
        return bars(specs, Duration.ofMinutes(15));
    }

    private static List<BacktestEngine.BacktestBar> bars(List<Bar> specs, Duration interval) {
        List<BacktestEngine.BacktestBar> bars = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            Bar spec = specs.get(index);
            Instant openTime = START.plus(interval.multipliedBy(index));
            Instant closeTime = openTime.plus(interval).minusMillis(1);
            Kline candle = new Kline(openTime, new BigDecimal(spec.open), new BigDecimal(spec.high),
                    new BigDecimal(spec.low), new BigDecimal(spec.close), BigDecimal.ONE,
                    closeTime, BigDecimal.ZERO, 1, BigDecimal.ZERO, BigDecimal.ZERO);
            FeatureSnapshot features = new FeatureSnapshot(openTime, closeTime,
                    closeTime.plusMillis(1),
                    Map.of(FeatureKey.close(), new BigDecimal(spec.close)));
            bars.add(new BacktestEngine.BacktestBar(candle, features));
        }
        return bars;
    }
}
