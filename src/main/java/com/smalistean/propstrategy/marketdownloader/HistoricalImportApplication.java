package com.smalistean.propstrategy.marketdownloader;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public final class HistoricalImportApplication {

    private static final Instant RESEARCH_START = Instant.parse("2023-08-06T00:00:00Z");
    private static final List<String> DEFAULT_SYMBOLS =
            List.of("SOLUSDT", "XRPUSDT", "BNBUSDT", "ADAUSDT", "DOGEUSDT", "LINKUSDT");

    private HistoricalImportApplication() {
    }

    public static void main(String[] args) {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var imports = symbols(System.getProperty("symbols"))
                    .stream().map(symbol -> executor.submit(
                            () -> BtcHistoricalImportApplication.importSymbol(symbol, RESEARCH_START)))
                    .toList();
            for (java.util.concurrent.Future<?> task : imports) {
                try {
                    task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Historical import interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw new IllegalStateException("Historical import failed", e.getCause());
                }
            }
        }
    }

    static List<String> symbols(String configured) {
        List<String> symbols = configured == null || configured.isBlank()
                ? DEFAULT_SYMBOLS
                : Arrays.stream(configured.split(",")).map(String::trim)
                .map(String::toUpperCase).filter(value -> !value.isBlank()).distinct().toList();
        if (symbols.isEmpty() || symbols.stream().anyMatch(
                symbol -> !symbol.matches("[A-Z0-9]{2,20}USDT"))) {
            throw new IllegalArgumentException("Symbols must be comma-separated USDT pairs");
        }
        return symbols;
    }
}
