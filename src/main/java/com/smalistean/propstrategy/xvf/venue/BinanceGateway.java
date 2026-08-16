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
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Binance USD-M futures gateway: signed REST for orders, user data stream for fills.
 *
 * <h2>Credentials</h2>
 * Read from {@code -DbinanceApiKey} / {@code -DbinanceApiSecret}, defaulting to obvious dummies so an
 * unconfigured run fails at the venue rather than doing something unintended. The secret is used only
 * to sign and is never logged; {@link #toString()} is overridden so it cannot leak through a debug
 * print of the object.
 *
 * <h2>Post-only is GTX</h2>
 * Binance spells post-only {@code timeInForce=GTX} ("good till crossing"): the order is rejected
 * rather than executed if it would take. That rejection is the desired behaviour — a limit that
 * crosses silently pays taker, converting the cheap leg into the expensive one, which is exactly the
 * cost XVF's execution design exists to avoid.
 *
 * <h2>listenKey lifetime</h2>
 * The user data stream key expires 60 minutes after creation and must be extended with a PUT. A
 * lapsed key does not error — the socket simply stops delivering, which would leave a filled maker
 * leg unhedged with nothing reporting it. The keepalive runs every 30 minutes and a failure to
 * extend is escalated rather than retried quietly.
 */
public final class BinanceGateway implements VenueGateway {

    private static final String REST = "https://fapi.binance.com";
    private static final String WS = "wss://fstream.binance.com/ws/";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final byte[] apiSecret;
    private final boolean dryRun;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, SymbolRules> ruleCache = new ConcurrentHashMap<>();

    public BinanceGateway(boolean dryRun) {
        this.apiKey = System.getProperty("binanceApiKey", "DUMMY_BINANCE_KEY");
        this.apiSecret = System.getProperty("binanceApiSecret", "DUMMY_BINANCE_SECRET")
                .getBytes(StandardCharsets.UTF_8);
        this.dryRun = dryRun;
    }

    @Override
    public String name() {
        return "binance";
    }

    @Override
    public OrderHandle placePostOnly(String venueSymbol, Side side, BigDecimal quantity,
                                     BigDecimal limitPrice) {
        String clientId = "xvf" + System.nanoTime();
        Map<String, String> params = new HashMap<>();
        params.put("symbol", venueSymbol);
        params.put("side", side.name());
        params.put("type", "LIMIT");
        params.put("timeInForce", "GTX");          // post-only: reject rather than take
        params.put("quantity", quantity.toPlainString());
        params.put("price", limitPrice.toPlainString());
        params.put("newClientOrderId", clientId);
        return send("POST", "/fapi/v1/order", params, venueSymbol, clientId);
    }

    @Override
    public OrderHandle placeMarket(String venueSymbol, Side side, BigDecimal quantity) {
        String clientId = "xvf" + System.nanoTime();
        Map<String, String> params = new HashMap<>();
        params.put("symbol", venueSymbol);
        params.put("side", side.name());
        params.put("type", "MARKET");
        params.put("quantity", quantity.toPlainString());
        params.put("newClientOrderId", clientId);
        return send("POST", "/fapi/v1/order", params, venueSymbol, clientId);
    }

    @Override
    public void cancel(OrderHandle handle) {
        Map<String, String> params = new HashMap<>();
        params.put("symbol", handle.venueSymbol());
        params.put("origClientOrderId", handle.clientOrderId());
        send("DELETE", "/fapi/v1/order", params, handle.venueSymbol(), handle.clientOrderId());
    }

    private OrderHandle send(String method, String path, Map<String, String> params,
                             String venueSymbol, String clientId) {
        if (dryRun) {
            System.out.printf("  [dry-run] binance %s %s %s%n", method, path, params);
            return new OrderHandle("binance", venueSymbol, "DRYRUN", clientId);
        }
        params.put("timestamp", Long.toString(System.currentTimeMillis()));
        params.put("recvWindow", "5000");
        String query = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + urlEncode(e.getValue()))
                .reduce((a, b) -> a + "&" + b).orElse("");
        String signed = query + "&signature=" + sign(query);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(REST + path + "?" + signed))
                .header("X-MBX-APIKEY", apiKey).timeout(Duration.ofSeconds(15));
        HttpRequest request = switch (method) {
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
            case "DELETE" -> builder.DELETE().build();
            default -> builder.GET().build();
        };
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("binance " + path + " HTTP "
                        + response.statusCode() + ": " + response.body());
            }
            JsonNode body = MAPPER.readTree(response.body());
            return new OrderHandle("binance", venueSymbol,
                    body.path("orderId").asText(""), clientId);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("binance " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
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
            OrderState state = switch (o.path("X").asText()) {
                case "NEW" -> OrderState.RESTING;
                case "PARTIALLY_FILLED" -> OrderState.PARTIALLY_FILLED;
                case "FILLED" -> OrderState.FILLED;
                case "CANCELED", "EXPIRED" -> OrderState.CANCELLED;
                case "REJECTED" -> OrderState.REJECTED;
                default -> OrderState.RESTING;
            };
            listener.accept(new OrderUpdate("binance", o.path("s").asText(), o.path("c").asText(),
                    state, new BigDecimal(o.path("z").asText("0")),
                    new BigDecimal(o.path("ap").asText("0")), node.path("E").asLong()));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            System.out.printf("!! binance stream frame unparsed: %s%n", e.getMessage());
        }
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
