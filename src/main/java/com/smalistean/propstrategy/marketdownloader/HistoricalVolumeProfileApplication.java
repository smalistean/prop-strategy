package com.smalistean.propstrategy.marketdownloader;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

public final class HistoricalVolumeProfileApplication {

    private HistoricalVolumeProfileApplication() {
    }

    public static void main(String[] args) throws Exception {
        String symbol = System.getProperty("profileSymbol", "BTCUSDT").trim().toUpperCase();
        Path directory = Path.of(System.getProperty(
                "profileArchiveDir", "data/order-flow/" + symbol));
        Instant start = Instant.parse(System.getProperty(
                "profileStart", "2023-08-07T00:00:00Z"));
        Instant end = Instant.parse(System.getProperty(
                "profileEnd", "2023-09-01T00:00:00Z"));
        BigDecimal priceStep = new BigDecimal(System.getProperty("profilePriceStep", "10"));
        int top = Integer.getInteger("profileTop", 15);
        List<Path> archives;
        try (var paths = Files.list(directory)) {
            archives = paths.filter(path -> path.getFileName().toString().endsWith(".zip"))
                    .filter(path -> overlaps(path, start, end))
                    .sorted().toList();
        }
        if (archives.isEmpty()) throw new IllegalArgumentException("No ZIP archives in " + directory);

        HistoricalVolumeProfileReader.Result result = new HistoricalVolumeProfileReader()
                .read(archives, start, end, priceStep);
        System.out.printf("Historical volume profile: symbol=%s period=[%s, %s) step=%s "
                        + "archives=%d sourceRows=%,d includedRows=%,d levels=%,d notional=%s%n",
                symbol, start, end, priceStep.toPlainString(), archives.size(), result.sourceRows(),
                result.includedRows(), result.levels().size(), result.totalQuoteNotional()
                        .stripTrailingZeros().toEngineeringString());
        int rank = 1;
        for (HistoricalVolumeProfileReader.PriceLevel level : result.strongest(top)) {
            BigDecimal share = result.totalQuoteNotional().signum() == 0 ? BigDecimal.ZERO
                    : level.quoteNotional().multiply(new BigDecimal("100"))
                    .divide(result.totalQuoteNotional(), 4, java.math.RoundingMode.HALF_UP);
            System.out.printf("%2d. [%s, %s) quote=%s share=%s%% delta=%s trades=%,d%s%n",
                    rank++, level.priceFrom().toPlainString(), level.priceTo().toPlainString(),
                    level.quoteNotional().stripTrailingZeros().toEngineeringString(),
                    share.stripTrailingZeros().toPlainString(),
                    level.deltaQuote().stripTrailingZeros().toEngineeringString(),
                    level.aggregateTrades(), rank == 2 ? " POC" : "");
        }
        System.out.println("Research warning: a profile built over the whole displayed period is "
                + "descriptive only. A backtest must use a rolling profile ending before each entry.");
    }

    static boolean overlaps(Path archive, Instant start, Instant end) {
        String name = archive.getFileName().toString();
        int marker = name.indexOf("aggTrades-");
        if (marker < 0) return true;
        String date = name.substring(marker + "aggTrades-".length(), name.length() - 4);
        try {
            Instant archiveStart;
            Instant archiveEnd;
            if (date.length() == 7) {
                YearMonth month = YearMonth.parse(date);
                archiveStart = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                archiveEnd = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            } else {
                LocalDate day = LocalDate.parse(date);
                archiveStart = day.atStartOfDay().toInstant(ZoneOffset.UTC);
                archiveEnd = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            }
            return archiveStart.isBefore(end) && archiveEnd.isAfter(start);
        } catch (java.time.format.DateTimeParseException ignored) {
            return true;
        }
    }
}
