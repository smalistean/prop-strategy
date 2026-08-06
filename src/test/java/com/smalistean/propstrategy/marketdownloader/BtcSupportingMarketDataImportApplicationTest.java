package com.smalistean.propstrategy.marketdownloader;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BtcSupportingMarketDataImportApplicationTest {

    @Test
    void continuesImmediatelyBeforeFirstStatisticInPage() {
        Instant current = Instant.parse("2026-08-06T12:00:00Z");
        Instant first = Instant.parse("2026-08-04T18:25:00Z");

        assertEquals(first.minusMillis(1),
                BtcSupportingMarketDataImportApplication.previousCursor(current, first));
    }

    @Test
    void rejectsNonAdvancingPage() {
        Instant current = Instant.parse("2026-08-06T12:00:00Z");

        assertThrows(IllegalStateException.class,
                () -> BtcSupportingMarketDataImportApplication.previousCursor(current, current));
    }
}
