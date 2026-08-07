package com.smalistean.propstrategy.marketdownloader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HistoricalImportApplicationTest {

    @Test
    void parsesNormalizesAndDeduplicatesConfiguredSymbols() {
        assertEquals(List.of("SOLUSDT", "LINKUSDT"),
                HistoricalImportApplication.symbols(" solusdt, LINKUSDT,solusdt "));
    }

    @Test
    void rejectsNonUsdtSymbols() {
        assertThrows(IllegalArgumentException.class,
                () -> HistoricalImportApplication.symbols("BTCUSD"));
    }
}
