package com.smalistean.propstrategy.strategy;

public interface StrategyFactory {

    String type();

    Strategy create(StrategyParameters parameters);
}
