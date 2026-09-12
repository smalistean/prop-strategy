package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.PostgresKlineRepository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Keeps Binance USDT-M 1h klines current, walking forward over REST from each symbol's latest
 * stored bar. Without {@code -Dsymbols} the list is every perp Binance classifies as
 * {@code underlyingType=EQUITY} in {@code exchangeInfo} (discovered on each run, so a listing made
 * yesterday is collected today from its own listing date), plus the fade universe and BTC/ETH.
 *
 * <p>Exists because nothing did this: {@link KlineArchiveImportApplication} imports monthly archives
 * (so the current month is never present), the daily {@code xvf-refresh.sh} imports Bybit, dYdX and
 * Hyperliquid candles but no Binance klines, and the weekend-fade study was therefore run with three
 * August 2026 weekends silently missing for 19 of its 27 names
 * ({@code WEEKEND_FADE_FUNDING_PREREGISTRATION.md}, amendment A6). The spec's "append new weekends
 * monthly" rule needs the bars to be there.
 *
 * <pre>
 *   -Dsymbols=SPYUSDT,QQQUSDT   comma-separated (default: all EQUITY perps + fade universe + BTC/ETH)
 *   -DklineInterval=1h          Binance interval string (default 1h)
 *   -DklineFrom=ISO-8601        floor for symbols with no rows yet (default 2026-01-01T00:00:00Z);
 *                               a discovered symbol listed later than the floor starts at its
 *                               exchangeInfo onboardDate instead
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
    /** Crypto references the weekend studies regress against; kept current on the same schedule. */
    static final String MAJORS = "BTCUSDT,ETHUSDT";
    private static final String EXCHANGE_INFO = "https://fapi.binance.com/fapi/v1/exchangeInfo";
    private static final int PAGE_LIMIT = 1_000;

    private PerpKlineRefreshApplication() {
    }

    public static void main(String[] args) {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(config);
        String interval = System.getProperty("klineInterval", "1h");
        Instant floor = Instant.parse(System.getProperty("klineFrom", "2026-01-01T00:00:00Z"));
        Instant now = Instant.now().truncatedTo(ChronoUnit.HOURS);

        Map<String, Instant> onboard = new LinkedHashMap<>();
        List<String> symbols;
        String explicit = System.getProperty("symbols");
        if (explicit != null) {
            symbols = List.of(explicit.split(","));
        } else {
            onboard = discoverEquityPerps();
            LinkedHashSet<String> all = new LinkedHashSet<>(List.of(FADE_UNIVERSE.split(",")));
            all.addAll(List.of(MAJORS.split(",")));
            all.addAll(onboard.keySet());
            symbols = List.copyOf(all);
            System.out.printf("discovered %d EQUITY perps; %d symbols in total%n", onboard.size(), symbols.size());
        }

        BinanceKlineClient client = new BinanceKlineClient();
        PostgresKlineRepository repository = new PostgresKlineRepository(config);
        int total = 0;
        for (String symbol : symbols) {
            Instant listed = onboard.get(symbol);
            Instant symbolFloor = listed != null && listed.isAfter(floor) ? listed : floor;
            Instant cursor = repository.latestOpenTime(symbol, interval)
                    .map(t -> t.minus(2, ChronoUnit.HOURS))
                    .orElse(symbolFloor);
            if (cursor.isBefore(symbolFloor)) {
                cursor = symbolFloor;
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

    /** Symbol to onboardDate for every trading perp Binance classifies as EQUITY. */
    static Map<String, Instant> discoverEquityPerps() {
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(EXCHANGE_INFO)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("exchangeInfo HTTP " + response.statusCode());
            }
            Map<String, Instant> found = new LinkedHashMap<>();
            for (JsonNode s : new ObjectMapper().readTree(response.body()).path("symbols")) {
                if ("EQUITY".equals(s.path("underlyingType").asText())
                        && "TRADING".equals(s.path("status").asText())) {
                    found.put(s.path("symbol").asText(), Instant.ofEpochMilli(s.path("onboardDate").asLong()));
                }
            }
            return found;
        } catch (IOException e) {
            throw new IllegalStateException("exchangeInfo fetch failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("exchangeInfo fetch interrupted", e);
        }
    }
}
