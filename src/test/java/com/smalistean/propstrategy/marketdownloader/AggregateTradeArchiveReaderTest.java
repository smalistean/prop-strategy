package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.AggregateTradeMinute;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AggregateTradeArchiveReaderTest {

    @TempDir Path directory;

    @Test
    void aggregatesAggressorDirectionNotionalBucketsAndIdGaps() throws Exception {
        Path archive = directory.resolve("sample.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8))) {
            zip.putNextEntry(new ZipEntry("sample.csv"));
            writer.write("agg_trade_id,price,quantity,first_trade_id,last_trade_id,transact_time,is_buyer_maker\n");
            writer.write("10,100,100,20,21,1722470400000,false\n");
            // Binance archives are not always globally ordered; a later minute may appear first.
            writer.write("13,100,10000,25,25,1722470460000,false\n");
            writer.write("12,100,1000,22,24,1722470401000,true\n");
        }
        AggregateTradeArchiveReader.Result result = new AggregateTradeArchiveReader().read(
                archive, "BTCUSDT", Instant.parse("2024-08-01T00:00:00Z"),
                Instant.parse("2024-08-01T00:02:00Z"));

        assertEquals(2, result.minutes().size());
        AggregateTradeMinute first = result.minutes().getFirst();
        assertEquals(0, first.aggressiveBuyQuote().compareTo(new BigDecimal("10000")));
        assertEquals(0, first.aggressiveSellQuote().compareTo(new BigDecimal("100000")));
        assertEquals(0, first.quoteDelta().compareTo(new BigDecimal("-90000")));
        assertEquals(1, first.aggregateTradeIdGapCount());
        assertEquals(1, first.large100kCount());
        assertEquals(1, result.gapCount());
        assertEquals(2, first.aggregateTradeCount());
    }
}
