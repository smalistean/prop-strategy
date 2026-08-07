package com.smalistean.propstrategy.marketdownloader;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BtcOrderFlowTrainingImportApplicationTest {

    @Test
    void plansExactlyTwentyFourMonthsAndSixTailDaysInOrder() {
        var archives = BtcOrderFlowTrainingImportApplication.archives();
        assertEquals(30, archives.size());
        assertTrue(archives.getFirst().uri().toString().endsWith("BTCUSDT-aggTrades-2023-08.zip"));
        assertTrue(archives.get(23).uri().toString().endsWith("BTCUSDT-aggTrades-2025-07.zip"));
        assertTrue(archives.getLast().uri().toString().endsWith("BTCUSDT-aggTrades-2025-08-06.zip"));
    }

    @Test
    void plansBtcUsdcWholeMonthTrainingWindow() {
        var archives = BtcOrderFlowTrainingImportApplication.monthlyArchives(
                "BTCUSDC", Instant.parse("2024-02-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"));

        assertEquals(24, archives.size());
        assertTrue(archives.getFirst().uri().toString()
                .endsWith("BTCUSDC-aggTrades-2024-02.zip"));
        assertTrue(archives.getLast().uri().toString()
                .endsWith("BTCUSDC-aggTrades-2026-01.zip"));
    }
}
