package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.backtester.BacktestBarAssembler;
import com.smalistean.propstrategy.backtester.BacktestConfigurationLoader;
import com.smalistean.propstrategy.backtester.BacktestEngine;
import com.smalistean.propstrategy.backtester.ChallengeHarness;
import com.smalistean.propstrategy.backtester.PropRuleEngine;
import com.smalistean.propstrategy.backtester.Trade;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.RandomEntryStrategy;
import com.smalistean.propstrategy.strategy.Strategy;
import com.smalistean.propstrategy.strategy.StrategyDecision;
import com.smalistean.propstrategy.strategy.StrategyRegistry;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Scores one strategy as a prop challenge, against a matched random-entry control.
 *
 * <p>Reports {@code P(pass)} over many simulated start dates, then the same figure for random
 * entries trading at the strategy's own observed rate with the strategy's own observed stop and
 * target geometry. The control is the point of the exercise: a pass rate only means something as a
 * margin over what indiscriminate trading achieves on the same instrument, in the same windows,
 * paying the same costs.
 */
public final class ChallengeHarnessApplication {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private ChallengeHarnessApplication() {
    }

    public static void main(String[] args) {
        Path engineFile = Path.of(System.getProperty(
                "engineConfig", "config/backtests/engine.properties"));
        Path strategyFile = Path.of(System.getProperty(
                "strategyConfig", "config/backtests/ema-pullback.properties"));
        BacktestConfigurationLoader.LoadedConfiguration loaded =
                new BacktestConfigurationLoader().load(engineFile, strategyFile);
        StrategyRegistry registry = StrategyRegistry.defaults();
        Supplier<Strategy> strategySupplier = () -> registry.create(
                loaded.strategyType(), loaded.strategyParameters());
        String marketSymbol = System.getProperty("marketSymbol", loaded.symbol())
                .trim().toUpperCase();
        Instant start = Instant.parse(System.getProperty("harnessStart", "2021-08-01T00:00:00Z"));
        Instant end = Instant.parse(System.getProperty("harnessEnd", "2026-08-01T00:00:00Z"));

        // The config's prop block is deliberately research-shaped (profit target disabled so runs
        // are not truncated). The challenge is the opposite: the target is the finish line. These
        // are the real Stage 1 figures as percentages of the 50,000 balance.
        BacktestEngine.BacktestConfig base = loaded.engine();
        BigDecimal balance = new BigDecimal(System.getProperty("challengeBalance", "50000"));
        BacktestEngine.BacktestConfig engineConfig = new BacktestEngine.BacktestConfig(
                balance,
                new BigDecimal(System.getProperty("riskFraction",
                        base.riskFraction().toPlainString())),
                base.maxLeverage(), base.execution(),
                new PropRuleEngine.PropRules(
                        percentOf(new BigDecimal(System.getProperty("maxTotalLoss", "5000")), balance),
                        percentOf(new BigDecimal(System.getProperty("maxDailyLoss", "2500")), balance),
                        percentOf(new BigDecimal(System.getProperty("profitTarget", "4000")), balance)),
                base.exits());
        ChallengeHarness.HarnessConfig harnessConfig = new ChallengeHarness.HarnessConfig(
                Duration.ofDays(Long.getLong("startSpacingDays", 14)),
                Duration.ofDays(Long.getLong("attemptDurationDays", 180)),
                Integer.getInteger("minimumTradingDays", 5));

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        BacktestBarAssembler.AssembledDataset dataset = BacktestBarAssembler.assemble(
                database, loaded.strategyType(), loaded.strategyParameters(), loaded.interval(),
                marketSymbol, strategySupplier.get(), base.execution().makerEnabled(), start, end);
        System.out.printf("Challenge harness: symbol=%s interval=%s window=[%s, %s) bars=%,d%n",
                marketSymbol, loaded.interval(), start, end, dataset.bars().size());
        System.out.printf("  balance=%s risk=%s/trade  limits: daily=%s total=%s target=%s%n",
                balance.toPlainString(), engineConfig.riskFraction().toPlainString(),
                System.getProperty("maxDailyLoss", "2500"),
                System.getProperty("maxTotalLoss", "5000"),
                System.getProperty("profitTarget", "4000"));
        System.out.printf("  attempts: every %s days, each lasting %s days, minimum %d trading days%n%n",
                harnessConfig.startSpacing().toDays(), harnessConfig.attemptDuration().toDays(),
                harnessConfig.minimumTradingDays());

        ChallengeHarness harness = new ChallengeHarness(engineConfig, harnessConfig);
        ChallengeHarness.Summary strategyResult = harness.run(loaded.strategyType(),
                strategySupplier, dataset.bars(), dataset.funding(), dataset.minuteCandles());
        System.out.println(strategyResult);
        if (Boolean.getBoolean("attemptDetail")) {
            strategyResult.allAttempts().stream().limit(Integer.getInteger("attemptDetailLimit", 10))
                    .forEach(attempt -> System.out.printf(
                            "  attempt %s %-38s net=%s worstDD=%s trades=%d bars=%d firstTradeAfter=%d%n",
                            attempt.startInclusive(), attempt.outcome(),
                            attempt.netProfit().setScale(2, RoundingMode.HALF_UP),
                            attempt.worstDrawdown().setScale(2, RoundingMode.HALF_UP),
                            attempt.trades(), attempt.barsInWindow(), attempt.barsBeforeFirstTrade()));
        }

        Geometry geometry = measure(strategyResult);
        if (geometry == null) {
            System.out.println("No trades were taken, so there is nothing to compare against a "
                    + "random control. Stopping here rather than printing a baseline that would "
                    + "be measuring a different strategy than the one under test.");
            return;
        }
        System.out.printf("Matched control geometry: entryRate=%.5f/bar, stop=%s, reward:risk=%s%n",
                geometry.entryProbabilityPerBar(), geometry.stopDistance().setScale(2, RoundingMode.HALF_UP),
                geometry.rewardRiskRatio().setScale(3, RoundingMode.HALF_UP));

        int atrPeriod = Integer.getInteger("controlAtrPeriod", 14);
        BacktestBarAssembler.AssembledDataset controlData = BacktestBarAssembler.assemble(
                database, "random-entry-control", loaded.strategyParameters(), loaded.interval(),
                marketSymbol, atrOnly(atrPeriod), base.execution().makerEnabled(), start, end);
        BigDecimal medianAtr = medianAtr(controlData.bars(), FeatureKey.atr(atrPeriod));
        if (medianAtr == null || medianAtr.signum() <= 0) {
            System.out.println("ATR unavailable for the control window; skipping the baseline.");
            return;
        }
        BigDecimal stopAtrMultiplier = geometry.stopDistance().divide(medianAtr, MC);
        System.out.printf("  medianATR(%d)=%s -> stopAtrMultiplier=%s%n%n", atrPeriod,
                medianAtr.setScale(2, RoundingMode.HALF_UP),
                stopAtrMultiplier.setScale(3, RoundingMode.HALF_UP));

        int seeds = Integer.getInteger("controlSeeds", 5);
        List<BigDecimal> controlPassRates = new ArrayList<>();
        for (int seed = 1; seed <= seeds; seed++) {
            long fixedSeed = seed;
            ChallengeHarness.Summary control = harness.run("random-entry seed=%d".formatted(seed),
                    () -> new RandomEntryStrategy(fixedSeed, geometry.entryProbabilityPerBar(),
                            stopAtrMultiplier, geometry.rewardRiskRatio(),
                            Integer.getInteger("controlMaxHoldingBars", 96), atrPeriod),
                    controlData.bars(), controlData.funding(), controlData.minuteCandles());
            controlPassRates.add(control.passRate());
            System.out.println(control);
        }

        BigDecimal bestControl = controlPassRates.stream().max(Comparator.naturalOrder())
                .orElse(BigDecimal.ZERO);
        System.out.println("=== Verdict ===");
        System.out.printf("strategy P(pass)=%s%%   best of %d random controls=%s%%%n",
                percent(strategyResult.passRate()), seeds, percent(bestControl));
        System.out.println(strategyResult.passRate().compareTo(bestControl) > 0
                ? "The strategy beat every random control. That is the minimum bar, not proof - "
                + "Phase 2 still has to clear two independent time cohorts with the best quarter "
                + "removed before this means anything."
                : "The strategy did NOT beat random entry at the same trade rate and geometry. "
                + "Its pass rate belongs to the payoff structure and the market, not to the "
                + "strategy, and ranking on it would be selecting noise.");
    }

    private record Geometry(double entryProbabilityPerBar, BigDecimal stopDistance,
                            BigDecimal rewardRiskRatio) {
    }

    /**
     * Reads the control's parameters off the strategy's own trades instead of choosing them.
     *
     * <p>A control that trades more often, or risks a different distance, is not a control - it is a
     * second strategy, and the comparison would then be measuring trade frequency rather than trade
     * quality. Stop distance comes from trades that actually stopped out and target distance from
     * trades that actually reached target, since those are the only exits whose price difference is
     * the configured level rather than an arbitrary mid-trade exit.
     */
    private static Geometry measure(ChallengeHarness.Summary summary) {
        List<Trade> trades = summary.allAttempts().stream()
                .flatMap(attempt -> attempt.closedTrades().stream()).toList();
        long bars = summary.allAttempts().stream()
                .mapToLong(ChallengeHarness.Attempt::barsInWindow).sum();
        if (trades.isEmpty() || bars == 0) {
            return null;
        }
        BigDecimal stopDistance = medianDistance(trades, "stop loss");
        BigDecimal targetDistance = medianDistance(trades, "take profit");
        if (stopDistance == null || stopDistance.signum() <= 0) {
            return null;
        }
        // A strategy that never reaches target leaves no measurable reward leg, so the control
        // trades the strategy's stop at 1:1 rather than inheriting a fabricated target.
        BigDecimal rewardRisk = targetDistance == null || targetDistance.signum() <= 0
                ? BigDecimal.ONE : targetDistance.divide(stopDistance, MC);
        return new Geometry(Math.min(1.0, (double) trades.size() / bars), stopDistance, rewardRisk);
    }

    private static BigDecimal medianDistance(List<Trade> trades, String exitReason) {
        List<BigDecimal> distances = trades.stream()
                .filter(trade -> trade.exitReason().equals(exitReason))
                .map(trade -> trade.exitPrice().subtract(trade.entryPrice(), MC).abs())
                .sorted().toList();
        return distances.isEmpty() ? null : distances.get(distances.size() / 2);
    }

    private static BigDecimal medianAtr(List<BacktestEngine.BacktestBar> bars, FeatureKey atr) {
        List<BigDecimal> values = bars.stream()
                .map(bar -> bar.features().values().get(atr))
                .filter(java.util.Objects::nonNull)
                .sorted().toList();
        return values.isEmpty() ? null : values.get(values.size() / 2);
    }

    private static Strategy atrOnly(int atrPeriod) {
        return new Strategy() {
            @Override
            public String name() {
                return "atr-only";
            }

            @Override
            public Set<FeatureKey> requiredFeatures() {
                return Set.of(FeatureKey.close(), FeatureKey.atr(atrPeriod));
            }

            @Override
            public StrategyDecision evaluate(List<FeatureSnapshot> history, int index,
                                             PositionView position) {
                return StrategyDecision.hold();
            }
        };
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal balance) {
        return amount.divide(balance, MC).multiply(BigDecimal.valueOf(100), MC);
    }

    private static String percent(BigDecimal fraction) {
        return fraction.multiply(BigDecimal.valueOf(100), MC)
                .setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
