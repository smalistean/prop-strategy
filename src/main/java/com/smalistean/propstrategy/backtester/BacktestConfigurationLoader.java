package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.strategy.StrategyParameters;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class BacktestConfigurationLoader {

    public record LoadedConfiguration(
            String symbol,
            String interval,
            BacktestDataset dataset,
            BacktestEngine.BacktestConfig engine,
            String strategyType,
            StrategyParameters strategyParameters
    ) {
    }

    public LoadedConfiguration load(Path engineFile, Path strategyFile) {
        Properties engine = read(engineFile);
        Properties strategy = read(strategyFile);
        String timezone = required(engine, "prop.timezone");
        if (!"UTC".equals(timezone)) {
            throw new IllegalArgumentException("Only UTC prop-rule boundaries are currently supported");
        }
        BacktestDataset.Type datasetType = datasetType(engine);
        if (datasetType == BacktestDataset.Type.FINAL_TEST
                && !Boolean.getBoolean("confirmFinalTest")) {
            throw new IllegalStateException("FINAL_TEST is intentionally locked. Re-run with "
                    + "-DconfirmFinalTest=true only after the strategy and parameters are frozen.");
        }
        String datasetPrefix = "data." + switch (datasetType) {
            case TRAINING -> "training";
            case VALIDATION -> "validation";
            case FINAL_TEST -> "finalTest";
        };
        BacktestDataset dataset = new BacktestDataset(
                datasetType,
                date(engine, datasetPrefix + "Start"),
                date(engine, datasetPrefix + "End"));

        BacktestEngine.BacktestConfig backtestConfig = new BacktestEngine.BacktestConfig(
                decimal(engine, "account.initialBalance"),
                decimal(engine, "risk.fractionPerTrade"),
                decimal(engine, "risk.maxLeverage"),
                new BacktestEngine.ExecutionConfig(
                        bool(engine, "execution.makerEnabled"),
                        decimal(engine, "execution.makerFeeBps"),
                        decimal(engine, "execution.takerFeeBps"),
                        decimal(engine, "execution.takerSlippageBps"),
                        decimal(engine, "execution.makerOffsetBps"),
                        integer(engine, "execution.makerOrderLifetimeMinutes"),
                        bool(engine, "execution.strategyExitTakerFallback"),
                        bool(engine, "execution.breakEvenEnabled"),
                        decimal(engine, "execution.breakEvenTriggerRiskMultiple")),
                new PropRuleEngine.PropRules(
                        decimal(engine, "prop.maxTotalDrawdownPercent"),
                        decimal(engine, "prop.maxDailyLossPercent"),
                        decimal(engine, "prop.profitTargetPercent")));

        Map<String, String> parameters = new HashMap<>();
        for (String name : strategy.stringPropertyNames()) {
            if (name.startsWith("strategy.") && !name.equals("strategy.type")) {
                parameters.put(name.substring("strategy.".length()), strategy.getProperty(name));
            }
        }
        return new LoadedConfiguration(
                System.getProperty("marketSymbol", required(engine, "market.symbol")),
                required(engine, "market.interval"),
                dataset,
                backtestConfig,
                required(strategy, "strategy.type"),
                new StrategyParameters(parameters));
    }

    private static Properties read(Path path) {
        Properties result = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            result.load(reader);
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read configuration " + path, e);
        }
    }

    private static String required(Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing configuration property: " + name);
        }
        return value.trim();
    }

    private static BigDecimal decimal(Properties properties, String name) {
        try {
            return new BigDecimal(required(properties, name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Configuration property must be decimal: " + name, e);
        }
    }

    private static int integer(Properties properties, String name) {
        try {
            int value = Integer.parseInt(required(properties, name));
            if (value <= 0) {
                throw new NumberFormatException("not positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Configuration property must be a positive integer: "
                    + name, e);
        }
    }

    private static boolean bool(Properties properties, String name) {
        String value = required(properties, name);
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException("Configuration property must be true or false: " + name);
        }
        return Boolean.parseBoolean(value);
    }

    private static BacktestDataset.Type datasetType(Properties properties) {
        try {
            return BacktestDataset.Type.valueOf(System.getProperty(
                    "backtestDataset", required(properties, "backtest.dataset")));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("backtest.dataset must be TRAINING, VALIDATION, or FINAL_TEST", e);
        }
    }

    private static java.time.Instant date(Properties properties, String name) {
        try {
            return LocalDate.parse(required(properties, name)).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Configuration property must be YYYY-MM-DD: " + name, e);
        }
    }
}
