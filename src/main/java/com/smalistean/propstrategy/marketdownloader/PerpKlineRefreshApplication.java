package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresKlineRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Keeps Binance USDT-M 1h klines current for a fixed symbol list, walking forward over REST from
 * each symbol's latest stored bar.
 *
 * <p>Exists because nothing did this: {@link KlineArchiveImportApplication} imports monthly archives
 * (so the current month is never present), the daily {@code xvf-refresh.sh} imports Bybit, dYdX and
 * Hyperliquid candles but no Binance klines, and the weekend-fade study was therefore run with three
 * August 2026 weekends silently missing for 19 of its 27 names
 * ({@code WEEKEND_FADE_FUNDING_PREREGISTRATION.md}, amendment A6). The spec's "append new weekends
 * monthly" rule needs the bars to be there.
 *
 * <pre>
 *   -Dsymbols=SPYUSDT,QQQUSDT   comma-separated (default: the 27-name fade universe)
 *   -DklineInterval=1h          Binance interval string (default 1h)
 *   -DklineFrom=ISO-8601        floor for symbols with no rows yet (default 2026-01-01T00:00:00Z)
 * </pre>
 * Re-fetches the last two stored bars on every run so a bar that was open at the previous run is
 * overwritten by its closed version ({@code ON CONFLICT ... DO UPDATE}). Only closed bars are
 * requested: the request window ends one millisecond before the current hour.
 */
public final class PerpKlineRefreshApplication {

    /** The weekend-fade universe as pre-registered (24 live names + NVDA + the two private names). */
    static final String FADE_UNIVERSE = "SPYUSDT,QQQUSDT,EWJUSDT,EWYUSDT,COINUSDT,TSLAUSDT,MSTRUSDT,PLTRUSDT,"
            + "HOODUSDT,AAPLUSDT,AMZNUSDT,METAUSDT,INTCUSDT,MUUSDT,CRCLUSDT,NVDAUSDT,LLYUSDT,JPMUSDT,QCOMUSDT,"
            + "TSMUSDT,PAYPUSDT,SNDKUSDT,AAOIUSDT,AXTIUSDT,NOKUSDT,OPENAIUSDT,SPCXUSDT";
    private static final int PAGE_LIMIT = 1_000;

    private PerpKlineRefreshApplication() {
    }

    public static void main(String[] args) {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(config);
        String interval = System.getProperty("klineInterval", "1h");
        List<String> symbols = List.of(System.getProperty("symbols", FADE_UNIVERSE).split(","));
        Instant floor = Instant.parse(System.getProperty("klineFrom", "2026-01-01T00:00:00Z"));
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);

        BinanceKlineClient client = new BinanceKlineClient();
        PostgresKlineRepository repository = new PostgresKlineRepository(config);
        int total = 0;
        for (String symbol : symbols) {
            Instant cursor = repository.latestOpenTime(symbol, interval)
                    .map(t -> t.minus(2, ChronoUnit.HOURS))
                    .orElse(floor);
            if (cursor.isBefore(floor)) {
                cursor = floor;
            }
            int rows = 0;
            while (cursor.isBefore(now)) {
                List<Kline> page = client.fetchKlines(symbol, interval,
                        cursor.toEpochMilli(), now.toEpochMilli() - 1, PAGE_LIMIT);
                if (page.isEmpty()) {
                    break;
                }
                rows += repository.upsertAll(symbol, interval, page);
                Instant last = page.get(page.size() - 1).openTime();
                if (!last.isAfter(cursor)) {
                    break;
                }
                cursor = last.plusMillis(1);
            }
            total += rows;
            System.out.printf("%-12s %,6d rows  latest bar %s%n", symbol, rows,
                    repository.latestOpenTime(symbol, interval).map(Instant::toString).orElse("none"));
        }
        System.out.printf("REFRESH DONE: %,d rows across %d symbols%n", total, symbols.size());
    }
}
