package com.smalistean.propstrategy.marketdownloader;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Builds a true volume-at-price profile directly from Binance aggregate trades. */
public final class HistoricalVolumeProfileReader {

    public record PriceLevel(BigDecimal priceFrom, BigDecimal priceTo, long aggregateTrades,
                             BigDecimal baseVolume, BigDecimal quoteNotional,
                             BigDecimal aggressiveBuyQuote, BigDecimal aggressiveSellQuote) {
        public BigDecimal deltaQuote() {
            return aggressiveBuyQuote.subtract(aggressiveSellQuote);
        }
    }

    public record Result(List<PriceLevel> levels, long sourceRows, long includedRows,
                         BigDecimal totalQuoteNotional) {
        public List<PriceLevel> strongest(int limit) {
            return levels.stream()
                    .sorted(Comparator.comparing(PriceLevel::quoteNotional).reversed())
                    .limit(limit)
                    .toList();
        }

        public PriceLevel pointOfControl() {
            return strongest(1).stream().findFirst().orElse(null);
        }
    }

    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_UP);

    public Result read(List<Path> archives, Instant startInclusive, Instant endExclusive,
                       BigDecimal priceStep) {
        if (!startInclusive.isBefore(endExclusive)) {
            throw new IllegalArgumentException("startInclusive must precede endExclusive");
        }
        if (priceStep.signum() <= 0) {
            throw new IllegalArgumentException("priceStep must be positive");
        }
        Map<BigDecimal, MutableLevel> levels = new HashMap<>();
        long sourceRows = 0;
        long includedRows = 0;
        for (Path archive : archives) {
            long[] counts = readArchive(archive, startInclusive, endExclusive, priceStep, levels);
            sourceRows += counts[0];
            includedRows += counts[1];
        }
        List<PriceLevel> finished = levels.values().stream()
                .map(level -> level.finish(priceStep))
                .sorted(Comparator.comparing(PriceLevel::priceFrom))
                .toList();
        BigDecimal total = finished.stream().map(PriceLevel::quoteNotional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(List.copyOf(finished), sourceRows, includedRows, total);
    }

    private long[] readArchive(Path archive, Instant startInclusive, Instant endExclusive,
                               BigDecimal priceStep, Map<BigDecimal, MutableLevel> levels) {
        long sourceRows = 0;
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
                throw new IllegalArgumentException("Unexpected aggregate-trade CSV header: " + archive);
            }
            while ((line = reader.readLine()) != null) {
                sourceRows++;
                String[] fields = line.split(",", -1);
                if (fields.length != 7) {
                    throw new IllegalArgumentException("Invalid aggregate-trade row in " + archive);
                }
                Instant time = Instant.ofEpochMilli(Long.parseLong(fields[5]));
                if (time.isBefore(startInclusive) || !time.isBefore(endExclusive)) continue;
                BigDecimal price = new BigDecimal(fields[1]);
                BigDecimal quantity = new BigDecimal(fields[2]);
                BigDecimal priceFrom = price.divideToIntegralValue(priceStep).multiply(priceStep);
                levels.computeIfAbsent(priceFrom, MutableLevel::new)
                        .add(quantity, price.multiply(quantity, MC),
                                Boolean.parseBoolean(fields[6]));
                includedRows++;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read aggregate-trade archive " + archive, e);
        }
        return new long[]{sourceRows, includedRows};
    }

    private static final class MutableLevel {
        private final BigDecimal priceFrom;
        private long aggregateTrades;
        private BigDecimal baseVolume = BigDecimal.ZERO;
        private BigDecimal quoteNotional = BigDecimal.ZERO;
        private BigDecimal aggressiveBuyQuote = BigDecimal.ZERO;
        private BigDecimal aggressiveSellQuote = BigDecimal.ZERO;

        private MutableLevel(BigDecimal priceFrom) {
            this.priceFrom = priceFrom;
        }

        private void add(BigDecimal quantity, BigDecimal quote, boolean buyerMaker) {
            aggregateTrades++;
            baseVolume = baseVolume.add(quantity);
            quoteNotional = quoteNotional.add(quote);
            if (buyerMaker) aggressiveSellQuote = aggressiveSellQuote.add(quote);
            else aggressiveBuyQuote = aggressiveBuyQuote.add(quote);
        }

        private PriceLevel finish(BigDecimal priceStep) {
            return new PriceLevel(priceFrom, priceFrom.add(priceStep), aggregateTrades, baseVolume,
                    quoteNotional, aggressiveBuyQuote, aggressiveSellQuote);
        }
    }
}
