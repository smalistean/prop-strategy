package com.smalistean.propstrategy.marketdownloader;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class BtcIncrementalSyncApplicationTest {

    @Test
    void startsImmediatelyAfterLatestStoredCandle() {
        Instant latest = Instant.parse("2026-08-06T10:15:00Z");

        Instant cursor = BtcIncrementalSyncApplication.nextCursor(
                Optional.of(latest), KlineInterval.FIFTEEN_MINUTES);

        assertEquals(Instant.parse("2026-08-06T10:30:00Z"), cursor);
    }

    @Test
    void refusesToChooseCursorForEmptyDatabase() {
        assertNull(BtcIncrementalSyncApplication.nextCursor(
                Optional.empty(), KlineInterval.ONE_MINUTE));
    }
}
