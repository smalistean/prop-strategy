package com.smalistean.propstrategy.strategy;

import java.math.BigDecimal;
import java.util.Map;

public record StrategyParameters(Map<String, String> values) {

    public StrategyParameters {
        values = Map.copyOf(values);
    }

    public int requiredInt(String name) {
        try {
            return Integer.parseInt(required(name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Strategy parameter must be an integer: " + name, e);
        }
    }

    public BigDecimal requiredDecimal(String name) {
        try {
            return new BigDecimal(required(name));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Strategy parameter must be decimal: " + name, e);
        }
    }

    private String required(String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing strategy parameter: " + name);
        }
        return value;
    }
}
