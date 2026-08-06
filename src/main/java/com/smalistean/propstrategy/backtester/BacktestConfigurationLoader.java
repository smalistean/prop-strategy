package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.strategy.StrategyParameters;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class BacktestConfigurationLoader {

    public record LoadedConfiguration(
            String symbol,
            String interval,
            int candleLimit,
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

        BacktestEngine.BacktestConfig backtestConfig = new BacktestEngine.BacktestConfig(
                decimal(engine, "account.initialBalance"),
                decimal(engine, "risk.fractionPerTrade"),
                decimal(engine, "risk.maxLeverage"),
                decimal(engine, "execution.slippageBps"),
                decimal(engine, "execution.takerFeeBps"),
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
                required(engine, "market.symbol"),
                required(engine, "market.interval"),
                integer(engine, "market.candleLimit"),
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
}
