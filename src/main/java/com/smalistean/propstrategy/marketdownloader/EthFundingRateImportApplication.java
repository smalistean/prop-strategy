package com.smalistean.propstrategy.marketdownloader;

import java.time.Instant;

public final class EthFundingRateImportApplication {

    private static final Instant RESEARCH_START = Instant.parse("2023-08-06T00:00:00Z");

    private EthFundingRateImportApplication() {
    }

    public static void main(String[] args) {
        BtcFundingRateImportApplication.importSymbol("ETHUSDT", RESEARCH_START);
    }
}
