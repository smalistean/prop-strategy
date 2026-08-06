package com.smalistean.propstrategy.marketdownloader;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

public enum KlineInterval {
    ONE_MINUTE("1m", Duration.ofMinutes(1)),
    FIVE_MINUTES("5m", Duration.ofMinutes(5)),
    FIFTEEN_MINUTES("15m", Duration.ofMinutes(15)),
    ONE_HOUR("1h", Duration.ofHours(1));

    private final String code;
    private final Duration duration;

    KlineInterval(String code, Duration duration) {
        this.code = code;
        this.duration = duration;
    }

    public String code() {
        return code;
    }

    public Duration duration() {
        return duration;
    }

    public Instant floor(Instant instant) {
        long durationMillis = duration.toMillis();
        return Instant.ofEpochMilli(Math.floorDiv(instant.toEpochMilli(), durationMillis) * durationMillis);
    }

    public static KlineInterval fromCode(String code) {
        return Arrays.stream(values())
                .filter(interval -> interval.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported kline interval: " + code));
    }
}
