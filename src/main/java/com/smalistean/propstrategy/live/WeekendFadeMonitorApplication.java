package com.smalistean.propstrategy.live;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Weekend-fade signal monitor across Binance, Bybit and Hyperliquid.
 *
 * <h2>What this is</h2>
 * A read-only signal generator for the strategy pre-registered in
 * {@code WEEKEND_FADE_FUNDING_PREREGISTRATION.md} and operationalised in
 * {@code WEEKEND_FADE_LIVE_SPEC.md}. The universe is <b>discovered live</b>: every non-crypto
 * perp Binance classifies in {@code exchangeInfo} (EQUITY, COMMODITY, PREMARKET, HK/KR/CN
 * equity), every Hyperliquid HIP-3 builder-dex market, and whichever of those Bybit also lists.
 * For each underlying it shows the move since the last real US market close (Friday 16:00
 * America/New_York) per venue, the move vs BTC, Binance funding, whether the user's prop
 * platform lists it (the {@code prop} column - the 36-symbol watchlist captured 2026-08-30),
 * and a signal.
 *
 * <h2>Signal honesty</h2>
 * Two tables are printed. The MEASURED table is the 25-name universe the edge was actually
 * measured on (+147.5 bp/weekend, t=1.82; +175.5/2.10 excluding private names) - only there
 * does a -0.50% move print {@code TRIGGER}. The EXTENDED table is everything else that exists,
 * for the user's own CEX trading: extension E1 measured that broad set at +23 bp/weekend
 * (t=0.22) on the same weekends where the measured names earned +241, so a crossing there
 * prints {@code below -0.5% (E1: no edge)} - availability information, not a recommendation.
 * Private-company perps (no Monday anchor - amendment A2), leveraged/inverse/vol ETPs, the
 * crypto-underlying BITO, and non-US-hours listings are labelled instead of signalled.
 * The mirror short is measured at -70 bp/weekend and closed: no short signals exist here.
 *
 * <p>Places no orders, holds no keys; the firm requires manual execution and the house rule is
 * stricter. The Sunday news check (earnings calendar, per-name headlines) still applies.
 *
 * <h2>Usage</h2>
 * <pre>
 *   -DbasketUsd=15000     total notional per triggered weekend
 *   -DperNameUsd=3000     per-name cap inside the basket
 *   -DskipVenues=bybit,hl skip the slower venue probes
 *   -DtriggersOnly=true   extended table: print only rows below the trigger
 * </pre>
 */
public final class WeekendFadeMonitorApplication {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final double TRIGGER_PCT = -0.50;

    /** The pre-registered measured universe (25 exchange-listed names; NVDA is not on the prop). */
    private static final Set<String> MEASURED = Set.of(
            "SPY", "QQQ", "EWJ", "EWY", "COIN", "TSLA", "MSTR", "PLTR", "HOOD", "AAPL", "AMZN",
            "META", "INTC", "MU", "CRCL", "NVDA", "LLY", "JPM", "QCOM", "TSM", "PAYP", "SNDK",
            "AAOI", "AXTI", "NOK");

    /** The prop platform's tokenized watchlist, 36 symbols, re-verified from screenshots 2026-08-30. */
    private static final Set<String> PROP = Set.of(
            "SPY", "QQQ", "EWJ", "EWY", "COIN", "TSLA", "MSTR", "PLTR", "HOOD", "AAPL", "AMZN",
            "META", "INTC", "MU", "CRCL", "LLY", "JPM", "QCOM", "TSM", "PAYP", "SNDK", "AAOI",
            "AXTI", "NOK", "OPENAI", "SPCX", "SAMSUNG", "SKHYNIX",
            "XAU", "XAG", "XPT", "XPD", "COPPER", "CL", "BZ", "NATGAS");

    /** Daily-rebalance / volatility ETPs plus the crypto-underlying BITO (E1 mechanical exclusions). */
    private static final Set<String> LEVERAGED_OR_VOL = Set.of(
            "SOXL", "SOXS", "TQQQ", "SQQQ", "TZA", "TBT", "TMF", "UVXY", "BITO");

    private record Listing(String binanceSymbol, String binanceType, String hlCoin, boolean onBybit) { }

    private record Row(String base, Listing listing, Double binancePct, Double bybitPct,
                       Double hlPct, Double fundingPct) {
        Double move() {
            return binancePct != null ? binancePct : bybitPct != null ? bybitPct : hlPct;
        }
    }

    public static void main(String[] args) throws Exception {
        double basketUsd = Double.parseDouble(System.getProperty("basketUsd", "15000"));
        double perNameUsd = Double.parseDouble(System.getProperty("perNameUsd", "3000"));
        String skip = System.getProperty("skipVenues", "").toLowerCase(Locale.ROOT);
        boolean useBybit = !skip.contains("bybit");
        boolean useHl = !skip.contains("hl");
        boolean triggersOnly = Boolean.parseBoolean(System.getProperty("triggersOnly", "false"));

        Instant anchor = lastFridayUsClose();
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

        Map<String, Listing> universe = discoverUniverse(http, useBybit, useHl);
        System.err.printf("universe: %d underlyings (binance %d, bybit %d, hl %d) - fetching anchors...%n",
                universe.size(),
                universe.values().stream().filter(l -> l.binanceSymbol() != null).count(),
                universe.values().stream().filter(Listing::onBybit).count(),
                universe.values().stream().filter(l -> l.hlCoin() != null).count());

        String binanceTickers = get(http, "https://fapi.binance.com/fapi/v1/ticker/price");
        String binancePremium = get(http, "https://fapi.binance.com/fapi/v1/premiumIndex");
        String bybitTickers = useBybit
                ? get(http, "https://api.bybit.com/v5/market/tickers?category=linear") : "";

        Double btcPct = binancePct(http, binanceTickers, "BTCUSDT", anchor);
        Double ethPct = binancePct(http, binanceTickers, "ETHUSDT", anchor);

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Listing> e : universe.entrySet()) {
            Listing l = e.getValue();
            Double binance = l.binanceSymbol() != null
                    ? binancePct(http, binanceTickers, l.binanceSymbol(), anchor) : null;
            Double bybit = useBybit && l.onBybit()
                    ? bybitPct(http, bybitTickers, e.getKey() + "USDT", anchor) : null;
            Double hl = useHl ? hyperliquidPct(http, l.hlCoin(), anchor) : null;
            Double funding = l.binanceSymbol() != null
                    ? field(binancePremium, l.binanceSymbol(), "lastFundingRate") : null;
            rows.add(new Row(e.getKey(), l, binance, bybit, hl,
                    funding == null ? null : funding * 100));
        }
        render(rows, btcPct, ethPct, anchor, Instant.now(), basketUsd, perNameUsd, triggersOnly);
    }

    // --- universe discovery --------------------------------------------------------------------

    /**
     * Base ticker -> where it trades. Binance is authoritative for classification
     * ({@code underlyingType}); Hyperliquid adds HIP-3 builder-dex markets (some are HL-only);
     * Bybit is probed by symbol name against the union, because its API carries no equity
     * classification. INDEX-type crypto baskets (BTCDOM, ALL) and non-USDT contracts are skipped.
     */
    /** Cryptos with no Binance perp to classify them (Binance delisted XMR), still on HL/Bybit. */
    private static final Set<String> KNOWN_CRYPTO = Set.of("XMR");

    private static Map<String, Listing> discoverUniverse(HttpClient http, boolean useBybit,
                                                         boolean useHl) throws Exception {
        Map<String, String> binance = new TreeMap<>();
        Map<String, String> types = new LinkedHashMap<>();
        Set<String> coinBases = new java.util.HashSet<>();
        String info = get(http, "https://fapi.binance.com/fapi/v1/exchangeInfo");
        Matcher m = Pattern.compile("\\{\"symbol\":\"([A-Z0-9]+USDT)\",(?:[^{}]|\\{[^{}]*})*?"
                + "\"underlyingType\":\"([A-Z_]+)\"").matcher(info);
        while (m.find()) {
            String type = m.group(2);
            String base = m.group(1).substring(0, m.group(1).length() - 4);
            if (type.equals("COIN") || type.equals("INDEX")) {
                // Remember crypto bases (PEPE from 1000PEPEUSDT too) so an HL builder dex or a
                // Bybit name-match cannot smuggle a crypto perp into the equity tables.
                coinBases.add(base.startsWith("1000") ? base.substring(4) : base);
                continue;
            }
            binance.put(base, m.group(1));
            types.put(base, type);
        }

        Map<String, String> hl = useHl ? hyperliquidUniverse(http) : Map.of();

        Set<String> bybit = new java.util.HashSet<>();
        if (useBybit) {
            String tickers = get(http, "https://api.bybit.com/v5/market/tickers?category=linear");
            for (String base : union(binance.keySet(), hl.keySet())) {
                if (tickers.contains("\"symbol\":\"" + base + "USDT\"")) bybit.add(base);
            }
        }

        int crypto = 0;
        Map<String, Listing> universe = new TreeMap<>();
        for (String base : union(binance.keySet(), hl.keySet())) {
            boolean isCrypto = binance.get(base) == null
                    && (coinBases.contains(base) || KNOWN_CRYPTO.contains(base));
            if (isCrypto) {
                crypto++;
                continue;
            }
            universe.put(base, new Listing(binance.get(base),
                    types.getOrDefault(base, "HL_ONLY"), hl.get(base), bybit.contains(base)));
        }
        System.err.printf("filtered %d crypto bases that leaked in via HL builder dexes%n", crypto);
        return universe;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> u = new java.util.TreeSet<>(a);
        u.addAll(b);
        return u;
    }

    // --- rendering -----------------------------------------------------------------------------

    private static void render(List<Row> rows, Double btcPct, Double ethPct, Instant anchor,
                               Instant now, double basketUsd, double perNameUsd, boolean triggersOnly) {
        DateTimeFormatter stamp = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm 'UTC'");
        StringBuilder out = new StringBuilder();
        out.append("WEEKEND FADE MONITOR   now ").append(stamp.format(now.atZone(ZoneOffset.UTC)))
           .append('\n');
        out.append("anchor (US close)      ").append(stamp.format(anchor.atZone(ZoneOffset.UTC)))
           .append("   entry Sun 20:00 UTC").append(countdown(now, nextSundayEntry(anchor)))
           .append("   exit Mon 11:00 New York").append(countdown(now, nextMondayExit(anchor)))
           .append('\n');
        out.append(String.format("crypto mood since anchor:  BTC %s   ETH %s%n%n",
                pct(btcPct), pct(ethPct)));

        String header = String.format("%-10s %4s %10s %10s %10s %11s %10s  %s%n",
                "symbol", "prop", "binance", "bybit", "hl", "vs BTC", "funding", "signal");
        Comparator<Row> byMove = Comparator.comparing(r -> r.move() == null ? 999.0 : r.move());

        out.append("MEASURED UNIVERSE (signal source - prereg +147.5 bp/weekend, t=1.82)\n")
           .append(header).append("-".repeat(84)).append('\n');
        int triggered = 0;
        for (Row r : rows.stream().filter(r -> MEASURED.contains(r.base())).sorted(byMove).toList()) {
            boolean below = r.move() != null && r.move() <= TRIGGER_PCT;
            if (below) triggered++;
            out.append(line(r, btcPct, below ? "TRIGGER" : ""));
        }

        out.append("\nEXTENDED (availability only - E1 measured NO edge here: +23 bp/wknd, t=0.22)\n")
           .append(header).append("-".repeat(84)).append('\n');
        for (Row r : rows.stream().filter(r -> !MEASURED.contains(r.base())).sorted(byMove).toList()) {
            String signal = extendedSignal(r);
            boolean below = r.move() != null && r.move() <= TRIGGER_PCT;
            if (triggersOnly && !below) continue;
            out.append(line(r, btcPct, signal));
        }

        out.append('\n');
        if (triggered == 0) {
            out.append("no measured name at or below ").append(TRIGGER_PCT)
               .append("% - no challenge trade this weekend unless that changes by Sunday 20:00 UTC.\n");
        } else {
            double perName = Math.min(perNameUsd, basketUsd / triggered);
            out.append(String.format(
                    "%d measured name(s) triggered. Basket $%,.0f, per-name cap $%,.0f -> $%,.0f each.%n",
                    triggered, basketUsd, perNameUsd, perName));
            out.append("Before entering: earnings calendar + per-name headline check (see live spec).\n");
        }
        out.append("Long-only. Manual execution only - this monitor places nothing. Extended rows are\n"
                + "listing availability for the user's own venue accounts, not signals: E1's verdict\n"
                + "stands until a pre-registered re-measurement says otherwise.\n");
        System.out.print(out);
    }

    private static String line(Row r, Double btcPct, String signal) {
        String excess = r.move() != null && btcPct != null ? pct(r.move() - btcPct) : "-";
        return String.format("%-10s %4s %10s %10s %10s %11s %10s  %s%n",
                r.base(), PROP.contains(r.base()) ? "+" : "",
                pct(r.binancePct()), pct(r.bybitPct()), pct(r.hlPct()), excess,
                r.fundingPct() == null ? "-" : String.format("%.4f%%", r.fundingPct()), signal);
    }

    private static String extendedSignal(Row r) {
        String type = r.listing().binanceType();
        boolean below = r.move() != null && r.move() <= TRIGGER_PCT;
        if (type.equals("PREMARKET") || r.base().equals("SPCX") || r.base().equals("ANTHROPIC")) {
            return "no anchor (private)";
        }
        if (LEVERAGED_OR_VOL.contains(r.base())) return "leveraged/vol ETP - excluded";
        if (type.startsWith("HK_") || type.startsWith("KR_") || type.startsWith("CN_")) {
            return "non-US hours (anchor wrong)";
        }
        if (type.equals("COMMODITY")) return below ? "metals/energy - measured dead" : "";
        if (type.equals("HL_ONLY")) return below ? "below -0.5% (HL-only, unmeasured)" : "";
        return below ? "below -0.5% (E1: no edge)" : "";
    }

    // --- time ----------------------------------------------------------------------------------

    /** Friday 16:00 New York of the current/most recent trading week, as an instant. */
    private static Instant lastFridayUsClose() {
        ZonedDateTime nowNy = ZonedDateTime.now(NEW_YORK);
        LocalDate friday = nowNy.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY));
        ZonedDateTime close = ZonedDateTime.of(friday, LocalTime.of(16, 0), NEW_YORK);
        if (close.isAfter(nowNy)) {
            close = close.minusWeeks(1);
        }
        return close.toInstant();
    }

    private static Instant nextSundayEntry(Instant anchor) {
        LocalDate friday = anchor.atZone(NEW_YORK).toLocalDate();
        return ZonedDateTime.of(friday.plusDays(2), LocalTime.of(20, 0), ZoneOffset.UTC).toInstant();
    }

    private static Instant nextMondayExit(Instant anchor) {
        LocalDate friday = anchor.atZone(NEW_YORK).toLocalDate();
        return ZonedDateTime.of(friday.plusDays(3), LocalTime.of(11, 0), NEW_YORK).toInstant();
    }

    private static String countdown(Instant now, Instant at) {
        long minutes = Duration.between(now, at).toMinutes();
        if (minutes < -12 * 60) return " (passed)";
        if (minutes < 0) return " (running)";
        return String.format(" (in %dh%02dm)", minutes / 60, minutes % 60);
    }

    private static String pct(Double value) {
        return value == null ? "-" : String.format("%+.2f%%", value);
    }

    // --- Binance -------------------------------------------------------------------------------

    private static Double binancePct(HttpClient http, String tickers, String symbol, Instant anchor)
            throws Exception {
        Double last = field(tickers, symbol, "price");
        if (last == null) return null;
        String kline = get(http, "https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol
                + "&interval=1h&startTime=" + anchor.minusSeconds(3600).toEpochMilli()
                + "&endTime=" + (anchor.toEpochMilli() - 1) + "&limit=1");
        Matcher m = Pattern.compile("\\[\\d+,\"[-0-9.]+\",\"[-0-9.]+\",\"[-0-9.]+\",\"([-0-9.]+)\"")
                .matcher(kline);
        if (!m.find()) return null;
        return (last / Double.parseDouble(m.group(1)) - 1) * 100;
    }

    // --- Bybit ---------------------------------------------------------------------------------

    private static Double bybitPct(HttpClient http, String tickers, String symbol, Instant anchor) {
        try {
            Double last = field(tickers, symbol, "lastPrice");
            if (last == null) return null;
            String kline = get(http, "https://api.bybit.com/v5/market/kline?category=linear&symbol="
                    + symbol + "&interval=60&start=" + anchor.minusSeconds(3600).toEpochMilli()
                    + "&end=" + (anchor.toEpochMilli() - 1) + "&limit=1");
            Matcher m = Pattern.compile("\\[\"\\d+\",\"[-0-9.]+\",\"[-0-9.]+\",\"[-0-9.]+\",\"([-0-9.]+)\"")
                    .matcher(kline);
            if (!m.find()) return null;
            return (last / Double.parseDouble(m.group(1)) - 1) * 100;
        } catch (Exception e) {
            return null;
        }
    }

    // --- Hyperliquid ---------------------------------------------------------------------------

    /**
     * Maps base ticker -> Hyperliquid coin name. The equity/ETF markets are not on the main dex:
     * they are HIP-3 builder-deployed dexes (as of 2026-08-30 the {@code xyz} dex carries 117
     * equity/commodity/FX markets, named {@code xyz:TSLA}), listed by {@code perpDexs} and
     * described by {@code meta} with a {@code dex} parameter. Every named dex is enumerated and
     * the first dex listing a base wins - {@code perpDexs} returns them in deployment order, so
     * the incumbent market outranks any copycat. Requests are spaced because the endpoint is
     * weight-limited per IP (see {@link com.smalistean.propstrategy.marketdownloader.HyperliquidClient}).
     */
    private static Map<String, String> hyperliquidUniverse(HttpClient http) {
        Map<String, String> coins = new LinkedHashMap<>();
        try {
            String dexes = post(http, "{\"type\":\"perpDexs\"}");
            Matcher dexMatcher = Pattern.compile("\"name\":\"([a-z0-9]+)\"").matcher(dexes);
            while (dexMatcher.find()) {
                String dex = dexMatcher.group(1);
                Thread.sleep(300);
                String meta = post(http, "{\"type\":\"meta\",\"dex\":\"" + dex + "\"}");
                Matcher m = Pattern.compile("\"name\":\"" + dex + ":([^\"]+)\"").matcher(meta);
                while (m.find()) {
                    coins.putIfAbsent(m.group(1).toUpperCase(Locale.ROOT), dex + ":" + m.group(1));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("hyperliquid dex enumeration failed: " + e.getMessage());
        }
        return coins;
    }

    private static Double hyperliquidPct(HttpClient http, String coin, Instant anchor) {
        if (coin == null) return null;
        try {
            Thread.sleep(300);
            String candles = post(http, "{\"type\":\"candleSnapshot\",\"req\":{\"coin\":\"" + coin
                    + "\",\"interval\":\"1h\",\"startTime\":" + anchor.minusSeconds(3600).toEpochMilli()
                    + ",\"endTime\":" + System.currentTimeMillis() + "}}");
            long anchorBarOpen = anchor.minusSeconds(3600).toEpochMilli();
            Matcher m = Pattern.compile("\"t\":(\\d+),.*?\"c\":\"([-0-9.]+)\"").matcher(candles);
            Double anchorClose = null;
            Double last = null;
            long lastT = Long.MIN_VALUE;
            while (m.find()) {
                long t = Long.parseLong(m.group(1));
                double close = Double.parseDouble(m.group(2));
                if (t == anchorBarOpen) anchorClose = close;
                if (t > lastT) {
                    lastT = t;
                    last = close;
                }
            }
            if (anchorClose == null || last == null) return null;
            return (last / anchorClose - 1) * 100;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // --- plumbing ------------------------------------------------------------------------------

    /** Pulls one numeric field out of the JSON object whose "symbol" matches, without a JSON lib. */
    private static Double field(String json, String symbol, String name) {
        int at = json.indexOf("\"symbol\":\"" + symbol + "\"");
        if (at < 0) return null;
        int start = json.lastIndexOf('{', at);
        int end = json.indexOf('}', at);
        if (start < 0 || end < 0) return null;
        Matcher m = Pattern.compile("\"" + name + "\":\"?([-0-9.eE]+)\"?")
                .matcher(json.substring(start, end));
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static String get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String post(HttpClient http, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("https://api.hyperliquid.xyz/info"))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(30))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
