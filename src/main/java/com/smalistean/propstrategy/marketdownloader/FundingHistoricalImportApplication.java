package com.smalistean.propstrategy.marketdownloader;

import java.time.Instant;

/** Imports the complete available funding history for the configured Futures universe. */
public final class FundingHistoricalImportApplication {
    private static final Instant DEFAULT_START = Instant.parse("2023-01-01T00:00:00Z");

    private FundingHistoricalImportApplication() {
    }

    public static void main(String[] args) {
        Instant start = Instant.parse(System.getProperty("start", DEFAULT_START.toString()));
        for (String symbol : HistoricalImportApplication.symbols(System.getProperty("symbols"))) {
            BtcFundingRateImportApplication.importSymbol(symbol, start);
        }
    }
}
