package com.smalistean.propstrategy.xvf.venue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Binance USD-M futures gateway: signed REST for orders, user data stream for fills.
 *
 * <h2>Credentials come from the environment, never the command line</h2>
 * {@code BINANCE_API_KEY} / {@code BINANCE_SECRET_KEY}. System properties were used previously and
 * are wrong for this: {@code -DbinanceApiKey=...} is visible in {@code ps aux} to every user on the
 * machine and is captured by any process listing in a crash dump or log. The secret is used only to
 * sign, is never logged, and {@link #toString()} is overridden so it cannot leak through a debug
 * print of the object.
 *
 * <h2>Post-only is GTX, capped taker is IOC</h2>
 * Binance spells post-only {@code timeInForce=GTX} ("good till crossing"): the order is rejected
 * rather than executed if it would take. The crossing order is a limit priced at the caller's worst
 * acceptable level with {@code timeInForce=IOC}, not {@code type=MARKET} — it still executes
 * immediately against available liquidity but cannot print through that price.
 *
 * <h2>An ambiguous submission is UNKNOWN</h2>
 * A timeout or 5xx after the request left the process may or may not have reached the matching
 * engine. Those return {@link SubmitOutcome#UNKNOWN} so the caller resolves them with
 * {@link #orderByClientId}; only an explicit 4xx from the venue is a rejection.
 *
 * <h2>listenKey lifetime</h2>
 * The user data stream key expires 60 minutes after creation and must be extended with a PUT. A
 * lapsed key does not error — the socket simply stops delivering, which would leave a filled maker
 * leg unhedged with nothing reporting it. The keepalive runs every 30 minutes and a failure to
 * extend is escalated rather than retried quietly.
 */
public final class BinanceGateway implements VenueGateway {

    private static final String REST = "https://fapi.binance.com";
    /**
     * User data goes to {@code /private}, not the legacy {@code /ws}.
     *
     * <p>Binance split futures WebSocket traffic into {@code /public} (high-frequency market data),
     * {@code /market} (regular market data) and {@code /private} (user data), and decommissioned the
     * unified {@code /ws} and {@code /stream} URLs on <b>2026-04-23</b>. A legacy connection still
     * completes its handshake and still receives server pings, so it looks healthy from every angle a
     * client can see - it simply never delivers user data again. That silence is indistinguishable
     * from "no fills happened", which is why this cost a live unhedged leg to find.
     */
    private static final String WS = "wss://fstream.binance.com/private/ws/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final byte[] apiSecret;
    private final boolean dryRun;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, SymbolRules> ruleCache = new ConcurrentHashMap<>();

    public BinanceGateway(boolean dryRun) {
        this.apiKey = System.getenv().getOrDefault("BINANCE_API_KEY", "DUMMY_BINANCE_KEY");
        this.apiSecret = System.getenv().getOrDefault("BINANCE_SECRET_KEY", "DUMMY_BINANCE_SECRET")
                .getBytes(StandardCharsets.UTF_8);
        this.dryRun = dryRun;
    }

    @Override
    public String name() {
        return "binance";
    }

    @Override
    public SubmitResult placePostOnly(String venueSymbol, Side side, BigDecimal quantity,
                                      BigDecimal limitPrice, String clientOrderId,
                                      boolean reduceOnly) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", venueSymbol);
        params.put("side", side.name());
        params.put("type", "LIMIT");
        params.put("timeInForce", "GTX");          // post-only: reject rather than take
        params.put("quantity", quantity.toPlainString());
        params.put("price", VenueGateway.roundToTick(
                limitPrice, rules(venueSymbol).tickSize(), side, false).toPlainString());
        params.put("newClientOrderId", clientOrderId);
        if (reduceOnly) {
            params.put("reduceOnly", "true");
        }
        return send("POST", "/fapi/v1/order", params, venueSymbol, clientOrderId);
    }

    @Override
    public SubmitResult placeCappedIoc(String venueSymbol, Side side, BigDecimal quantity,
                                       BigDecimal worstPrice, String clientOrderId,
                                       boolean reduceOnly) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", venueSymbol);
        params.put("side", side.name());
        params.put("type", "LIMIT");
        params.put("timeInForce", "IOC");          // crosses now, cancels the rest, never prints worse
        params.put("quantity", quantity.toPlainString());
        params.put("price", VenueGateway.roundToTick(
                worstPrice, rules(venueSymbol).tickSize(), side, true).toPlainString());
        params.put("newClientOrderId", clientOrderId);
        if (reduceOnly) {
            params.put("reduceOnly", "true");
        }
        return send("POST", "/fapi/v1/order", params, venueSymbol, clientOrderId);
    }

    @Override
    public java.util.List<PositionSnapshot> positions() {
        if (dryRun) {
            return java.util.List.of();
        }
        java.util.List<PositionSnapshot> out = new java.util.ArrayList<>();
        try {
            JsonNode body = MAPPER.readTree(signedGet("/fapi/v3/positionRisk", new HashMap<>()));
            for (JsonNode p : body) {
                BigDecimal amt = new BigDecimal(p.path("positionAmt").asText("0"));
                if (amt.signum() != 0) {
                    // positionAmt is already signed on Binance: negative is short.
                    out.add(new PositionSnapshot("binance", p.path("symbol").asText(), amt,
                            new BigDecimal(p.path("entryPrice").asText("0"))));
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("binance positionRisk unparsed", e);
        }
        return out;
    }

    @Override
    public void cancel(OrderHandle handle) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", handle.venueSymbol());
        params.put("origClientOrderId", handle.clientOrderId());
        send("DELETE", "/fapi/v1/order", params, handle.venueSymbol(), handle.clientOrderId());
    }

    @Override
    public Optional<OrderSnapshot> orderByClientId(String venueSymbol, String clientOrderId) {
        if (dryRun) {
            return Optional.empty();
        }
        Map<String, String> params = new HashMap<>();
        params.put("symbol", venueSymbol);
        params.put("origClientOrderId", clientOrderId);
        try {
            JsonNode body = MAPPER.readTree(signedGet("/fapi/v1/order", params));
            if (body.hasNonNull("code")) {
                int code = body.path("code").asInt();
                if (code == -2013) {
                    return Optional.empty();   // "Order does not exist": it never reached the venue
                }
                // Any OTHER error code is a failure to ask, not an answer - a bad signature (-1022),
                // an expired key, a rate limit. Returning empty would tell the caller the order was
                // never placed, which it treats as a rejection, so a broken key would mark every
                // ambiguous submission rejected while real orders rested untracked.
                throw new IllegalStateException("binance order lookup code " + code + ": "
                        + body.path("msg").asText());
            }
            return Optional.of(new OrderSnapshot(
                    new OrderHandle("binance", venueSymbol, body.path("orderId").asText(""),
                            clientOrderId),
                    parseState(body.path("status").asText()),
                    new BigDecimal(body.path("executedQty").asText("0")),
                    new BigDecimal(body.path("avgPrice").asText("0"))));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("binance order lookup unparsed", e);
        }
    }

    @Override
    public TopOfBook topOfBook(String venueSymbol) {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(
                                    REST + "/fapi/v1/ticker/bookTicker?symbol=" + venueSymbol))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("bookTicker HTTP " + response.statusCode()
                        + " for " + venueSymbol);
            }
            JsonNode body = MAPPER.readTree(response.body());
            return new TopOfBook(new BigDecimal(body.path("bidPrice").asText()),
                    new BigDecimal(body.path("askPrice").asText()),
                    body.path("time").asLong(System.currentTimeMillis()));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("bookTicker failed for " + venueSymbol, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /**
     * Sends a signed request and classifies the outcome.
     *
     * <p>A 4xx carries a venue decision and is a rejection. Anything else — timeout, connection
     * reset, 5xx — leaves the outcome genuinely unknown, because the order may already be resting.
     */
    private SubmitResult send(String method, String path, Map<String, String> params,
                              String venueSymbol, String clientId) {
        OrderHandle handle = new OrderHandle("binance", venueSymbol, "", clientId);
        if (dryRun) {
            System.out.printf("  [dry-run] binance %s %s %s%n", method, path, params);
            return new SubmitResult(SubmitOutcome.ACCEPTED,
                    new OrderHandle("binance", venueSymbol, "DRYRUN", clientId), "dry run");
        }
        params.put("timestamp", Long.toString(System.currentTimeMillis()));
        params.put("recvWindow", "5000");
        String signed = query(params) + "&signature=" + sign(query(params));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(REST + path + "?" + signed))
                .header("X-MBX-APIKEY", apiKey).timeout(Duration.ofSeconds(15));
        HttpRequest request = switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
            case "DELETE" -> builder.DELETE().build();
            default -> builder.GET().build();
        };
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 200) {
                JsonNode body = MAPPER.readTree(response.body());
                return new SubmitResult(SubmitOutcome.ACCEPTED,
                        new OrderHandle("binance", venueSymbol, body.path("orderId").asText(""),
                                clientId), "ok");
            }
            if (status >= 400 && status < 500) {
                return new SubmitResult(SubmitOutcome.REJECTED, handle,
                        "HTTP " + status + ": " + response.body());
            }
            return new SubmitResult(SubmitOutcome.UNKNOWN, handle,
                    "HTTP " + status + ": " + response.body());
        } catch (java.io.IOException e) {
            // The request left this process; whether it reached the matching engine is unknowable
            // from here. Resolve with orderByClientId rather than retrying.
            return new SubmitResult(SubmitOutcome.UNKNOWN, handle, "io: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SubmitResult(SubmitOutcome.UNKNOWN, handle, "interrupted");
        }
    }

    private String signedGet(String path, Map<String, String> params) {
        params.put("timestamp", Long.toString(System.currentTimeMillis()));
        params.put("recvWindow", "5000");
        String signed = query(params) + "&signature=" + sign(query(params));
        try {
            return http.send(HttpRequest.newBuilder(URI.create(REST + path + "?" + signed))
                            .header("X-MBX-APIKEY", apiKey).timeout(Duration.ofSeconds(15))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("binance " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static String query(Map<String, String> params) {
        return params.entrySet().stream()
                .filter(e -> !"signature".equals(e.getKey()))
                .map(e -> e.getKey() + "=" + urlEncode(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
    }

    @Override
    public AutoCloseable streamOrderUpdates(Consumer<OrderUpdate> listener) {
        if (dryRun) {
            System.out.println("  [dry-run] binance user data stream not opened");
            return () -> { };
        }
        String listenKey = createListenKey();
        // Extend well inside the 60-minute expiry. A lapsed key stops delivering silently, and a
        // silent stream with a filled maker leg is the worst state this system has.
        keepalive.scheduleAtFixedRate(() -> {
            try {
                keepAlive(listenKey);
            } catch (RuntimeException e) {
                System.out.printf("!!!! binance listenKey keepalive FAILED: %s — fills may stop "
                        + "arriving with no further error%n", e.getMessage());
            }
        }, 30, 30, TimeUnit.MINUTES);

        WebSocket socket = http.newWebSocketBuilder()
                .buildAsync(URI.create(WS + listenKey), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            handle(buffer.toString(), listener);
                            buffer.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        System.out.printf("!!!! binance user stream error: %s — treat as fills "
                                + "possibly missed, reconcile by polling%n", error.getMessage());
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                        System.out.printf("!!!! binance user stream closed (%d %s)%n", code, reason);
                        return null;
                    }
                }).join();
        return () -> {
            socket.abort();
            keepalive.shutdownNow();
        };
    }

    /** Parses ORDER_TRADE_UPDATE. Other event types on this stream are ignored. */
    private void handle(String frame, Consumer<OrderUpdate> listener) {
        try {
            JsonNode node = MAPPER.readTree(frame);
            if (!"ORDER_TRADE_UPDATE".equals(node.path("e").asText())) {
                return;
            }
            JsonNode o = node.path("o");
            // 'z' is CUMULATIVE filled quantity for the order, not this fill's size. The engine
            // differences it against a watermark; passing it through unchanged is intentional.
            listener.accept(new OrderUpdate("binance", o.path("s").asText(), o.path("c").asText(),
                    parseState(o.path("X").asText()), new BigDecimal(o.path("z").asText("0")),
                    new BigDecimal(o.path("ap").asText("0")), node.path("E").asLong()));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            System.out.printf("!! binance stream frame unparsed: %s%n", e.getMessage());
        }
    }

    private static OrderState parseState(String venueStatus) {
        return switch (venueStatus) {
            case "NEW" -> OrderState.RESTING;
            case "PARTIALLY_FILLED" -> OrderState.PARTIALLY_FILLED;
            case "FILLED" -> OrderState.FILLED;
            case "CANCELED", "EXPIRED" -> OrderState.CANCELLED;
            case "REJECTED" -> OrderState.REJECTED;
            default -> OrderState.RESTING;
        };
    }

    private String createListenKey() {
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(REST + "/fapi/v1/listenKey"))
                            .header("X-MBX-APIKEY", apiKey)
                            .POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("listenKey HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return MAPPER.readTree(response.body()).path("listenKey").asText();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("listenKey failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private void keepAlive(String listenKey) {
        try {
            http.send(HttpRequest.newBuilder(URI.create(REST + "/fapi/v1/listenKey"))
                            .header("X-MBX-APIKEY", apiKey)
                            .method("PUT", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("keepalive failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    @Override
    public SymbolRules rules(String venueSymbol) {
        return ruleCache.computeIfAbsent(venueSymbol, symbol -> {
            try {
                HttpResponse<String> response = http.send(
                        HttpRequest.newBuilder(URI.create(REST + "/fapi/v1/exchangeInfo"))
                                .timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                for (JsonNode s : MAPPER.readTree(response.body()).path("symbols")) {
                    if (!symbol.equals(s.path("symbol").asText())) {
                        continue;
                    }
                    BigDecimal step = BigDecimal.ONE;
                    BigDecimal tick = BigDecimal.ONE;
                    BigDecimal minNotional = new BigDecimal("5");
                    // Read the FILTERS, not pricePrecision/quantityPrecision - Binance documents
                    // explicitly that those are display precision, not tick and step size.
                    for (JsonNode f : s.path("filters")) {
                        switch (f.path("filterType").asText()) {
                            case "LOT_SIZE" -> step = new BigDecimal(f.path("stepSize").asText());
                            case "PRICE_FILTER" -> tick = new BigDecimal(f.path("tickSize").asText());
                            case "MIN_NOTIONAL" -> minNotional = new BigDecimal(f.path("notional").asText());
                            default -> { }
                        }
                    }
                    return new SymbolRules(step, minNotional, tick);
                }
                throw new IllegalStateException("no rules for " + symbol);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("exchangeInfo failed", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", e);
            }
        });
    }

    private String sign(String query) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret, "HmacSHA256"));
            byte[] raw = mac.doFinal(query.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Never render credentials, even accidentally through a debug print of this object. */
    @Override
    public String toString() {
        return "BinanceGateway[dryRun=" + dryRun + "]";
    }
}
