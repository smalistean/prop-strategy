package com.smalistean.propstrategy.marketdownloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoricalVolumeProfileReaderTest {

    @TempDir Path temporaryDirectory;

    @Test
    void buildsPriceBinsAndPreservesAggressorDirection() throws IOException {
        Path archive = temporaryDirectory.resolve("trades.zip");
        String csv = "agg_trade_id,price,quantity,first_trade_id,last_trade_id,transact_time,is_buyer_maker\n"
                + "1,100.50,2,1,1,1704067200000,false\n"
                + "2,109.99,1,2,2,1704067201000,true\n"
                + "3,110.00,3,3,3,1704067202000,false\n"
                + "4,100.00,9,4,4,1704153600000,false\n";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("trades.csv"));
            zip.write(csv.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        HistoricalVolumeProfileReader.Result result = new HistoricalVolumeProfileReader().read(
                List.of(archive), Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z"), new java.math.BigDecimal("10"));

        assertEquals(3, result.includedRows());
        assertEquals(2, result.levels().size());
        var first = result.levels().getFirst();
        assertEquals(0, first.priceFrom().compareTo(new java.math.BigDecimal("100")));
        assertEquals(0, first.aggressiveBuyQuote().compareTo(new java.math.BigDecimal("201.00")));
        assertEquals(0, first.aggressiveSellQuote().compareTo(new java.math.BigDecimal("109.99")));
        assertEquals(0, result.pointOfControl().priceFrom()
                .compareTo(new java.math.BigDecimal("110")));
    }

    @Test
    void selectsOnlyArchivesOverlappingRequestedPeriod() {
        Instant start = Instant.parse("2023-08-07T00:00:00Z");
        Instant end = Instant.parse("2023-09-01T00:00:00Z");
        assertTrue(HistoricalVolumeProfileApplication.overlaps(
                Path.of("BTCUSDT-aggTrades-2023-08.zip"), start, end));
        assertFalse(HistoricalVolumeProfileApplication.overlaps(
                Path.of("BTCUSDT-aggTrades-2023-09.zip"), start, end));
        assertTrue(HistoricalVolumeProfileApplication.overlaps(
                Path.of("BTCUSDT-aggTrades-2023-08-07.zip"), start, end));
    }
}
