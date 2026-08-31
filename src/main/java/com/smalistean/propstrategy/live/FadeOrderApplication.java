package com.smalistean.propstrategy.live;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manual-trigger order tool for the weekend fade on the user's OWN Binance USDT-M account.
 *
 * <h2>Scope and guardrails</h2>
 * This exists for the personal-capital variant of {@code WEEKEND_FADE_LIVE_SPEC.md} - it must
 * NEVER be pointed at the prop challenge account (the firm requires manual execution there, and
 * that account is on a different platform anyway). Deliberate restrictions, matching the
 * measured strategy and the house rules:
 * <ul>
 *   <li><b>Dry-run by default.</b> Without {@code -Dlive=true} it prints the exact orders it
 *       would send and sends nothing. The operator - not this program's author - flips the flag:
 *       the house rule is that Claude builds and dry-runs, the user places.</li>
 *   <li><b>Long only.</b> {@code open} only ever BUYs (the mirror short is measured at
 *       -70 bp/weekend and closed); {@code close} only ever SELLs with {@code reduceOnly}, so
 *       it can flatten but never flip.</li>
 *   <li><b>Size caps.</b> Per-name value above $1,000 needs {@code -DconfirmLarge=true}; above
 *       $3,000 (the measured per-name cap) it refuses outright.</li>
 *   <li><b>Leverage pinned to 1x</b> before every open - the slider changes liquidation
 *       distance, not risk, and 1x makes liquidation unreachable.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   source .env.binance   # BINANCE_API_KEY / BINANCE_SECRET_KEY
 *   -Daction=open|close   what to do (default open)
 *   -Dsymbols=AXTI,COIN   base tickers or full *USDT symbols, comma-separated
 *   -DvalueUsd=300        notional per symbol for open (ignored for close)
 *   -Dlive=true           actually send orders (default false = dry run)
 *   -DconfirmLarge=true   required when valueUsd exceeds 1000
 * </pre>
 * Example (Sunday 20:00 UTC entry, dry run):
 * {@code java ... -Dsymbols=AXTI -DvalueUsd=300 -cp target/classes ...FadeOrderApplication}
 */
public final class FadeOrderApplication {

    private static final String BASE = "https://fapi.binance.com";
    private static final double HARD_CAP_USD = 3000;
    private static final double CONFIRM_CAP_USD = 1000;

    public static void main(String[] args) throws Exception {
        String action = System.getProperty("action", "open").toLowerCase(Locale.ROOT);
        boolean live = Boolean.parseBoolean(System.getProperty("live", "false"));
        double valueUsd = Double.parseDouble(System.getProperty("valueUsd", "300"));
        String symbolsRaw = System.getProperty("symbols", "");
        if (symbolsRaw.isBlank()) {
            System.err.println("no -Dsymbols given, e.g. -Dsymbols=AXTI,COIN");
            System.exit(2);
        }
        if (!action.equals("open") && !action.equals("close")) {
            System.err.println("-Daction must be open or close");
            System.exit(2);
        }
        if (action.equals("open") && valueUsd > HARD_CAP_USD) {
            System.err.printf("refusing: valueUsd %.0f exceeds the measured per-name cap %.0f%n",
                    valueUsd, HARD_CAP_USD);
            System.exit(2);
        }
        if (action.equals("open") && valueUsd > CONFIRM_CAP_USD
                && !Boolean.parseBoolean(System.getProperty("confirmLarge", "false"))) {
            System.err.printf("valueUsd %.0f > %.0f needs -DconfirmLarge=true%n",
                    valueUsd, CONFIRM_CAP_USD);
            System.exit(2);
        }

        String apiKey = env("BINANCE_API_KEY");
        String secret = env("BINANCE_SECRET_KEY");
        if (live && (apiKey == null || secret == null)) {
            System.err.println("live mode needs BINANCE_API_KEY and BINANCE_SECRET_KEY "
                    + "(source .env.binance)");
            System.exit(2);
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        List<String> symbols = java.util.Arrays.stream(symbolsRaw.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .map(s -> s.endsWith("USDT") ? s : s + "USDT")
                .toList();

        System.out.printf("%s  %s  %s%n%n", live ? "LIVE" : "DRY RUN",
                action.toUpperCase(Locale.ROOT), String.join(" ", symbols));
        String exchangeInfo = get(http, BASE + "/fapi/v1/exchangeInfo");

        for (String symbol : symbols) {
            try {
                if (action.equals("open")) {
                    open(http, exchangeInfo, symbol, valueUsd, live, apiKey, secret);
                } else {
                    close(http, symbol, live, apiKey, secret);
                }
            } catch (Exception e) {
                System.err.printf("%-10s FAILED: %s%n", symbol, e.getMessage());
            }
        }
        if (!live) {
            System.out.println("\ndry run - nothing was sent. Add -Dlive=true to place the orders.");
        }
    }

    private static void open(HttpClient http, String exchangeInfo, String symbol, double valueUsd,
                             boolean live, String apiKey, String secret) throws Exception {
        double price = price(http, symbol);
        BigDecimal step = lotStep(exchangeInfo, symbol);
        BigDecimal qty = BigDecimal.valueOf(valueUsd / price)
                .divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros();
        double notional = qty.doubleValue() * price;
        if (qty.signum() <= 0 || notional < 5) {
            throw new IllegalStateException(String.format(
                    "quantity rounds to %s (notional $%.2f) - below Binance's $5 minimum", qty, notional));
        }
        System.out.printf("%-10s price %.4f  qty %s  notional $%.2f  -> MARKET BUY, leverage 1x%n",
                symbol, price, qty.toPlainString(), notional);
        if (live) {
            signed(http, apiKey, secret, "/fapi/v1/leverage",
                    "symbol=" + symbol + "&leverage=1");
            String r = signed(http, apiKey, secret, "/fapi/v1/order",
                    "symbol=" + symbol + "&side=BUY&type=MARKET&quantity=" + qty.toPlainString());
            System.out.printf("%-10s sent: %s%n", symbol, summary(r));
        }
    }

    private static void close(HttpClient http, String symbol, boolean live, String apiKey,
                              String secret) throws Exception {
        if (!live) {
            System.out.printf("%-10s would fetch position and MARKET SELL it reduceOnly "
                    + "(needs keys+live to read the position)%n", symbol);
            return;
        }
        String pos = signed(http, apiKey, secret, "/fapi/v3/positionRisk", "symbol=" + symbol);
        Matcher m = Pattern.compile("\"positionAmt\":\"([-0-9.]+)\"").matcher(pos);
        BigDecimal amt = m.find() ? new BigDecimal(m.group(1)) : BigDecimal.ZERO;
        if (amt.signum() <= 0) {
            System.out.printf("%-10s no long position open (amt=%s), nothing to close%n", symbol, amt);
            return;
        }
        System.out.printf("%-10s closing long of %s -> MARKET SELL reduceOnly%n",
                symbol, amt.toPlainString());
        String r = signed(http, apiKey, secret, "/fapi/v1/order",
                "symbol=" + symbol + "&side=SELL&type=MARKET&reduceOnly=true&quantity="
                        + amt.stripTrailingZeros().toPlainString());
        System.out.printf("%-10s sent: %s%n", symbol, summary(r));
    }

    // --- Binance plumbing ----------------------------------------------------------------------

    private static double price(HttpClient http, String symbol) throws Exception {
        String body = get(http, BASE + "/fapi/v1/ticker/price?symbol=" + symbol);
        Matcher m = Pattern.compile("\"price\":\"([0-9.]+)\"").matcher(body);
        if (!m.find()) throw new IllegalStateException("no price: " + body);
        return Double.parseDouble(m.group(1));
    }

    private static BigDecimal lotStep(String exchangeInfo, String symbol) {
        int at = exchangeInfo.indexOf("\"symbol\":\"" + symbol + "\"");
        if (at < 0) throw new IllegalStateException("symbol not in exchangeInfo");
        Matcher m = Pattern.compile("\"stepSize\":\"([0-9.]+)\"")
                .matcher(exchangeInfo.substring(at, Math.min(at + 4000, exchangeInfo.length())));
        if (!m.find()) throw new IllegalStateException("no LOT_SIZE stepSize");
        return new BigDecimal(m.group(1)).stripTrailingZeros();
    }

    /** POSTs a signed request; GETs when the path is positionRisk. Throws on non-200. */
    private static String signed(HttpClient http, String apiKey, String secret, String path,
                                 String params) throws Exception {
        String query = params + "&recvWindow=10000&timestamp=" + System.currentTimeMillis();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(
                mac.doFinal(query.getBytes(StandardCharsets.UTF_8)));
        String url = BASE + path + "?" + query + "&signature=" + signature;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("X-MBX-APIKEY", apiKey).timeout(Duration.ofSeconds(20));
        HttpRequest request = path.contains("positionRisk")
                ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static String summary(String orderResponse) {
        Matcher m = Pattern.compile(
                "\"orderId\":(\\d+).*?\"status\":\"([A-Z_]+)\"").matcher(orderResponse);
        return m.find() ? "orderId " + m.group(1) + " " + m.group(2) : orderResponse;
    }

    private static String get(HttpClient http, String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }
}
