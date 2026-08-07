package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketRegimeClassifierTest {

    private final MarketRegimeClassifier classifier =
            new MarketRegimeClassifier(1, new BigDecimal("2"));

    @Test
    void classifiesDirectionalAndFlatMovesFromCompletedHistory() {
        assertEquals(MarketRegime.BULL, classifier.classify(history("100", "103"), 1));
        assertEquals(MarketRegime.BEAR, classifier.classify(history("100", "97"), 1));
        assertEquals(MarketRegime.FLAT, classifier.classify(history("100", "101"), 1));
    }

    private static List<FeatureSnapshot> history(String previous, String current) {
        return List.of(snapshot(0, previous), snapshot(1, current));
    }

    private static FeatureSnapshot snapshot(int index, String close) {
        Instant time = Instant.EPOCH.plusSeconds(index * 900L);
        return new FeatureSnapshot(time, time.plusSeconds(899), time.plusSeconds(900),
                Map.of(FeatureKey.close(), new BigDecimal(close)));
    }
}
