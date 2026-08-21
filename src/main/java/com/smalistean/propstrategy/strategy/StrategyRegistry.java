package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.strategy.apollo.ApolloBasePocRetestStrategyFactory;
import com.smalistean.propstrategy.strategy.apollo.ApolloOrderedLiquiditySequenceStrategyFactory;
import com.smalistean.propstrategy.strategy.apollo.ApolloV4BasePocContinuationStrategyFactory;
import com.smalistean.propstrategy.strategy.apollo.ApolloV5BasePocContinuationStrategyFactory;
import com.smalistean.propstrategy.strategy.apollo.ApolloV5LiquidityLimitStrategyFactory;
import com.smalistean.propstrategy.strategy.apollo.ApolloVariableBasePocStrategyFactory;
import com.smalistean.propstrategy.strategy.gerchik.GerchikBounceStrategyFactory;
import com.smalistean.propstrategy.strategy.gerchik.GerchikBreakoutStrategyFactory;
import com.smalistean.propstrategy.strategy.gerchik.GerchikFalseBreakoutStrategyFactory;
import com.smalistean.propstrategy.strategy.gerchik.GerchikLevelStrategyFactory;

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
                new OrderFlowExhaustionStrategyFactory(),
                new MultiTimeframeFlatLongStrategyFactory(),
                new PassiveMakerMeanReversionStrategyFactory(),
                new StructuralChannelStrategyFactory(),
                new GerchikLevelStrategyFactory(),
                new GerchikFalseBreakoutStrategyFactory(),
                new GerchikBounceStrategyFactory(),
                new GerchikBreakoutStrategyFactory(),
                new VolumeProfileLevelStrategyFactory(),
                new ApolloBasePocRetestStrategyFactory(),
                new ApolloVariableBasePocStrategyFactory(),
                new ApolloV4BasePocContinuationStrategyFactory(),
                new ApolloV5BasePocContinuationStrategyFactory(),
                new ApolloV5LiquidityLimitStrategyFactory(),
                new ThreeLevelRangeStrategyFactory(),
                new IntradayFlatMeanReversionStrategyFactory(),
                new CrossSectionalLongPullbackStrategyFactory(),
                new LiquiditySweepReversalStrategyFactory(),
                new HigherTimeframeLiquiditySweepStrategyFactory(),
                new ApolloOrderedLiquiditySequenceStrategyFactory()));
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
