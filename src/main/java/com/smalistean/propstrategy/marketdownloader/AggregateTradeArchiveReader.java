package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.AggregateTradeMinute;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class AggregateTradeArchiveReader {

    public record Result(List<AggregateTradeMinute> minutes, long sourceRows,
                         long filteredRows, long duplicateRows, long gapCount) {
    }

    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_UP);

    public Result read(Path archive, String symbol, Instant startInclusive, Instant endExclusive) {
        return read(archive, symbol, startInclusive, endExclusive, null);
    }

    public Result read(Path archive, String symbol, Instant startInclusive, Instant endExclusive,
                       Long previousAggregateTradeId) {
        NavigableMap<Instant, MutableMinute> aggregated = new TreeMap<>();
        long sourceRows = 0;
        long filteredRows = 0;
        long duplicates = 0;
        long gaps = 0;
        long minimumIncludedId = Long.MAX_VALUE;
        long maximumIncludedId = Long.MIN_VALUE;
        long includedRows = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive));
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(zip, StandardCharsets.UTF_8), 1 << 20)) {
            ZipEntry entry = zip.getNextEntry();
            if (entry == null || entry.isDirectory()) {
                throw new IllegalArgumentException("Archive contains no CSV: " + archive);
            }
            String line = reader.readLine();
            if (line == null || !line.startsWith("agg_trade_id,")) {
                throw new IllegalArgumentException("Unexpected aggregate-trade CSV header");
            }
            while ((line = reader.readLine()) != null) {
                sourceRows++;
                String[] fields = line.split(",", -1);
                if (fields.length != 7) {
                    throw new IllegalArgumentException("Invalid aggregate-trade row at " + sourceRows);
                }
                long id = Long.parseLong(fields[0]);
                Instant time = Instant.ofEpochMilli(Long.parseLong(fields[5]));
                if (time.isBefore(startInclusive) || !time.isBefore(endExclusive)) {
                    filteredRows++;
                    continue;
                }
                Instant minute = time.truncatedTo(ChronoUnit.MINUTES);
                MutableMinute current = aggregated.computeIfAbsent(minute,
                        ignored -> new MutableMinute(symbol, minute));
                current.add(id, new BigDecimal(fields[1]), new BigDecimal(fields[2]),
                        Long.parseLong(fields[3]), Long.parseLong(fields[4]), time,
                        Boolean.parseBoolean(fields[6]), 0);
                minimumIncludedId = Math.min(minimumIncludedId, id);
                maximumIncludedId = Math.max(maximumIncludedId, id);
                includedRows++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read aggregate-trade archive " + archive, e);
        }
        if (includedRows > 0) {
            long expectedIds = maximumIncludedId - minimumIncludedId + 1;
            gaps = Math.max(0, expectedIds - includedRows);
            duplicates = Math.max(0, includedRows - expectedIds);
            if (previousAggregateTradeId != null) {
                gaps += Math.max(0, minimumIncludedId - previousAggregateTradeId - 1);
            }
            MutableMinute first = aggregated.firstEntry().getValue();
            first.gaps = gaps;
            first.duplicateCount = Math.toIntExact(duplicates);
        }
        List<AggregateTradeMinute> result = aggregated.values().stream()
                .map(MutableMinute::finish).toList();
        return new Result(List.copyOf(result), sourceRows, filteredRows, duplicates, gaps);
    }

    private static final class MutableMinute {
        private static final BigDecimal TEN_K = new BigDecimal("10000");
        private static final BigDecimal HUNDRED_K = new BigDecimal("100000");
        private static final BigDecimal ONE_M = new BigDecimal("1000000");
        private final String symbol;
        private final Instant minuteTime;
        private Instant firstTime;
        private Instant lastTime;
        private long firstId;
        private long lastId;
        private int count;
        private long underlyingCount;
        private BigDecimal base = BigDecimal.ZERO, quote = BigDecimal.ZERO;
        private BigDecimal buyBase = BigDecimal.ZERO, sellBase = BigDecimal.ZERO;
        private BigDecimal buyQuote = BigDecimal.ZERO, sellQuote = BigDecimal.ZERO;
        private BigDecimal firstPrice, lastPrice, minPrice, maxPrice, maxAggregate = BigDecimal.ZERO;
        private int count10k, count100k, count1m, duplicateCount;
        private BigDecimal buy10k = BigDecimal.ZERO, sell10k = BigDecimal.ZERO;
        private BigDecimal buy100k = BigDecimal.ZERO, sell100k = BigDecimal.ZERO;
        private BigDecimal buy1m = BigDecimal.ZERO, sell1m = BigDecimal.ZERO;
        private long gaps;

        private MutableMinute(String symbol, Instant minuteTime) {
            this.symbol = symbol;
            this.minuteTime = minuteTime;
        }

        private void add(long id, BigDecimal price, BigDecimal quantity, long firstTrade,
                         long lastTrade, Instant time, boolean buyerMaker, long gap) {
            BigDecimal notional = price.multiply(quantity, MC);
            if (count == 0) {
                firstId = id; lastId = id; firstTime = time; lastTime = time;
                firstPrice = price; lastPrice = price; minPrice = price; maxPrice = price;
            } else {
                if (time.isBefore(firstTime) || (time.equals(firstTime) && id < firstId)) {
                    firstTime = time; firstPrice = price;
                }
                if (time.isAfter(lastTime) || (time.equals(lastTime) && id > lastId)) {
                    lastTime = time; lastPrice = price;
                }
                firstId = Math.min(firstId, id);
                lastId = Math.max(lastId, id);
            }
            count++;
            underlyingCount += lastTrade - firstTrade + 1;
            base = base.add(quantity); quote = quote.add(notional); gaps += gap;
            minPrice = minPrice.min(price); maxPrice = maxPrice.max(price); maxAggregate = maxAggregate.max(notional);
            if (buyerMaker) { sellBase = sellBase.add(quantity); sellQuote = sellQuote.add(notional); }
            else { buyBase = buyBase.add(quantity); buyQuote = buyQuote.add(notional); }
            if (notional.compareTo(TEN_K) >= 0) { count10k++; if (buyerMaker) sell10k = sell10k.add(notional); else buy10k = buy10k.add(notional); }
            if (notional.compareTo(HUNDRED_K) >= 0) { count100k++; if (buyerMaker) sell100k = sell100k.add(notional); else buy100k = buy100k.add(notional); }
            if (notional.compareTo(ONE_M) >= 0) { count1m++; if (buyerMaker) sell1m = sell1m.add(notional); else buy1m = buy1m.add(notional); }
        }

        private AggregateTradeMinute finish() {
            BigDecimal buyVwap = buyBase.signum() == 0 ? null : buyQuote.divide(buyBase, MC);
            BigDecimal sellVwap = sellBase.signum() == 0 ? null : sellQuote.divide(sellBase, MC);
            return new AggregateTradeMinute(symbol, minuteTime, firstTime, lastTime, firstId, lastId,
                    count, underlyingCount, base, quote, buyBase, sellBase, buyQuote, sellQuote,
                    buyBase.subtract(sellBase), buyQuote.subtract(sellQuote), firstPrice, lastPrice,
                    minPrice, maxPrice, buyVwap, sellVwap, maxAggregate, count10k, buy10k, sell10k,
                    count100k, buy100k, sell100k, count1m, buy1m, sell1m, gaps, duplicateCount);
        }
    }
}
