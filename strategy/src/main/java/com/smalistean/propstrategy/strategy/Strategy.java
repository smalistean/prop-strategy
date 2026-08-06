package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.database.Kline;

import java.util.List;

public interface Strategy {

    String name();

    Signal evaluate(List<Kline> history, int index);
}
