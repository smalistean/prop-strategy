package com.smalistean.propstrategy.marketdownloader;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KlineIntervalTest {

    @Test
    void alignsTimestampsToIntervalBoundaries() {
        Instant value = Instant.parse("2026-08-06T12:17:42.123Z");

        assertEquals(Instant.parse("2026-08-06T12:17:00Z"), KlineInterval.ONE_MINUTE.floor(value));
        assertEquals(Instant.parse("2026-08-06T12:15:00Z"), KlineInterval.FIVE_MINUTES.floor(value));
        assertEquals(Instant.parse("2026-08-06T12:15:00Z"), KlineInterval.FIFTEEN_MINUTES.floor(value));
        assertEquals(Instant.parse("2026-08-06T12:00:00Z"), KlineInterval.ONE_HOUR.floor(value));
    }

    @Test
    void rejectsUnsupportedIntervals() {
        assertThrows(IllegalArgumentException.class, () -> KlineInterval.fromCode("2m"));
    }
}
