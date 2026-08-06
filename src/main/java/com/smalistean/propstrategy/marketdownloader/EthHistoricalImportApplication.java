package com.smalistean.propstrategy.marketdownloader;

import java.time.Instant;

public final class EthHistoricalImportApplication {

    private static final Instant RESEARCH_START = Instant.parse("2023-08-06T00:00:00Z");

    private EthHistoricalImportApplication() {
    }

    public static void main(String[] args) {
        BtcHistoricalImportApplication.importSymbol("ETHUSDT", RESEARCH_START);
    }
}
