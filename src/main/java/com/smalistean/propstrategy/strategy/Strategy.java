package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;

import java.util.List;
import java.util.Set;

public interface Strategy {

    String name();

    Set<FeatureKey> requiredFeatures();

    StrategyDecision evaluate(List<FeatureSnapshot> history, int index, PositionView position);
}
