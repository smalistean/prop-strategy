package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.VolumeProfileBin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RollingVolumeProfileGeneratorTest {
    @Test
    void profileAtTimeUsesOnlyEarlierBucketsAndMergesStrongNeighbors() {
        Instant zero = Instant.parse("2024-01-01T00:00:00Z");
        List<VolumeProfileBin> bins = List.of(
                bin(zero, "100", "100", "60", "40"),
                bin(zero, "110", "60", "20", "40"),
                bin(zero.plusSeconds(900), "200", "1000", "1000", "0"),
                bin(zero.plusSeconds(1800), "100", "20", "20", "0"));

        var profiles = new RollingVolumeProfileGenerator().generate(
                bins, List.of(2), new BigDecimal("0.50")).get(2);

        var atSecondBucket = profiles.getFirst();
        assertEquals(zero.plusSeconds(900), atSecondBucket.asOf());
        assertEquals(0, atSecondBucket.pocFrom().compareTo(new BigDecimal("100")));
        assertEquals(0, atSecondBucket.zoneTo().compareTo(new BigDecimal("120")));
        assertEquals(0, atSecondBucket.zoneDeltaQuote().compareTo(BigDecimal.ZERO));
        var atThirdBucket = profiles.get(1);
        assertEquals(0, atThirdBucket.pocFrom().compareTo(new BigDecimal("200")));
    }

    @Test
    void evictsBinsOutsideLookback() {
        Instant zero = Instant.parse("2024-01-01T00:00:00Z");
        List<VolumeProfileBin> bins = List.of(
                bin(zero, "100", "500", "500", "0"),
                bin(zero.plusSeconds(900), "200", "100", "100", "0"),
                bin(zero.plusSeconds(2700), "300", "1", "1", "0"));
        var last = new RollingVolumeProfileGenerator().generate(
                bins, List.of(2), BigDecimal.ONE).get(2).getLast();
        assertEquals(0, last.pocFrom().compareTo(new BigDecimal("200")));
    }

    private static VolumeProfileBin bin(Instant time, String price, String quote,
                                        String buy, String sell) {
        return new VolumeProfileBin("BTCUSDT", time, 15, new BigDecimal("10"),
                new BigDecimal(price), 1, BigDecimal.ONE, new BigDecimal(quote),
                new BigDecimal(buy), new BigDecimal(sell));
    }
}
