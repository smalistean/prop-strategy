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
            ExecutionConfig execution,
            PropRuleEngine.PropRules propRules,
            ExitConfig exits
    ) {
        public BacktestConfig(BigDecimal initialBalance, BigDecimal riskFraction,
                              BigDecimal maxLeverage, ExecutionConfig execution,
                              PropRuleEngine.PropRules propRules) {
            this(initialBalance, riskFraction, maxLeverage, execution, propRules, ExitConfig.baseline());
        }
        public BacktestConfig {
            if (initialBalance.signum() <= 0 || riskFraction.signum() <= 0
                    || riskFraction.compareTo(BigDecimal.ONE) > 0
                    || maxLeverage.signum() <= 0) {
                throw new IllegalArgumentException("Invalid backtest balance/risk configuration");
            }
        }
    }

    public record ExitConfig(boolean partialEnabled, BigDecimal partialTriggerR,
                             BigDecimal partialFraction, boolean trailingEnabled,
                             BigDecimal trailingTriggerR, BigDecimal trailingDistanceR,
                             boolean lackOfProgressEnabled, int lackOfProgressBars,
                             BigDecimal minimumProgressR) {
        public ExitConfig {
            if (partialTriggerR.signum() <= 0 || partialFraction.signum() <= 0
                    || partialFraction.compareTo(BigDecimal.ONE) >= 0
                    || trailingTriggerR.signum() <= 0 || trailingDistanceR.signum() <= 0
                    || lackOfProgressBars <= 0 || minimumProgressR.signum() < 0) {
                throw new IllegalArgumentException("Invalid exit configuration");
            }
        }

        public static ExitConfig baseline() {
            return new ExitConfig(false, BigDecimal.ONE, new BigDecimal("0.5"),
                    false, BigDecimal.ONE, BigDecimal.ONE, false, 8,
                    new BigDecimal("0.25"));
        }
    }

    public record ExecutionConfig(
            boolean makerEnabled,
            BigDecimal makerFeeBps,
            BigDecimal takerFeeBps,
            BigDecimal takerSlippageBps,
            BigDecimal makerOffsetBps,
            int makerOrderLifetimeMinutes,
            boolean strategyExitTakerFallback,
            boolean breakEvenEnabled,
            BigDecimal breakEvenTriggerRiskMultiple
    ) {
        public ExecutionConfig {
            if (makerFeeBps.signum() < 0 || takerFeeBps.signum() < 0
                    || takerSlippageBps.signum() < 0 || makerOffsetBps.signum() < 0
                    || makerOrderLifetimeMinutes <= 0
                    || breakEvenTriggerRiskMultiple.signum() <= 0) {
                throw new IllegalArgumentException("Invalid execution configuration");
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
            Optional<PropRuleEngine.Violation> terminationReason,
            ExecutionStats executionStats
    ) {
    }

    public record ExecutionStats(int makerEntryOrders, int makerEntryFills,
                                 int expiredMakerEntries, int makerExitOrders,
                                 int makerExitFills, int takerExitFallbacks) {
    }

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private final BacktestConfig config;

    public BacktestEngine(BacktestConfig config) {
        this.config = config;
    }

    public BacktestResult run(Strategy strategy, List<BacktestBar> bars,
                              List<FundingRate> fundingRates) {
        return run(strategy, bars, fundingRates, List.of());
    }

    public BacktestResult run(Strategy strategy, List<BacktestBar> bars,
                              List<FundingRate> fundingRates, List<Kline> minuteCandles) {
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("Backtest requires at least one bar");
        }
        if (config.execution().makerEnabled() && minuteCandles.isEmpty()) {
            throw new IllegalArgumentException("Maker execution requires 1m candles");
        }
        Account account = new Account(config.initialBalance());
        PositionSizer sizer = new PositionSizer(config.riskFraction());
        ExecutionModel execution = new ExecutionModel(config.execution().takerSlippageBps(),
                config.execution().takerFeeBps(), config.execution().makerFeeBps());
        PropRuleEngine propRules = new PropRuleEngine(config.propRules());
        List<FeatureSnapshot> history = bars.stream().map(BacktestBar::features).toList();
        StrategyDecision pending = StrategyDecision.hold();
        Optional<PropRuleEngine.Violation> termination = Optional.empty();
        int fundingIndex = 0;
        int minuteIndex = 0;
        ExecutionTracker tracker = new ExecutionTracker();

        for (int index = 0; index < bars.size(); index++) {
            BacktestBar frame = bars.get(index);
            Kline bar = frame.candle();

            while (minuteIndex < minuteCandles.size()
                    && minuteCandles.get(minuteIndex).openTime().isBefore(bar.openTime())) {
                minuteIndex++;
            }
            int minuteEnd = minuteIndex;
            while (minuteEnd < minuteCandles.size()
                    && !minuteCandles.get(minuteEnd).openTime().isAfter(bar.closeTime())) {
                minuteEnd++;
            }
            List<Kline> withinBar = minuteCandles.subList(minuteIndex, minuteEnd);

            executePending(pending, account, sizer, execution, bar, withinBar, tracker);
            applyFundingThrough(account, fundingRates, bar, fundingIndex);
            while (fundingIndex < fundingRates.size()
                    && !fundingRates.get(fundingIndex).fundingTime().isAfter(bar.closeTime())) {
                fundingIndex++;
            }
            executeProtectiveOrders(account, execution, bar, withinBar);
            account.incrementBarsHeld();
            executeLackOfProgressExit(account, execution, bar);
            BigDecimal equity = account.markToMarket(bar.close());

            PropRuleEngine.RuleCheckResult ruleCheck = propRules.check(account, equity, bar.closeTime());
            if (!ruleCheck.passed()) {
                if (account.hasOpenPosition()) {
                    closeAtTaker(account, execution, bar.closeTime(), bar.close(), "prop rule termination");
                }
                termination = ruleCheck.violation();
                break;
            }

            pending = strategy.evaluate(history, index, account.positionView());
            minuteIndex = minuteEnd;
        }

        if (account.hasOpenPosition()) {
            Kline last = bars.getLast().candle();
            closeAtTaker(account, execution, last.closeTime(), last.close(), "end of data");
        }
        return new BacktestResult(account, termination, tracker.snapshot());
    }

    private void executePending(StrategyDecision pending, Account account,
                                PositionSizer sizer, ExecutionModel execution, Kline bar,
                                List<Kline> minuteCandles, ExecutionTracker tracker) {
        if (pending instanceof StrategyDecision.Exit exit && account.hasOpenPosition()) {
            executeExit(account, execution, bar, minuteCandles, exit.reason(), tracker);
            return;
        }
        if (!(pending instanceof StrategyDecision.Enter)
                && !(pending instanceof StrategyDecision.EnterAtLevels)
                || account.hasOpenPosition()) {
            return;
        }

        Side side = pending instanceof StrategyDecision.Enter entry
                ? entry.side() : ((StrategyDecision.EnterAtLevels) pending).side();
        boolean buy = side == Side.LONG;
        if (config.execution().makerEnabled()) {
            tracker.makerEntryOrders++;
        }
        MakerFill makerFill = config.execution().makerEnabled()
                ? findMakerFill(bar, minuteCandles, buy) : null;
        if (config.execution().makerEnabled() && makerFill == null) {
            tracker.expiredMakerEntries++;
            return;
        }
        if (makerFill != null) {
            tracker.makerEntryFills++;
        }
        BigDecimal entryPrice = makerFill == null ? bar.open() : makerFill.price();
        ExecutionModel.Fill unitFill = makerFill == null
                ? execution.takerFill(entryPrice, buy, BigDecimal.ONE)
                : execution.makerFill(entryPrice, BigDecimal.ONE);
        BigDecimal stop = pending instanceof StrategyDecision.Enter entry
                ? side == Side.LONG
                    ? unitFill.fillPrice().subtract(entry.stopDistance(), MC)
                    : unitFill.fillPrice().add(entry.stopDistance(), MC)
                : ((StrategyDecision.EnterAtLevels) pending).stopPrice();
        BigDecimal target = pending instanceof StrategyDecision.Enter entry
                ? side == Side.LONG
                    ? unitFill.fillPrice().add(entry.targetDistance(), MC)
                    : unitFill.fillPrice().subtract(entry.targetDistance(), MC)
                : ((StrategyDecision.EnterAtLevels) pending).targetPrice();
        if (stop.signum() <= 0 || target.signum() <= 0
                || side == Side.LONG && (stop.compareTo(unitFill.fillPrice()) >= 0
                    || target.compareTo(unitFill.fillPrice()) <= 0)
                || side == Side.SHORT && (stop.compareTo(unitFill.fillPrice()) <= 0
                    || target.compareTo(unitFill.fillPrice()) >= 0)) {
            return;
        }
        BigDecimal quantity = sizer.size(account.balance(), unitFill.fillPrice(),
                stop);
        BigDecimal maximumQuantity = account.balance().multiply(config.maxLeverage(), MC)
                .divide(unitFill.fillPrice(), MC);
        quantity = quantity.min(maximumQuantity);
        if (quantity.signum() <= 0) {
            return;
        }
        ExecutionModel.Fill fill = makerFill == null
                ? execution.takerFill(entryPrice, buy, quantity)
                : execution.makerFill(entryPrice, quantity);
        account.open(makerFill == null ? bar.openTime() : makerFill.time(),
                side, quantity, stop, target, fill);
    }

    private void executeProtectiveOrders(Account account, ExecutionModel execution,
                                         Kline bar, List<Kline> minuteCandles) {
        if (!account.hasOpenPosition()) {
            return;
        }
        if (config.execution().makerEnabled()) {
            for (Kline minute : minuteCandles) {
                if (!account.hasOpenPosition()) {
                    return;
                }
                if (!minute.openTime().isAfter(account.positionView().entryTime())) {
                    continue;
                }
                executeProtectiveMinute(account, execution, minute);
            }
            return;
        }
        if (account.side() == Side.LONG) {
            if (bar.low().compareTo(account.stopPrice()) <= 0) {
                BigDecimal reference = bar.open().compareTo(account.stopPrice()) < 0
                        ? bar.open() : account.stopPrice();
                closeAtTaker(account, execution, bar.closeTime(), reference, "stop loss");
            } else if (bar.high().compareTo(account.targetPrice()) >= 0) {
                closeAtTaker(account, execution, bar.closeTime(), account.targetPrice(), "take profit");
            }
        } else if (bar.high().compareTo(account.stopPrice()) >= 0) {
            BigDecimal reference = bar.open().compareTo(account.stopPrice()) > 0
                    ? bar.open() : account.stopPrice();
            closeAtTaker(account, execution, bar.closeTime(), reference, "stop loss");
        } else if (bar.low().compareTo(account.targetPrice()) <= 0) {
            closeAtTaker(account, execution, bar.closeTime(), account.targetPrice(), "take profit");
        }
    }

    private void executeProtectiveMinute(Account account, ExecutionModel execution,
                                         Kline minute) {
        if (account.side() == Side.LONG) {
            if (minute.low().compareTo(account.stopPrice()) <= 0) {
                BigDecimal reference = minute.open().compareTo(account.stopPrice()) < 0
                        ? minute.open() : account.stopPrice();
                closeAtTaker(account, execution, minute.closeTime(), reference,
                        stopReason(account));
            } else if (shouldTakePartial(account, minute.high())) {
                closePartialAtMaker(account, execution, minute.closeTime(), partialPrice(account));
                account.markPartialProfitTaken();
                updateTrailingStop(account, minute.high());
            } else if (minute.high().compareTo(account.targetPrice()) > 0) {
                closeAtMaker(account, execution, minute.closeTime(), account.targetPrice(), "take profit");
            } else if (shouldActivateBreakEven(account, minute.high())) {
                account.activateBreakEven(breakEvenStop(account));
                if (minute.low().compareTo(account.stopPrice()) <= 0) {
                    closeAtTaker(account, execution, minute.closeTime(), account.stopPrice(),
                            "break-even stop");
                }
            } else {
                updateTrailingStop(account, minute.high());
            }
        } else if (minute.high().compareTo(account.stopPrice()) >= 0) {
            BigDecimal reference = minute.open().compareTo(account.stopPrice()) > 0
                    ? minute.open() : account.stopPrice();
            closeAtTaker(account, execution, minute.closeTime(), reference,
                    stopReason(account));
        } else if (shouldTakePartial(account, minute.low())) {
            closePartialAtMaker(account, execution, minute.closeTime(), partialPrice(account));
            account.markPartialProfitTaken();
            updateTrailingStop(account, minute.low());
        } else if (minute.low().compareTo(account.targetPrice()) < 0) {
            closeAtMaker(account, execution, minute.closeTime(), account.targetPrice(), "take profit");
        } else if (shouldActivateBreakEven(account, minute.low())) {
            account.activateBreakEven(breakEvenStop(account));
            if (minute.high().compareTo(account.stopPrice()) >= 0) {
                closeAtTaker(account, execution, minute.closeTime(), account.stopPrice(),
                        "break-even stop");
            }
        } else {
            updateTrailingStop(account, minute.low());
        }
    }

    private boolean shouldTakePartial(Account account, BigDecimal favorablePrice) {
        if (!config.exits().partialEnabled() || account.partialProfitTaken()) {
            return false;
        }
        BigDecimal trigger = partialPrice(account);
        return account.side() == Side.LONG
                ? favorablePrice.compareTo(trigger) > 0
                : favorablePrice.compareTo(trigger) < 0;
    }

    private BigDecimal partialPrice(Account account) {
        BigDecimal distance = account.initialRiskDistance().multiply(config.exits().partialTriggerR(), MC);
        return account.side() == Side.LONG
                ? account.entryPrice().add(distance, MC)
                : account.entryPrice().subtract(distance, MC);
    }

    private void updateTrailingStop(Account account, BigDecimal favorablePrice) {
        if (!config.exits().trailingEnabled()) {
            return;
        }
        BigDecimal activationDistance = account.initialRiskDistance()
                .multiply(config.exits().trailingTriggerR(), MC);
        BigDecimal activation = account.side() == Side.LONG
                ? account.entryPrice().add(activationDistance, MC)
                : account.entryPrice().subtract(activationDistance, MC);
        boolean active = account.side() == Side.LONG
                ? favorablePrice.compareTo(activation) >= 0
                : favorablePrice.compareTo(activation) <= 0;
        if (active) {
            BigDecimal trail = account.initialRiskDistance()
                    .multiply(config.exits().trailingDistanceR(), MC);
            account.improveStop(account.side() == Side.LONG
                    ? favorablePrice.subtract(trail, MC)
                    : favorablePrice.add(trail, MC));
        }
    }

    private void executeLackOfProgressExit(Account account, ExecutionModel execution, Kline bar) {
        if (!account.hasOpenPosition() || !config.exits().lackOfProgressEnabled()
                || account.positionView().barsHeld() < config.exits().lackOfProgressBars()) {
            return;
        }
        BigDecimal progress = account.side() == Side.LONG
                ? bar.close().subtract(account.entryPrice(), MC)
                : account.entryPrice().subtract(bar.close(), MC);
        BigDecimal required = account.initialRiskDistance()
                .multiply(config.exits().minimumProgressR(), MC);
        if (progress.compareTo(required) < 0) {
            closeAtTaker(account, execution, bar.closeTime(), bar.close(), "lack of progress");
        }
    }

    private void closePartialAtMaker(Account account, ExecutionModel execution,
                                     java.time.Instant time, BigDecimal price) {
        BigDecimal quantity = account.quantity().multiply(config.exits().partialFraction(), MC);
        account.closePartial(time, quantity, execution.makerFill(price, quantity),
                "partial take profit");
    }

    private boolean shouldActivateBreakEven(Account account, BigDecimal favorablePrice) {
        if (!config.execution().breakEvenEnabled() || account.breakEvenActive()) {
            return false;
        }
        BigDecimal triggerDistance = account.initialRiskDistance()
                .multiply(config.execution().breakEvenTriggerRiskMultiple(), MC);
        BigDecimal trigger = account.side() == Side.LONG
                ? account.entryPrice().add(triggerDistance, MC)
                : account.entryPrice().subtract(triggerDistance, MC);
        return account.side() == Side.LONG
                ? favorablePrice.compareTo(trigger) >= 0
                : favorablePrice.compareTo(trigger) <= 0;
    }

    private BigDecimal breakEvenStop(Account account) {
        BigDecimal feeRate = config.execution().takerFeeBps().divide(BPS, MC);
        BigDecimal slippageRate = config.execution().takerSlippageBps().divide(BPS, MC);
        BigDecimal entryCost = account.entryFeePerUnit();
        if (account.side() == Side.LONG) {
            BigDecimal requiredFill = account.entryPrice().add(entryCost, MC)
                    .divide(BigDecimal.ONE.subtract(feeRate, MC), MC);
            return requiredFill.divide(BigDecimal.ONE.subtract(slippageRate, MC), MC);
        }
        BigDecimal requiredFill = account.entryPrice().subtract(entryCost, MC)
                .divide(BigDecimal.ONE.add(feeRate, MC), MC);
        return requiredFill.divide(BigDecimal.ONE.add(slippageRate, MC), MC);
    }

    private static String stopReason(Account account) {
        return account.breakEvenActive() ? "break-even stop" : "stop loss";
    }

    private static void applyFundingThrough(Account account, List<FundingRate> rates,
                                            Kline bar, int startIndex) {
        for (int i = startIndex; i < rates.size(); i++) {
            FundingRate rate = rates.get(i);
            if (rate.fundingTime().isAfter(bar.closeTime())) {
                break;
            }
            if (!rate.fundingTime().isBefore(bar.openTime()) && account.hasOpenPosition()
                    && !rate.fundingTime().isBefore(account.positionView().entryTime())) {
                BigDecimal price = rate.markPrice() == null ? bar.close() : rate.markPrice();
                BigDecimal payment = price.multiply(account.quantity(), MC)
                        .multiply(rate.fundingRate(), MC);
                account.applyFunding(account.side() == Side.LONG ? payment.negate() : payment);
            }
        }
    }

    private MakerFill findMakerFill(Kline bar, List<Kline> minuteCandles, boolean buy) {
        BigDecimal limit = makerLimit(bar.open(), buy);
        java.time.Instant expires = bar.openTime()
                .plus(java.time.Duration.ofMinutes(config.execution().makerOrderLifetimeMinutes()));
        for (Kline minute : minuteCandles) {
            if (!minute.openTime().isBefore(expires)) {
                break;
            }
            boolean tradedThrough = buy
                    ? minute.low().compareTo(limit) < 0
                    : minute.high().compareTo(limit) > 0;
            if (tradedThrough) {
                return new MakerFill(minute.closeTime(), limit);
            }
        }
        return null;
    }

    private void executeExit(Account account, ExecutionModel execution, Kline bar,
                             List<Kline> minuteCandles, String reason, ExecutionTracker tracker) {
        if (!config.execution().makerEnabled()) {
            closeAtTaker(account, execution, bar.openTime(), bar.open(), reason);
            return;
        }
        tracker.makerExitOrders++;
        boolean buy = account.side() == Side.SHORT;
        BigDecimal limit = makerLimit(bar.open(), buy);
        java.time.Instant expires = bar.openTime()
                .plus(java.time.Duration.ofMinutes(config.execution().makerOrderLifetimeMinutes()));
        for (Kline minute : minuteCandles) {
            if (!minute.openTime().isBefore(expires)) {
                break;
            }
            if (minute.openTime().isAfter(account.positionView().entryTime())) {
                executeProtectiveMinute(account, execution, minute);
                if (!account.hasOpenPosition()) {
                    return;
                }
            }
            boolean tradedThrough = buy
                    ? minute.low().compareTo(limit) < 0
                    : minute.high().compareTo(limit) > 0;
            if (tradedThrough) {
                tracker.makerExitFills++;
                closeAtMaker(account, execution, minute.closeTime(), limit, reason);
                return;
            }
        }
        if (!config.execution().strategyExitTakerFallback()) {
            return;
        }
        tracker.takerExitFallbacks++;
        Kline fallback = minuteCandles.stream()
                .filter(minute -> !minute.openTime().isBefore(expires))
                .findFirst().orElse(null);
        if (fallback == null) {
            closeAtTaker(account, execution, bar.closeTime(), bar.close(), reason + " taker fallback");
        } else {
            closeAtTaker(account, execution, fallback.openTime(), fallback.open(),
                    reason + " taker fallback");
        }
    }

    private static void closeAtTaker(Account account, ExecutionModel execution,
                                     java.time.Instant time, BigDecimal referencePrice, String reason) {
        boolean buy = account.side() == Side.SHORT;
        ExecutionModel.Fill fill = execution.takerFill(referencePrice, buy, account.quantity());
        account.close(time, fill, reason);
    }

    private static void closeAtMaker(Account account, ExecutionModel execution,
                                     java.time.Instant time, BigDecimal limitPrice, String reason) {
        account.close(time, execution.makerFill(limitPrice, account.quantity()), reason);
    }

    private record MakerFill(java.time.Instant time, BigDecimal price) {
    }

    private BigDecimal makerLimit(BigDecimal reference, boolean buy) {
        BigDecimal offset = reference.multiply(config.execution().makerOffsetBps(), MC)
                .divide(BigDecimal.valueOf(10_000), MC);
        return buy ? reference.subtract(offset, MC) : reference.add(offset, MC);
    }

    private static final class ExecutionTracker {
        private int makerEntryOrders;
        private int makerEntryFills;
        private int expiredMakerEntries;
        private int makerExitOrders;
        private int makerExitFills;
        private int takerExitFallbacks;

        private ExecutionStats snapshot() {
            return new ExecutionStats(makerEntryOrders, makerEntryFills, expiredMakerEntries,
                    makerExitOrders, makerExitFills, takerExitFallbacks);
        }
    }
}
