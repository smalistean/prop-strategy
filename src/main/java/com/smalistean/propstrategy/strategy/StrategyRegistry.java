package com.smalistean.propstrategy.strategy;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class StrategyRegistry {

    private final Map<String, StrategyFactory> factories;

    public StrategyRegistry(Collection<StrategyFactory> factories) {
        this.factories = factories.stream().collect(Collectors.toUnmodifiableMap(
                StrategyFactory::type, Function.identity()));
    }

    public static StrategyRegistry defaults() {
        return new StrategyRegistry(java.util.List.of(
                new EmaPullbackStrategyFactory(),
                new DonchianBreakoutStrategyFactory(),
                new VolatilityCompressionBreakoutStrategyFactory(),
                new RsiAtrMeanReversionStrategyFactory(),
                new IntradayFlatMeanReversionStrategyFactory()));
    }

    public Strategy create(String type, StrategyParameters parameters) {
        StrategyFactory factory = factories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown strategy type '%s'; available: %s"
                    .formatted(type, factories.keySet()));
        }
        return factory.create(parameters);
    }
}
