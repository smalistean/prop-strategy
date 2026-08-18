package com.smalistean.propstrategy.live;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Live risk and sizing monitor for a funded-account challenge.
 *
 * <h2>What this is, and deliberately is not</h2>
 * It is <b>not</b> a signal generator. Three positioning signals were tested against
 * {@code POSITIONING_PREREGISTRATION.md} and all three were refuted; the best reached a 53.4% hit
 * rate against a 51.1% base rate. Emitting "buy/sell" from that would present a coin flip as a
 * system.
 *
 * <p>What it does compute is <b>position sizing</b>, because that is the one lever measured to be
 * worth anything. Simulated on 110,190 real daily price paths, the probability of passing a
 * 10%-target/10%-drawdown challenge runs 12.5% at 5x leverage and 40.9% at 0.5x - a 28-point swing
 * from sizing alone, with no view on direction at all. The reason is the daily-loss cap: it is
 * measured on unrealised equity, so it triggers on the intraday low, and larger positions convert it
 * into a stop tight enough that ordinary noise takes it out.
 *
 * <p>So the columns that matter here are {@code maxNotional} and {@code pDailyBreach}. Everything
 * else is context.
 *
 * <h2>Usage</h2>
 * <pre>
 *   -DequityUsd=48483        current account equity
 *   -DdailyCapUsd=2500       maximum daily loss
 *   -DremainingUsd=3483      remaining total-loss budget
 *   -DtargetUsd=5517         profit still required
 *   -Dsymbols=BTCUSDT,ETHUSDT,SOLUSDT
 * </pre>
 * Public market data only - no API key, and it places no orders.
 */
public final class ChallengeMonitorApplication {

    private record Live(double price, double change24h, double fundingRate, double dailyVolPct) { }

    /** Highest simulated pass rate over 110,190 real daily paths; see CHALLENGE analysis. */
    private static final double SIM_OPTIMAL_LEVERAGE =
            Double.parseDouble(System.getProperty("sizeLeverage", "0.5"));

    private static final Map<String, Live> STATE = new ConcurrentHashMap<>();
    private static final Pattern FIELD = Pattern.compile("\"%s\":\"?([-0-9.eE]+)\"?");

    public static void main(String[] args) throws Exception {
        double equity = Double.parseDouble(System.getProperty("equityUsd", "48483"));
        double dailyCap = Double.parseDouble(System.getProperty("dailyCapUsd", "2500"));
        double remaining = Double.parseDouble(System.getProperty("remainingUsd", "3483"));
        double target = Double.parseDouble(System.getProperty("targetUsd", "5517"));
        List<String> symbols = List.of(System.getProperty("symbols",
                "BTCUSDT,ETHUSDT,SOLUSDT,XRPUSDT,DOGEUSDT,LINKUSDT,AVAXUSDT,ADAUSDT").split(","));

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        System.out.println("seeding realised volatility from recent daily candles...");
        Map<String, Double> vol = new LinkedHashMap<>();
        for (String symbol : symbols) {
            vol.put(symbol, dailyVolatility(http, symbol));
        }

        // REST polling rather than websockets. Two endpoints return every symbol in one call and a
        // 5-second refresh is ample for a sizing monitor, so this stays as it is.
        //
        // An earlier version of this comment blamed the environment: the futures websocket
        // "connects but never delivers a frame, while spot streams do". The symptom was real and the
        // diagnosis was wrong. Binance split futures streams into /public, /market and /private and
        // decommissioned the unified /ws URL on 2026-04-23; a legacy connection still handshakes and
        // still receives pings, so it looks alive while delivering nothing. Spot kept working because
        // it is on a different timeline. See BinanceGateway.WS.
        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                poll(http, symbols, vol);
                render(symbols, equity, dailyCap, remaining, target);
            } catch (Exception e) {
                System.err.println("poll failed: " + e.getMessage());
            }
        }, 0, 5, TimeUnit.SECONDS);

        Thread.currentThread().join();
    }

    /**
     * Sizing that keeps a one-sigma adverse day inside the daily cap.
     *
     * <p>{@code maxNotional = dailyCap / dailyVol}. At that size a 1-sigma down day exactly reaches
     * the cap, so roughly 16% of days breach on a normal approximation - and rather more in practice,
     * because crypto daily ranges have fatter tails than the normal and the cap keys off the intraday
     * low rather than the close. Treat it as an upper bound, not a target.
     */
    private static void render(List<String> symbols, double equity, double dailyCap,
                               double remaining, double target) {
        StringBuilder out = new StringBuilder();
        out.append("\033[H\033[2J");
        out.append("CHALLENGE MONITOR  ")
           .append(DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.now().atZone(ZoneOffset.UTC)))
           .append(" UTC   equity $").append(String.format("%,.0f", equity))
           .append("   need +$").append(String.format("%,.0f", target))
           .append("   budget left $").append(String.format("%,.0f", remaining))
           .append("   daily cap $").append(String.format("%,.0f", dailyCap)).append('\n');
        out.append("sizing only - no directional signal; three positioning signals were tested and refuted\n\n");
        out.append(String.format("%-11s %12s %9s %9s %10s %12s %11s %9s %11s%n",
                "symbol", "price", "24h", "dayVol", "funding", "SIZE", "moveToTgt", "moveToCap", "1sigmaMax"));
        out.append("-".repeat(103)).append('\n');

        for (String symbol : symbols) {
            Live live = STATE.get(symbol);
            if (live == null) {
                out.append(String.format("%-11s %12s%n", symbol, "waiting..."));
                continue;
            }
            // The 1-sigma bound (dailyCap / dailyVol) is far too loose in practice: it assumes the
            // cap is tested by the daily CLOSE, whereas it is tested by the intraday LOW, and crypto
            // daily ranges are fat-tailed. Simulated on 110,190 real paths, that bound corresponds to
            // roughly 3x leverage and a 21% pass rate, against 41% at 0.5x. So the headline number
            // here is the simulation-derived size, and the 1-sigma bound is shown only as a ceiling
            // that should not be approached.
            double suggested = equity * SIM_OPTIMAL_LEVERAGE;
            double ceiling = live.dailyVolPct() > 0
                    ? Math.min(dailyCap, remaining) / live.dailyVolPct() : 0;
            double moveToTarget = suggested > 0 ? target / suggested : Double.NaN;
            double capMove = suggested > 0 ? Math.min(dailyCap, remaining) / suggested : Double.NaN;
            out.append(String.format("%-11s %12.4f %8.2f%% %8.2f%% %9.4f%% %12s %10.2f%% %9.2f%% %11s%n",
                    symbol, live.price(), live.change24h(), live.dailyVolPct() * 100,
                    live.fundingRate() * 100, String.format("$%,.0f", suggested),
                    moveToTarget * 100, capMove * 100, String.format("$%,.0f", ceiling)));
        }
        out.append(String.format("%nSIZE      = %.2fx equity - the size with the highest simulated pass rate "
                + "(41%% vs 21%% at 3x).%n", SIM_OPTIMAL_LEVERAGE));
        out.append("moveToTgt = favourable price move needed to finish, at SIZE.\n");
        out.append("moveToCap = adverse price move that trips the daily cap, at SIZE. Wider is what keeps you alive.\n");
        out.append("1sigmaMax = the naive dailyCap/dailyVol bound. A CEILING, not a target - it breaches often.\n");
        System.out.print(out);
    }

    /** Realised daily volatility from the last 30 daily candles - the sizing input. */
    private static double dailyVolatility(HttpClient http, String symbol) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(
                "https://fapi.binance.com/fapi/v1/klines?symbol=" + symbol + "&interval=1d&limit=31"))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        List<Double> closes = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\[(\\d+),\"([-0-9.]+)\",\"([-0-9.]+)\",\"([-0-9.]+)\",\"([-0-9.]+)\"")
                .matcher(response.body());
        while (matcher.find()) {
            closes.add(Double.parseDouble(matcher.group(5)));
        }
        if (closes.size() < 5) return 0.03;
        List<Double> rets = new ArrayList<>();
        for (int i = 1; i < closes.size(); i++) {
            rets.add(closes.get(i) / closes.get(i - 1) - 1);
        }
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = rets.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / rets.size();
        return Math.sqrt(variance);
    }

    /** One call each for 24h stats and mark price/funding; both return the whole universe. */
    private static void poll(HttpClient http, List<String> symbols, Map<String, Double> vol)
            throws Exception {
        String tickers = get(http, "https://fapi.binance.com/fapi/v1/ticker/24hr");
        String premium = get(http, "https://fapi.binance.com/fapi/v1/premiumIndex");
        for (String symbol : symbols) {
            Double price = field(tickers, symbol, "lastPrice");
            Double change = field(tickers, symbol, "priceChangePercent");
            Double funding = field(premium, symbol, "lastFundingRate");
            if (price == null) continue;
            STATE.put(symbol, new Live(price, change == null ? 0 : change,
                    funding == null ? 0 : funding, vol.getOrDefault(symbol, 0.03)));
        }
    }

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
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private record Listener(Map<String, Double> vol) implements WebSocket.Listener {
        @Override public void onOpen(WebSocket webSocket) {
            System.out.println("connected to Binance futures stream");
            webSocket.request(1);
        }

        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            String text = data.toString();
            String symbol = extractString(text, "s");
            if (symbol != null) {
                Live previous = STATE.get(symbol);
                double price = extract(text, "p", extract(text, "c", previous == null ? 0 : previous.price()));
                double change = extract(text, "P", previous == null ? 0 : previous.change24h());
                double funding = extract(text, "r", previous == null ? 0 : previous.fundingRate());
                STATE.put(symbol, new Live(price, change, funding,
                        vol.getOrDefault(symbol, previous == null ? 0.03 : previous.dailyVolPct())));
            }
            webSocket.request(1);
            return null;
        }

        @Override public void onError(WebSocket webSocket, Throwable error) {
            System.err.println("stream error: " + error.getMessage());
        }

        private static double extract(String json, String field, double fallback) {
            Matcher m = Pattern.compile("\"" + field + "\":\"?([-0-9.eE]+)\"?").matcher(json);
            return m.find() ? Double.parseDouble(m.group(1)) : fallback;
        }

        private static String extractString(String json, String field) {
            Matcher m = Pattern.compile("\"" + field + "\":\"([A-Z0-9]+)\"").matcher(json);
            return m.find() ? m.group(1) : null;
        }
    }
}
