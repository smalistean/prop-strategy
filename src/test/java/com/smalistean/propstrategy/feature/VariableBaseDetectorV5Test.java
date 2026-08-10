package com.smalistean.propstrategy.feature;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class VariableBaseDetectorV5Test {
    @Test void detectsHorizontalBaseAfterDistinctEntrance() {
        List<FeatureSnapshot> h = new ArrayList<>();
        for (int i = 0; i < 4; i++) h.add(snapshot(i, 96 + i));
        for (int i = 4; i < 16; i++) h.add(snapshot(i, i % 2 == 0 ? 100 : 101));
        h.add(snapshot(16, 104));
        var config = new VariableBaseDetector.Config(12, 12, new BigDecimal("0.5"),
                new BigDecimal("2"), new BigDecimal("0.5"), new BigDecimal("0.1"),
                BigDecimal.ONE, new BigDecimal("0.2"), new BigDecimal("0.25"));
        var candidates = new VariableBaseDetectorV5().detectCandidates(h, 16, FeatureKey.atr(14), config, 12);
        assertFalse(candidates.isEmpty());
        var base = candidates.get(0);
        assertEquals(12, base.bars());
        assertEquals(new BigDecimal("100"), base.low());
        assertEquals(new BigDecimal("101"), base.high());
    }

    private static FeatureSnapshot snapshot(int i, int price) {
        Instant time = Instant.EPOCH.plusSeconds(i * 900L);
        Map<FeatureKey, BigDecimal> v = new HashMap<>();
        BigDecimal p = BigDecimal.valueOf(price);
        v.put(FeatureKey.open(), p); v.put(FeatureKey.close(), p);
        v.put(FeatureKey.high(), p.add(new BigDecimal("0.1")));
        v.put(FeatureKey.low(), p.subtract(new BigDecimal("0.1")));
        v.put(FeatureKey.atr(14), BigDecimal.ONE);
        return new FeatureSnapshot(time, time.plusSeconds(899), time.plusSeconds(900), v);
    }
}
