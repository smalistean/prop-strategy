package com.smalistean.propstrategy.marketdownloader;

public final class EthIncrementalSyncApplication {

    private EthIncrementalSyncApplication() {
    }

    public static void main(String[] args) {
        BtcIncrementalSyncApplication.synchronizeSymbol("ETHUSDT");
    }
}
