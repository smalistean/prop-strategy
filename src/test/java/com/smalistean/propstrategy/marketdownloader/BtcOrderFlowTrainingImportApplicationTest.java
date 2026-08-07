package com.smalistean.propstrategy.marketdownloader;

import org.junit.jupiter.api.Test;

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
}
