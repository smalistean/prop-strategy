package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.Kline;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class KlinePreviewApplication {

    private static final String SYMBOL = "BTCUSDT";
    private static final String INTERVAL = "1h";
    private static final int LIMIT = 10;

    private KlinePreviewApplication() {
    }

    public static void main(String[] args) {
        Instant start = Instant.now()
                .minus(365, ChronoUnit.DAYS)
                .truncatedTo(ChronoUnit.HOURS);
        Instant end = start.plus(24, ChronoUnit.HOURS);

        BinanceKlineClient client = new BinanceKlineClient();
        List<Kline> klines = client.fetchKlines(
                SYMBOL, INTERVAL, start.toEpochMilli(), end.toEpochMilli(), LIMIT);

        System.out.printf("Binance USD(S)-M Futures: %s %s klines from %s%n",
                SYMBOL, INTERVAL, start);
        System.out.println("open_time                 open          high          low           close         volume");
        for (Kline kline : klines) {
            System.out.printf("%-25s %-13s %-13s %-13s %-13s %s%n",
                    kline.openTime(), kline.open(), kline.high(), kline.low(),
                    kline.close(), kline.volume());
        }
        System.out.printf("Received %d klines.%n", klines.size());
    }
}
