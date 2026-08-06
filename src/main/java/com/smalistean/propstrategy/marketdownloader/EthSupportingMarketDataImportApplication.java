package com.smalistean.propstrategy.marketdownloader;

public final class EthSupportingMarketDataImportApplication {

    private EthSupportingMarketDataImportApplication() {
    }

    public static void main(String[] args) {
        BtcSupportingMarketDataImportApplication.importSymbol("ETHUSDT");
    }
}
