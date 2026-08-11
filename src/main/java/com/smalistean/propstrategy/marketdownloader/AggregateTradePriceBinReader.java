package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.VolumeProfileBin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class AggregateTradePriceBinReader {
    public record Result(List<VolumeProfileBin> bins, long sourceRows, long includedRows) {}

    private record Key(Instant bucketTime, BigDecimal priceFrom) {}
    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_UP);

    public Result read(Path archive, String symbol, Instant start, Instant end,
                       int bucketMinutes, BigDecimal priceStep) {
        if (!start.isBefore(end) || bucketMinutes <= 0 || priceStep.signum() <= 0) {
            throw new IllegalArgumentException("Invalid volume-profile import dimensions");
        }
        Map<Key, MutableBin> bins = new HashMap<>();
        long sourceRows = 0, includedRows = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(zip, StandardCharsets.UTF_8), 1 << 20)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory()) throw new IllegalArgumentException("Archive contains no CSV");
            // Binance changed the archive format: monthly files from roughly 2022 onward carry a
            // "agg_trade_id,price,..." header row, while older ones begin directly with data. Peek
            // at the first line and rewind if it is data, so both layouts parse identically.
            reader.mark(1 << 16);
            String line = reader.readLine();
            if (line == null) {
                throw new IllegalArgumentException("Aggregate-trade CSV is empty");
            }
            if (!line.startsWith("agg_trade_id,")) {
                reader.reset();
            }
            long bucketMillis = bucketMinutes * 60_000L;
            while ((line = reader.readLine()) != null) {
                sourceRows++;
                String[] fields = line.split(",", -1);
                if (fields.length != 7) throw new IllegalArgumentException("Invalid row " + sourceRows);
                long epochMillis = Long.parseLong(fields[5]);
                Instant time = Instant.ofEpochMilli(epochMillis);
                if (time.isBefore(start) || !time.isBefore(end)) continue;
                BigDecimal price = new BigDecimal(fields[1]);
                BigDecimal quantity = new BigDecimal(fields[2]);
                Instant bucket = Instant.ofEpochMilli(Math.floorDiv(epochMillis, bucketMillis) * bucketMillis);
                BigDecimal priceFrom = price.divideToIntegralValue(priceStep).multiply(priceStep);
                bins.computeIfAbsent(new Key(bucket, priceFrom), ignored -> new MutableBin())
                        .add(quantity, price.multiply(quantity, MC), Boolean.parseBoolean(fields[6]));
                includedRows++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + archive, e);
        }
        List<VolumeProfileBin> result = bins.entrySet().stream().map(entry -> new VolumeProfileBin(
                        symbol, entry.getKey().bucketTime(), bucketMinutes, priceStep,
                        entry.getKey().priceFrom(), entry.getValue().count, entry.getValue().base,
                        entry.getValue().quote, entry.getValue().buy, entry.getValue().sell))
                .sorted(Comparator.comparing(VolumeProfileBin::bucketTime)
                        .thenComparing(VolumeProfileBin::priceFrom)).toList();
        return new Result(List.copyOf(result), sourceRows, includedRows);
    }

    private static final class MutableBin {
        private long count;
        private BigDecimal base = BigDecimal.ZERO, quote = BigDecimal.ZERO;
        private BigDecimal buy = BigDecimal.ZERO, sell = BigDecimal.ZERO;

        private void add(BigDecimal quantity, BigDecimal notional, boolean buyerMaker) {
            count++; base = base.add(quantity); quote = quote.add(notional);
            if (buyerMaker) sell = sell.add(notional); else buy = buy.add(notional);
        }
    }
}
