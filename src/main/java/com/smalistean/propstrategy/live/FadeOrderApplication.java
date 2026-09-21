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
import java.util.ArrayList;
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
 *       $3,000 (the measured per-name cap) it refuses outright. A symbol that already holds a
 *       position is refused, so a re-run after a timeout cannot double a name.</li>
 *   <li><b>Leverage and margin mode are set explicitly before every open.</b> Default 1x, hard
 *       cap 5x; the slider changes liquidation distance and margin used, not notional risk. Default
 *       margin mode ISOLATED so a single-name disaster costs its posted margin, never the wallet
 *       (Plan S rule R4). The exchange default on a never-traded symbol is 20x cross.</li>
 *   <li><b>Pre-flight before any live order, and on demand with {@code -Daction=check}:</b>
 *       signed reads of balance, position mode (must be one-way) and asset mode (must be
 *       single-asset). The key refused this machine once (-2015, IP allowlist) three minutes
 *       before an exit; run {@code check} from the exit machine before the exit.</li>
 *   <li><b>It looks back.</b> Orders are sent with {@code newOrderRespType=RESULT} so the fill
 *       status, quantity and average price are printed, and every live run ends by listing the
 *       account's open positions - the actual basket, not the intended one. Any symbol that
 *       failed is tallied and the exit code is non-zero.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>
 *   source .env.binance   # BINANCE_API_KEY / BINANCE_SECRET_KEY
 *   -Daction=open|close|check   (default open); check = signed reads only, never an order
 *   -Dsymbols=AXTI,COIN   base tickers or full *USDT symbols, comma-separated
 *   -DvalueUsd=300        notional per symbol for open (ignored for close)
 *   -Dlive=true           actually send orders (default false = dry run)
 *   -DconfirmLarge=true   required when valueUsd exceeds 1000
 *   -Dleverage=3          1..5, default 1 (set per symbol before the order)
 *   -DmarginType=ISOLATED ISOLATED (default) or CROSS, set per symbol before the order
 * </pre>
 */
public final class FadeOrderApplication {

    private static final String BASE = "https://fapi.binance.com";
    private static final double HARD_CAP_USD = 3000;
    private static final double CONFIRM_CAP_USD = 1000;
    private static final int MAX_LEVERAGE = 5;

    public static void main(String[] args) throws Exception {
        String action = System.getProperty("action", "open").toLowerCase(Locale.ROOT);
        boolean live = Boolean.parseBoolean(System.getProperty("live", "false"));
        double valueUsd = Double.parseDouble(System.getProperty("valueUsd", "300"));
        String symbolsRaw = System.getProperty("symbols", "");
        if (!action.equals("open") && !action.equals("close") && !action.equals("check")) {
            System.err.println("-Daction must be open, close or check");
            System.exit(2);
        }
        if (!action.equals("check") && symbolsRaw.isBlank()) {
            System.err.println("no -Dsymbols given, e.g. -Dsymbols=AXTI,COIN");
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
        int leverage;
        try {
            leverage = Integer.parseInt(System.getProperty("leverage", "1").trim());
        } catch (NumberFormatException e) {
            System.err.println("-Dleverage must be an integer 1.." + MAX_LEVERAGE);
            System.exit(2);
            return;
        }
        String marginType = System.getProperty("marginType", "ISOLATED").toUpperCase(Locale.ROOT);
        if (leverage < 1 || leverage > MAX_LEVERAGE) {
            System.err.printf("-Dleverage must be 1..%d (got %d)%n", MAX_LEVERAGE, leverage);
            System.exit(2);
        }
        if (!marginType.equals("ISOLATED") && !marginType.equals("CROSS")) {
            System.err.println("-DmarginType must be ISOLATED or CROSS");
            System.exit(2);
        }

        String apiKey = env("BINANCE_API_KEY");
        String secret = env("BINANCE_SECRET_KEY");
        boolean needKeys = live || action.equals("check");
        if (needKeys && (apiKey == null || secret == null)) {
            System.err.println("live and check need BINANCE_API_KEY and BINANCE_SECRET_KEY "
                    + "(source .env.binance)");
            System.exit(2);
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

        if (action.equals("check")) {
            preflight(http, apiKey, secret, 0, Double.parseDouble(System.getProperty("checkNotional", "0")));
            listPositions(http, apiKey, secret);
            System.out.println("check complete - no order was sent.");
            return;
        }

        List<String> symbols = java.util.Arrays.stream(symbolsRaw.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .map(s -> s.endsWith("USDT") ? s : s + "USDT")
                .distinct()
                .toList();

        System.out.printf("%s  %s  %s%n", live ? "LIVE" : "DRY RUN",
                action.toUpperCase(Locale.ROOT), String.join(" ", symbols));
        if (action.equals("open")) {
            double margin = valueUsd * symbols.size() / leverage;
            System.out.printf("leverage %dx %s  per-name notional $%.0f  total notional $%.0f  margin to post ~$%.0f"
                    + "  liquidation ~%.0f%% below entry per name, before the 1.5%% liquidation fee%n%n",
                    leverage, marginType, valueUsd, valueUsd * symbols.size(), margin,
                    100.0 / leverage - 1.0);
        } else {
            System.out.println();
        }
        if (live) {
            double needed = action.equals("open") ? valueUsd * symbols.size() / leverage * 1.05 : 0;
            preflight(http, apiKey, secret, needed, action.equals("open") ? valueUsd * symbols.size() : 0);
        }
        String exchangeInfo = get(http, BASE + "/fapi/v1/exchangeInfo");

        List<String> failed = new ArrayList<>();
        for (String symbol : symbols) {
            try {
                if (action.equals("open")) {
                    open(http, exchangeInfo, symbol, valueUsd, leverage, marginType, live, apiKey, secret);
                } else {
                    close(http, symbol, live, apiKey, secret);
                }
            } catch (Exception e) {
                failed.add(symbol);
                // stdout as well as stderr, so a captured log carries the failure next to the fills
                System.out.printf("%-10s FAILED: %s%n", symbol, e.getMessage());
                System.err.printf("%-10s FAILED: %s%n", symbol, e.getMessage());
            }
        }
        if (!live) {
            System.out.println("\ndry run - nothing was sent. Add -Dlive=true to place the orders.");
            return;
        }
        System.out.printf("%nsent %d of %d", symbols.size() - failed.size(), symbols.size());
        if (!failed.isEmpty()) {
            System.out.printf("; FAILED: %s - treat each as UNKNOWN until the positions below say otherwise",
                    String.join(" ", failed));
        }
        System.out.println();
        listPositions(http, apiKey, secret);
        if (!failed.isEmpty()) {
            System.exit(1);
        }
    }

    /** Signed reads only: key works from this machine, balance, one-way mode, single-asset mode. */
    private static void preflight(HttpClient http, String apiKey, String secret, double neededUsd)
            throws Exception {
        preflight(http, apiKey, secret, neededUsd, 0);
    }

    /** @param roundTripNotional total notional of the intended open, for the BNB fee estimate; 0 to skip. */
    private static void preflight(HttpClient http, String apiKey, String secret, double neededUsd,
                                  double roundTripNotional) throws Exception {
        String bal = signed(http, apiKey, secret, "/fapi/v2/balance", "");
        Matcher m = Pattern.compile("\"asset\":\"USDT\".*?\"availableBalance\":\"([0-9.]+)\"").matcher(bal);
        double available = m.find() ? Double.parseDouble(m.group(1)) : -1;
        Matcher bm = Pattern.compile("\"asset\":\"BNB\".*?\"balance\":\"([0-9.]+)\"").matcher(bal);
        double bnb = bm.find() ? Double.parseDouble(bm.group(1)) : 0;
        String dual = signed(http, apiKey, secret, "/fapi/v1/positionSide/dual", "");
        String multi = signed(http, apiKey, secret, "/fapi/v1/multiAssetsMargin", "");
        boolean hedge = dual.contains("\"dualSidePosition\":true");
        boolean multiAsset = multi.contains("\"multiAssetsMargin\":true");
        System.out.printf("pre-flight: signed read OK  USDT available %.2f  position mode %s  asset mode %s%n",
                available, hedge ? "HEDGE" : "one-way", multiAsset ? "MULTI-ASSET" : "single-asset");
        bnbFeeCheck(http, apiKey, secret, bnb, roundTripNotional);
        if (hedge || multiAsset) {
            System.err.println("refusing: this tool assumes one-way position mode and single-asset margin mode "
                    + "(BUY without positionSide, reduceOnly close, ISOLATED allowed). Change it in Binance "
                    + "Preferences first.");
            System.exit(2);
        }
        if (neededUsd > 0 && available < neededUsd) {
            System.err.printf("refusing: available %.2f is below the ~%.2f margin these orders need (plus 5%%)%n",
                    available, neededUsd);
            System.exit(2);
        }
    }

    /**
     * House rule: fees are always paid in BNB. Prints the BNB balance in coin and dollars, whether fee
     * burn is switched on, and - when a notional is supplied - the estimated round-trip fee and how many
     * more weekends the balance covers. Warns rather than refuses: with no BNB, Binance charges the fee in
     * USDT and the order still fills; the only loss is the 10% BNB discount.
     */
    private static void bnbFeeCheck(HttpClient http, String apiKey, String secret, double bnb,
                                    double roundTripNotional) throws Exception {
        double px = 0;
        try {
            px = price(http, "BNBUSDT");
        } catch (Exception ignored) {
            // a missing price must not stop a live run; the balance line below still prints in coin
        }
        boolean burnOn = true;
        try {
            burnOn = signed(http, apiKey, secret, "/fapi/v1/feeBurn", "").contains("\"feeBurn\":true");
        } catch (Exception ignored) {
            // older keys may not carry this permission; assume on and say so
        }
        double takerRate = 0.0004;
        try {
            Matcher r = Pattern.compile("\"takerCommissionRate\":\"([0-9.]+)\"")
                    .matcher(signed(http, apiKey, secret, "/fapi/v1/commissionRate", "symbol=BTCUSDT"));
            if (r.find()) takerRate = Double.parseDouble(r.group(1));
        } catch (Exception ignored) {
            // fall back to the VIP-0 taker rate
        }
        double effective = burnOn ? takerRate * 0.9 : takerRate;
        System.out.printf("           BNB %.6f (~$%.2f)  fee burn %s  taker %.4f%%%s%n",
                bnb, bnb * px, burnOn ? "ON" : "*** OFF ***", effective * 100,
                burnOn ? "" : "  <- turn BNB fee burn ON in the Binance futures UI");
        if (roundTripNotional > 0 && px > 0) {
            double fee = roundTripNotional * effective * 2;
            double weekends = fee > 0 ? bnb * px / fee : 0;
            System.out.printf("           estimated round-trip fee $%.2f (%.6f BNB); balance covers ~%.1f more weekends at this size%n",
                    fee, fee / px, weekends);
            if (bnb * px < fee) {
                System.out.printf("           *** WARNING: BNB will not cover this round trip. Binance will charge USDT "
                        + "instead (order still fills, 10%% discount lost). Top up BNB in the futures wallet.%n");
            }
        }
    }

    /** Every open position on the account, from positionRisk v3 (which returns only non-flat symbols). */
    private static void listPositions(HttpClient http, String apiKey, String secret) throws Exception {
        String body = signed(http, apiKey, secret, "/fapi/v3/positionRisk", "");
        Matcher obj = Pattern.compile("\\{[^{}]*\\}").matcher(body);
        int n = 0;
        System.out.println("open positions now:");
        while (obj.find()) {
            String o = obj.group();
            String sym = field(o, "symbol"), amt = field(o, "positionAmt"), entry = field(o, "entryPrice"),
                   upnl = field(o, "unRealizedProfit"), liq = field(o, "liquidationPrice"),
                   notional = field(o, "notional");
            if (amt == null || new BigDecimal(amt).signum() == 0) continue;
            n++;
            System.out.printf("  %-10s qty %-10s entry %-10s notional %-10s uPnL %-10s liq %s%n",
                    sym, amt, entry, notional, upnl, liq);
        }
        if (n == 0) System.out.println("  none");
    }

    private static String field(String obj, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"?([-0-9.A-Za-z]+)\"?").matcher(obj);
        return m.find() ? m.group(1) : null;
    }

    private static void open(HttpClient http, String exchangeInfo, String symbol, double valueUsd,
                             int leverage, String marginType, boolean live, String apiKey,
                             String secret) throws Exception {
        double price = price(http, symbol);
        BigDecimal step = lotStep(exchangeInfo, symbol);
        BigDecimal qty = BigDecimal.valueOf(valueUsd / price)
                .divide(step, 0, RoundingMode.DOWN).multiply(step).stripTrailingZeros();
        double notional = qty.doubleValue() * price;
        if (qty.signum() <= 0 || notional < 5) {
            throw new IllegalStateException(String.format(
                    "quantity rounds to %s (notional $%.2f) - below Binance's $5 minimum", qty, notional));
        }
        System.out.printf("%-10s price %.4f  qty %s  notional $%.2f  -> %s %dx, MARKET BUY%n",
                symbol, price, qty.toPlainString(), notional, marginType, leverage);
        if (live) {
            // Refuse to add to a name already held: a re-run after a timeout must not double it.
            String pos = signed(http, apiKey, secret, "/fapi/v3/positionRisk", "symbol=" + symbol);
            Matcher pm = Pattern.compile("\"positionAmt\":\"([-0-9.]+)\"").matcher(pos);
            if (pm.find() && new BigDecimal(pm.group(1)).signum() != 0) {
                throw new IllegalStateException("already holds " + pm.group(1) + " - refusing to add");
            }
            // Margin mode first, then leverage, then the order. Binance answers -4046 when the mode
            // is already what was asked for; that is success, not a failure to stop on.
            try {
                signed(http, apiKey, secret, "/fapi/v1/marginType",
                        "symbol=" + symbol + "&marginType=" + marginType);
            } catch (IllegalStateException e) {
                if (!e.getMessage().contains("-4046")) throw e;
            }
            signed(http, apiKey, secret, "/fapi/v1/leverage",
                    "symbol=" + symbol + "&leverage=" + leverage);
            String r = signed(http, apiKey, secret, "/fapi/v1/order",
                    "symbol=" + symbol + "&side=BUY&type=MARKET&newOrderRespType=RESULT&quantity="
                            + qty.toPlainString());
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
                "symbol=" + symbol + "&side=SELL&type=MARKET&reduceOnly=true&newOrderRespType=RESULT&quantity="
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

    private static final List<String> GET_PATHS = List.of(
            "positionRisk", "/balance", "positionSide/dual", "multiAssetsMargin");

    /** Signed request: GET for the read endpoints above, POST otherwise. Throws on non-200. */
    private static String signed(HttpClient http, String apiKey, String secret, String path,
                                 String params) throws Exception {
        String query = (params.isEmpty() ? "" : params + "&") + "recvWindow=10000&timestamp="
                + System.currentTimeMillis();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = HexFormat.of().formatHex(
                mac.doFinal(query.getBytes(StandardCharsets.UTF_8)));
        String url = BASE + path + "?" + query + "&signature=" + signature;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("X-MBX-APIKEY", apiKey).timeout(Duration.ofSeconds(20));
        boolean isGet = GET_PATHS.stream().anyMatch(path::contains);
        HttpRequest request = isGet ? b.GET().build() : b.POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    /** RESULT responses carry the fill; print what actually happened, not just the id. */
    private static String summary(String orderResponse) {
        String id = field(orderResponse, "orderId"), st = field(orderResponse, "status"),
               exq = field(orderResponse, "executedQty"), avg = field(orderResponse, "avgPrice"),
               cum = field(orderResponse, "cumQuote");
        if (id == null) return orderResponse;
        return String.format("orderId %s %s  filled %s @ %s  ($%s)", id, st, exq, avg, cum);
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
