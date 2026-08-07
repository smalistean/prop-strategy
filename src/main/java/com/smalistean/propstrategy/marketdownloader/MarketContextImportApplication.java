package com.smalistean.propstrategy.marketdownloader;

import java.time.Instant;

public final class MarketContextImportApplication {

    private static final Instant RESEARCH_START = Instant.parse("2023-08-06T00:00:00Z");

    private MarketContextImportApplication() {
    }

    public static void main(String[] args) {
        var symbols = HistoricalImportApplication.symbols(System.getProperty("symbols"));
        for (String symbol : symbols) {
            BtcFundingRateImportApplication.importSymbol(symbol, RESEARCH_START);
        }
        for (String symbol : symbols) {
            BtcSupportingMarketDataImportApplication.importSymbol(symbol);
        }
    }
}
