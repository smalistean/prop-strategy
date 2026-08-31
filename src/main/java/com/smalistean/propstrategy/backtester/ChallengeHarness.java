package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.strategy.Strategy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Replays one strategy as a prop challenge from many independent start dates.
 *
 * <p>A single backtest over the whole history answers "what would this have returned". That is not
 * the question the challenge asks. The challenge is a race between a profit target and two loss
 * limits, started once, and its answer is a <b>probability</b>: how often does this strategy reach
 * +target before it breaches. One equity curve cannot express that; a distribution over start dates
 * can, which is what this class produces.
 *
 * <p>Every attempt gets an identical {@code attemptDuration} window rather than running to the end
 * of the data, so an attempt starting in 2021 and one starting in 2026 had the same opportunity and
 * their outcomes are comparable. Start dates with less than a full window of data remaining are
 * skipped for the same reason. The rules place no calendar limit on a real attempt, so the duration
 * is a reported dimension to sweep, not a fact about the challenge - the same treatment
 * {@code execution_delay} received in the XVF calibration work.
 *
 * <p><b>Known edge effect.</b> Features are generated once over the full history, so their values
 * are correct at every bar, but a strategy that inspects the bar history it is handed sees a
 * truncated list at the start of each window. Strategies guard their own lookbacks, so the effect is
 * that the first bars of a window produce no entries rather than wrong ones - it delays each attempt
 * slightly and cannot manufacture trades. {@code Attempt.barsBeforeFirstTrade()} exposes it so the
 * size of the delay is visible instead of assumed.
 */
public final class ChallengeHarness {

    /**
     * @param startSpacing       gap between consecutive simulated start dates. Windows overlap, so
     *                           attempts are not independent samples; spacing trades sample count
     *                           against how much history two neighbouring attempts share.
     * @param attemptDuration    identical calendar length granted to every attempt.
     * @param minimumTradingDays the firm's minimum-trading-days rule. Reaching the profit target in
     *                           fewer days is reported separately rather than counted as a pass.
     */
    public record HarnessConfig(Duration startSpacing, Duration attemptDuration,
                                int minimumTradingDays) {
        public HarnessConfig {
            if (startSpacing.isZero() || startSpacing.isNegative()) {
                throw new IllegalArgumentException("startSpacing must be positive");
            }
            if (attemptDuration.isZero() || attemptDuration.isNegative()) {
                throw new IllegalArgumentException("attemptDuration must be positive");
            }
            if (minimumTradingDays < 0) {
                throw new IllegalArgumentException("minimumTradingDays cannot be negative");
            }
        }
    }

    public enum Outcome {
        /** Profit target reached, with the minimum trading days satisfied. */
        PASSED,
        /**
         * Profit target reached too fast to satisfy the minimum-trading-days rule. Not a pass as the
         * rules are written, and not a failure either - a real trader would keep trading at minimal
         * size until the day count was met. Reported on its own so a "pass" driven by two lucky
         * trades is never silently counted as one.
         */
        TARGET_HIT_BUT_TOO_FEW_TRADING_DAYS,
        FAILED_MAX_LOSS,
        FAILED_DAILY_LOSS,
        /** Window ended with the account alive but the target not reached. */
        UNRESOLVED
    }

    /**
     * @param barsInWindow how many bars this attempt was offered, which is what a trade rate has to
     *                     be divided by. Attempts can differ here even at a fixed duration, because
     *                     the data has gaps.
     * @param closedTrades every trade the attempt closed, kept so a matched control can read its
     *                     rate and stop geometry off the strategy rather than having them chosen.
     */
    public record Attempt(Instant startInclusive, Instant endExclusive, Outcome outcome,
                          BigDecimal netProfit, BigDecimal worstDrawdown, int trades,
                          int tradingDays, long calendarDaysToResolution,
                          int barsBeforeFirstTrade, int barsInWindow, List<Trade> closedTrades) {
    }

    public record Summary(String label, int attempts, Map<Outcome, Integer> counts,
                          BigDecimal passRate, BigDecimal failRate,
                          Optional<Long> medianCalendarDaysToPass,
                          BigDecimal medianWorstDrawdown, BigDecimal p90WorstDrawdown,
                          BigDecimal maxWorstDrawdown, int medianTrades, int medianTradingDays,
                          List<Attempt> allAttempts) {

        @Override
        public String toString() {
            StringBuilder text = new StringBuilder();
            text.append("%s: attempts=%d%n".formatted(label, attempts));
            text.append("  P(pass)=%s%%  P(any fail)=%s%%%n".formatted(
                    percent(passRate), percent(failRate)));
            for (Outcome outcome : Outcome.values()) {
                int count = counts.getOrDefault(outcome, 0);
                text.append("    %-38s %4d  (%s%%)%n".formatted(outcome, count,
                        percent(share(count, attempts))));
            }
            text.append("  calendar days to pass (median): %s%n".formatted(
                    medianCalendarDaysToPass.map(String::valueOf).orElse("n/a - no passes")));
            text.append("  worst drawdown: median=%s  p90=%s  max=%s%n".formatted(
                    plain(medianWorstDrawdown), plain(p90WorstDrawdown), plain(maxWorstDrawdown)));
            text.append("  median trades per attempt=%d, median trading days=%d%n".formatted(
                    medianTrades, medianTradingDays));
            return text.toString();
        }
    }

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final BacktestEngine.BacktestConfig engineConfig;
    private final HarnessConfig harnessConfig;

    public ChallengeHarness(BacktestEngine.BacktestConfig engineConfig, HarnessConfig harnessConfig) {
        this.engineConfig = engineConfig;
        this.harnessConfig = harnessConfig;
    }

    public Summary run(String label, Supplier<Strategy> strategySupplier,
                       List<BacktestEngine.BacktestBar> bars, List<FundingRate> funding,
                       List<Kline> minuteCandles) {
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("Challenge harness requires at least one bar");
        }
        List<Attempt> attempts = new ArrayList<>();
        for (Instant start : startDates(bars)) {
            Instant end = start.plus(harnessConfig.attemptDuration());
            List<BacktestEngine.BacktestBar> windowBars = bars.stream()
                    .filter(bar -> !bar.candle().openTime().isBefore(start)
                            && bar.candle().openTime().isBefore(end))
                    .toList();
            if (windowBars.isEmpty()) {
                continue;
            }
            List<FundingRate> windowFunding = funding.stream()
                    .filter(rate -> !rate.fundingTime().isBefore(start)
                            && rate.fundingTime().isBefore(end))
                    .toList();
            List<Kline> windowMinutes = minuteCandles.stream()
                    .filter(candle -> !candle.openTime().isBefore(start)
                            && candle.openTime().isBefore(end))
                    .toList();
            attempts.add(evaluate(start, end, strategySupplier.get(),
                    windowBars, windowFunding, windowMinutes));
        }
        return summarise(label, attempts);
    }

    /**
     * Start dates that still have a full {@code attemptDuration} of data behind them. A start date
     * closer to the end of the data would produce a truncated attempt, and a truncated attempt is
     * biased toward UNRESOLVED - it simply ran out of room rather than reaching any verdict.
     */
    private List<Instant> startDates(List<BacktestEngine.BacktestBar> bars) {
        // Kline close times are inclusive of the bar's final millisecond - verified against
        // binance_perp_kline, where a 15m bar spans 899.999 seconds - so the exclusive end of the
        // data is one millisecond after the last close. Comparing against the raw close time would
        // discard the final window even when the data covers it exactly.
        Instant historyEnd = bars.getLast().candle().closeTime().plusMillis(1);
        List<Instant> starts = new ArrayList<>();
        Instant start = bars.getFirst().candle().openTime();
        while (!start.plus(harnessConfig.attemptDuration()).isAfter(historyEnd)) {
            starts.add(start);
            start = start.plus(harnessConfig.startSpacing());
        }
        return starts;
    }

    private Attempt evaluate(Instant start, Instant end, Strategy strategy,
                             List<BacktestEngine.BacktestBar> windowBars,
                             List<FundingRate> windowFunding, List<Kline> windowMinutes) {
        BacktestEngine.BacktestResult result = new BacktestEngine(engineConfig)
                .run(strategy, windowBars, windowFunding, windowMinutes);
        Account account = result.account();
        List<Trade> trades = account.closedTrades();
        int tradingDays = distinctTradingDays(trades);
        Outcome outcome = classify(result.terminationReason(), tradingDays);
        Instant resolvedAt = trades.isEmpty() || outcome == Outcome.UNRESOLVED
                ? end : trades.getLast().exitTime();
        return new Attempt(start, end, outcome,
                account.balance().subtract(account.initialBalance(), MC),
                worstDrawdown(account), trades.size(), tradingDays,
                Duration.between(start, resolvedAt).toDays(),
                barsBeforeFirstTrade(windowBars, trades), windowBars.size(), trades);
    }

    private Outcome classify(Optional<PropRuleEngine.Violation> termination, int tradingDays) {
        if (termination.isEmpty()) {
            return Outcome.UNRESOLVED;
        }
        return switch (termination.get()) {
            case MAX_DRAWDOWN -> Outcome.FAILED_MAX_LOSS;
            case DAILY_LOSS -> Outcome.FAILED_DAILY_LOSS;
            case PROFIT_TARGET_REACHED -> tradingDays >= harnessConfig.minimumTradingDays()
                    ? Outcome.PASSED
                    : Outcome.TARGET_HIT_BUT_TOO_FEW_TRADING_DAYS;
        };
    }

    /**
     * Distinct UTC dates carrying an entry. The firm's written terms do not define what marks a day
     * as traded; counting entries is the stricter reading, since a position opened one day and
     * closed the next would count twice under an entry-or-exit rule and once here.
     */
    private static int distinctTradingDays(List<Trade> trades) {
        Set<java.time.LocalDate> days = new HashSet<>();
        trades.forEach(trade -> days.add(trade.entryTime().atZone(ZoneOffset.UTC).toLocalDate()));
        return days.size();
    }

    /**
     * Largest shortfall below the starting balance, matching the firm's static Maximum Loss basis.
     * An account that runs up and gives back stays at zero here as long as it never dips below where
     * it began - the same distinction {@link PropRuleEngine} draws.
     */
    private static BigDecimal worstDrawdown(Account account) {
        BigDecimal initial = account.initialBalance();
        BigDecimal worst = BigDecimal.ZERO;
        for (BigDecimal equity : account.equityCurve()) {
            worst = worst.max(initial.subtract(equity, MC));
        }
        return worst;
    }

    private static int barsBeforeFirstTrade(List<BacktestEngine.BacktestBar> windowBars,
                                            List<Trade> trades) {
        if (trades.isEmpty()) {
            return windowBars.size();
        }
        Instant firstEntry = trades.getFirst().entryTime();
        for (int index = 0; index < windowBars.size(); index++) {
            if (!windowBars.get(index).candle().openTime().isBefore(firstEntry)) {
                return index;
            }
        }
        return windowBars.size();
    }

    private static Summary summarise(String label, List<Attempt> attempts) {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        attempts.forEach(attempt -> counts.merge(attempt.outcome(), 1, Integer::sum));
        int total = attempts.size();
        int passes = counts.getOrDefault(Outcome.PASSED, 0);
        int failures = counts.getOrDefault(Outcome.FAILED_MAX_LOSS, 0)
                + counts.getOrDefault(Outcome.FAILED_DAILY_LOSS, 0);
        List<Long> daysToPass = attempts.stream()
                .filter(attempt -> attempt.outcome() == Outcome.PASSED)
                .map(Attempt::calendarDaysToResolution).sorted().toList();
        List<BigDecimal> drawdowns = attempts.stream().map(Attempt::worstDrawdown).sorted().toList();
        List<Integer> tradeCounts = attempts.stream().map(Attempt::trades).sorted().toList();
        List<Integer> tradingDays = attempts.stream().map(Attempt::tradingDays).sorted().toList();
        return new Summary(label, total, counts,
                share(passes, total), share(failures, total),
                daysToPass.isEmpty() ? Optional.empty() : Optional.of(percentile(daysToPass, 50)),
                drawdowns.isEmpty() ? BigDecimal.ZERO : percentile(drawdowns, 50),
                drawdowns.isEmpty() ? BigDecimal.ZERO : percentile(drawdowns, 90),
                drawdowns.isEmpty() ? BigDecimal.ZERO : drawdowns.getLast(),
                tradeCounts.isEmpty() ? 0 : percentile(tradeCounts, 50),
                tradingDays.isEmpty() ? 0 : percentile(tradingDays, 50),
                attempts);
    }

    private static <T> T percentile(List<T> sortedValues, int percentile) {
        int index = (int) Math.round((percentile / 100.0) * (sortedValues.size() - 1));
        return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
    }

    private static BigDecimal share(int count, int total) {
        return total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(count).divide(BigDecimal.valueOf(total), MC);
    }

    private static String percent(BigDecimal fraction) {
        return fraction.multiply(BigDecimal.valueOf(100), MC)
                .setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
