package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.PostgresVolumeProfileBinRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class VolumeProfilePreviewApplication {
    private VolumeProfilePreviewApplication() {}

    public static void main(String[] args) {
        String symbol = System.getProperty("profileSymbol", "BTCUSDT").trim().toUpperCase();
        Instant start = Instant.parse(System.getProperty("profileStart", "2023-08-14T00:00:00Z"));
        Instant end = Instant.parse(System.getProperty("profileEnd", "2023-08-15T00:00:00Z"));
        int bucketMinutes = Integer.getInteger("profileBucketMinutes", 15);
        BigDecimal priceStep = new BigDecimal(System.getProperty("profilePriceStep", "10"));
        List<Integer> windows = List.of(96, 288, 672);
        Instant warmupStart = start.minus(Duration.ofMinutes((long) windows.getLast() * bucketMinutes));
        var bins = new PostgresVolumeProfileBinRepository(DatabaseConfig.fromEnvironment())
                .findRange(symbol, bucketMinutes, priceStep, warmupStart, end);
        var profiles = new RollingVolumeProfileGenerator().generate(
                bins, windows, new BigDecimal("0.50"));
        for (int window : windows) {
            System.out.printf("%n%s rolling profile (%d buckets / %dh)%n",
                    symbol, window, window * bucketMinutes / 60);
            profiles.getOrDefault(window, List.of()).stream()
                    .filter(profile -> !profile.asOf().isBefore(start)).skip(Math.max(0,
                            profiles.getOrDefault(window, List.of()).stream()
                                    .filter(profile -> !profile.asOf().isBefore(start)).count() - 5))
                    .forEach(profile -> System.out.printf(
                            "%s POC=[%s,%s) zone=[%s,%s) share=%s%% delta=%s stable=%d%n",
                            profile.asOf(), profile.pocFrom(), profile.pocTo(), profile.zoneFrom(),
                            profile.zoneTo(), profile.zoneShare().multiply(new BigDecimal("100"))
                                    .setScale(2, java.math.RoundingMode.HALF_UP),
                            profile.zoneDeltaQuote().stripTrailingZeros().toEngineeringString(),
                            profile.pocStabilityBuckets()));
        }
    }
}
