package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.VolumeProfileBin;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VolumeProfileFeatureAssemblerTest {
    @Test
    void exactBaseProfileExcludesCurrentBreakoutBucket() {
        Instant zero = Instant.parse("2024-01-01T00:00:00Z");
        List<VolumeProfileBin> bins = List.of(
                bin(zero, "100", "100"),
                bin(zero.plusSeconds(900), "110", "50"),
                bin(zero.plusSeconds(1800), "200", "10000"));
        Instant breakout = zero.plusSeconds(1800);
        FeatureSnapshot technical = new FeatureSnapshot(breakout, breakout.plusSeconds(899),
                breakout.plusSeconds(900), Map.of(FeatureKey.close(), new BigDecimal("205")));

        FeatureSnapshot result = new VolumeProfileFeatureAssembler().mergeExactBase(
                List.of(technical), bins, 2, new BigDecimal("0.50")).getFirst();

        assertEquals(0, result.require(FeatureKey.exactBasePoc(2))
                .compareTo(new BigDecimal("100")));
    }

    private static VolumeProfileBin bin(Instant time, String price, String quote) {
        return new VolumeProfileBin("BTCUSDT", time, 15, new BigDecimal("10"),
                new BigDecimal(price), 1, BigDecimal.ONE, new BigDecimal(quote),
                new BigDecimal(quote), BigDecimal.ZERO);
    }
}
