package com.smalistean.propstrategy.xvf.venue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Bybit V5 linear perpetuals: signed REST for orders, private websocket for fills.
 *
 * <h2>retCode, not the HTTP status, decides the outcome</h2>
 * This is the difference that matters against {@link BinanceGateway}. Bybit answers HTTP 200 to a
 * rejected order and puts the verdict in {@code retCode} — 0 is success, anything else is a refusal
 * with the reason in {@code retMsg}. Classifying on the status line alone would treat every rejection
 * as an accepted order, so the engine would wait for a fill on something that was never placed and
 * then hedge nothing. Only a transport failure or a 5xx is {@code UNKNOWN} here.
 *
 * <h2>Signing</h2>
 * {@code HMAC-SHA256(timestamp + apiKey + recvWindow + payload)}, where payload is the raw JSON body
 * for POST and the query string for GET. The order of concatenation is fixed and unforgiving; a
 * mismatch returns 10004 rather than anything descriptive.
 *
 * <h2>Credentials</h2>
 * {@code BYBIT_API_KEY} / {@code BYBIT_SECRET_KEY} from the environment, never system properties —
 * {@code -D} arguments are visible in {@code ps aux} to every user on the machine. {@link #toString()}
 * is overridden so credentials cannot leak through a debug print.
 *
 * <h2>Post-only and capped taker</h2>
 * {@code timeInForce=PostOnly} rejects rather than crossing; {@code IOC} at an explicit worst price
 * crosses now and cancels the remainder. Both carry {@code orderLinkId}, the caller's own idempotency
 * key, which is what makes an ambiguous submission resolvable.
 *
 * <p>{@code positionIdx=0} throughout: one-way mode. Hedge mode has different reduce-only semantics
 * and would need its own reviewed path.
 */
public final class BybitGateway implements VenueGateway {

    private static final String REST = "https://api.bybit.com";
    private static final String WS = "wss://stream.bybit.com/v5/private";
    private static final String CATEGORY = "linear";
    private static final String RECV_WINDOW = "5000";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiKey;
    private final byte[] apiSecret;
    private final boolean dryRun;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, SymbolRules> ruleCache = new ConcurrentHashMap<>();

    public BybitGateway(boolean dryRun) {
        this.apiKey = System.getenv().getOrDefault("BYBIT_API_KEY", "DUMMY_BYBIT_KEY");
        this.apiSecret = System.getenv().getOrDefault("BYBIT_SECRET_KEY", "DUMMY_BYBIT_SECRET")
                .getBytes(StandardCharsets.UTF_8);
        this.dryRun = dryRun;
    }

    @Override
    public String name() {
        return "bybit";
    }

    @Override
    public SubmitResult placePostOnly(String venueSymbol, Side side, BigDecimal quantity,
                                      BigDecimal limitPrice, String clientOrderId,
                                      boolean reduceOnly) {
        return submit(venueSymbol, side, quantity,
                VenueGateway.roundToTick(limitPrice, rules(venueSymbol).tickSize(), side, false),
                "PostOnly", clientOrderId, reduceOnly);
    }

    @Override
    public SubmitResult placeCappedIoc(String venueSymbol, Side side, BigDecimal quantity,
                                       BigDecimal worstPrice, String clientOrderId,
                                       boolean reduceOnly) {
        return submit(venueSymbol, side, quantity,
                VenueGateway.roundToTick(worstPrice, rules(venueSymbol).tickSize(), side, true),
                "IOC", clientOrderId, reduceOnly);
    }

    @Override
    public void setLeverage(String venueSymbol, int leverage) {
        if (dryRun) {
            System.out.printf("  [dry-run] bybit leverage %s -> %dx%n", venueSymbol, leverage);
            return;
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("category", CATEGORY);
        body.put("symbol", venueSymbol);
        // One-way mode requires both, and they must agree.
        body.put("buyLeverage", Integer.toString(leverage));
        body.put("sellLeverage", Integer.toString(leverage));
        String raw = body.toString();
        String timestamp = Long.toString(System.currentTimeMillis());
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                            URI.create(REST + "/v5/position/set-leverage"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("X-BAPI-API-KEY", apiKey)
                    .header("X-BAPI-TIMESTAMP", timestamp)
                    .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                    .header("X-BAPI-SIGN", sign(timestamp + apiKey + RECV_WINDOW + raw))
                    .POST(HttpRequest.BodyPublishers.ofString(raw)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("bybit leverage " + venueSymbol + " -> " + leverage
                        + "x rejected: HTTP " + response.statusCode() + " " + response.body());
            }
            JsonNode json = MAPPER.readTree(response.body());
            int code = json.path("retCode").asInt(-1);
            // 110043 is Bybit's "leverage not modified" - already at this value, which is the
            // outcome this call wanted, not a failure.
            if (code != 0 && code != 110043) {
                throw new IllegalStateException("bybit leverage " + venueSymbol + " -> " + leverage
                        + "x retCode " + code + ": " + json.path("retMsg").asText());
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("bybit leverage " + venueSymbol + " -> " + leverage
                    + "x failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted setting bybit leverage", e);
        }
    }

    @Override
    public java.util.List<PositionSnapshot> positions() {
        if (dryRun) {
            return java.util.List.of();
        }
        JsonNode response = signedGet("/v5/position/list",
                "category=" + CATEGORY + "&settleCoin=USDT");
        int code = response.path("retCode").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("bybit position/list retCode " + code + ": "
                    + response.path("retMsg").asText());
        }
        java.util.List<PositionSnapshot> out = new java.util.ArrayList<>();
        for (JsonNode p : response.path("result").path("list")) {
            BigDecimal size = new BigDecimal(p.path("size").asText("0"));
            if (size.signum() == 0) {
                continue;
            }
            // Bybit reports size unsigned with the direction in `side`; the interface wants it signed.
            BigDecimal signed = "Sell".equals(p.path("side").asText()) ? size.negate() : size;
            out.add(new PositionSnapshot("bybit", p.path("symbol").asText(), signed,
                    new BigDecimal(p.path("avgPrice").asText("0").isEmpty()
                            ? "0" : p.path("avgPrice").asText("0")),
                    VenueGateway.optionalPrice(p.path("liqPrice").asText(""))));
        }
        return out;
    }

    private SubmitResult submit(String venueSymbol, Side side, BigDecimal quantity,
                                BigDecimal price, String timeInForce, String clientOrderId,
                                boolean reduceOnly) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("category", CATEGORY);
        body.put("symbol", venueSymbol);
        body.put("side", side == Side.BUY ? "Buy" : "Sell");
        body.put("orderType", "Limit");
        body.put("qty", quantity.toPlainString());
        body.put("price", price.toPlainString());
        body.put("timeInForce", timeInForce);
        body.put("orderLinkId", clientOrderId);
        body.put("positionIdx", 0);
        if (reduceOnly) {
            body.put("reduceOnly", true);
        }
        return post("/v5/order/create", body, venueSymbol, clientOrderId);
    }

    @Override
    public SubmitResult placeReduceOnlyTrigger(String venueSymbol, Side side, BigDecimal quantity,
                                               BigDecimal triggerPrice, TriggerWhen when,
                                               String clientOrderId) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("category", CATEGORY);
        body.put("symbol", venueSymbol);
        body.put("side", side == Side.BUY ? "Buy" : "Sell");
        body.put("orderType", "Market");
        body.put("qty", quantity.toPlainString());
        body.put("triggerPrice", VenueGateway.roundToTick(
                triggerPrice, rules(venueSymbol).tickSize(), side, true).toPlainString());
        // Bybit takes the raw direction and works the rest out itself: 1 rises to, 2 falls to.
        body.put("triggerDirection", when == TriggerWhen.PRICE_RISES_TO ? 1 : 2);
        // Mark price, matching Binance: one venue's wick must not unwind a hedged pair.
        body.put("triggerBy", "MarkPrice");
        body.put("reduceOnly", true);
        body.put("orderLinkId", clientOrderId);
        body.put("positionIdx", 0);
        return post("/v5/order/create", body, venueSymbol, clientOrderId);
    }

    @Override
    public void cancel(OrderHandle handle) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("category", CATEGORY);
        body.put("symbol", handle.venueSymbol());
        body.put("orderLinkId", handle.clientOrderId());
        post("/v5/order/cancel", body, handle.venueSymbol(), handle.clientOrderId());
    }

    @Override
    public Optional<OrderSnapshot> orderByClientId(String venueSymbol, String clientOrderId) {
        if (dryRun) {
            return Optional.empty();
        }
        JsonNode response = signedGet("/v5/order/realtime",
                "category=" + CATEGORY + "&symbol=" + venueSymbol + "&orderLinkId=" + clientOrderId);
        int code = response.path("retCode").asInt(-1);
        if (code != 0) {
            // Only retCode 0 may be read as an answer. A non-zero code is a failure to ASK - a bad
            // signature, an expired key, a rate limit - and returning empty here would tell the
            // caller the venue never saw the order. The caller treats that as a rejection, so a
            // broken key would silently mark every ambiguous submission rejected while real orders
            // rested untracked. Throwing keeps it UNKNOWN, which escalates instead.
            throw new IllegalStateException("bybit order lookup retCode " + code + ": "
                    + response.path("retMsg").asText());
        }
        JsonNode list = response.path("result").path("list");
        if (!list.isArray() || list.isEmpty()) {
            // retCode 0 with an empty list IS the answer: no such orderLinkId.
            return Optional.empty();
        }
        JsonNode o = list.get(0);
        return Optional.of(new OrderSnapshot(
                new OrderHandle("bybit", venueSymbol, o.path("orderId").asText(""), clientOrderId),
                parseState(o.path("orderStatus").asText()),
                new BigDecimal(o.path("cumExecQty").asText("0")),
                new BigDecimal(o.path("avgPrice").asText("0").isEmpty()
                        ? "0" : o.path("avgPrice").asText("0"))));
    }

    @Override
    public TopOfBook topOfBook(String venueSymbol) {
        JsonNode body = publicGet("/v5/market/tickers?category=" + CATEGORY + "&symbol=" + venueSymbol);
        JsonNode list = body.path("result").path("list");
        if (!list.isArray() || list.isEmpty()) {
            throw new IllegalStateException("bybit has no ticker for " + venueSymbol);
        }
        JsonNode t = list.get(0);
        return new TopOfBook(new BigDecimal(t.path("bid1Price").asText()),
                new BigDecimal(t.path("ask1Price").asText()),
                body.path("time").asLong(System.currentTimeMillis()));
    }

    /**
     * Refuses a symbol that is not a genuine crypto perpetual.
     *
     * <p>Bybit tags every non-crypto listing with a non-empty {@code symbolType} - confirmed live
     * 2026-08-19 on ONUSDT, {@code "symbolType":"stock"}, which turned out to be ON Semiconductor
     * Corp (NASDAQ: ON) rather than the Orochi Network crypto token Binance lists under the
     * identical three-letter ticker. A funding-spread pair built from that match was two
     * unrelated, uncorrelated directional bets wearing a hedge's clothes - the sizing math cannot
     * catch it, because both legs price to their own venue's ~notional target regardless of what
     * asset either one actually is. Every genuine crypto perpetual carries {@code ""}.
     */
    static void requireCryptoPerp(String venueSymbol, JsonNode instrument) {
        String symbolType = instrument.path("symbolType").asText("");
        if (!symbolType.isEmpty()) {
            throw new IllegalStateException("bybit " + venueSymbol + " is a " + symbolType
                    + " listing, not a crypto perpetual - refusing rather than risking a ticker "
                    + "collision with whatever the same base means on another venue");
        }
    }

    @Override
    public SymbolRules rules(String venueSymbol) {
        return ruleCache.computeIfAbsent(venueSymbol, symbol -> {
            JsonNode body = publicGet("/v5/market/instruments-info?category=" + CATEGORY
                    + "&symbol=" + symbol);
            JsonNode list = body.path("result").path("list");
            if (!list.isArray() || list.isEmpty()) {
                throw new IllegalStateException("no bybit rules for " + symbol);
            }
            JsonNode i = list.get(0);
            requireCryptoPerp(symbol, i);
            JsonNode lot = i.path("lotSizeFilter");
            String minNotional = lot.path("minNotionalValue").asText("5");
            return new SymbolRules(
                    new BigDecimal(lot.path("qtyStep").asText("1")),
                    new BigDecimal(minNotional.isEmpty() ? "5" : minNotional),
                    new BigDecimal(i.path("priceFilter").path("tickSize").asText("0.01")));
        });
    }

    // ---------- transport ----------

    /**
     * Sends a signed POST and classifies the outcome.
     *
     * <p>HTTP 200 with a non-zero {@code retCode} is a REJECTION, not a success. Anything that fails
     * in transport is UNKNOWN, because the order may already be resting.
     */
    private SubmitResult post(String path, ObjectNode body, String venueSymbol, String clientId) {
        OrderHandle handle = new OrderHandle("bybit", venueSymbol, "", clientId);
        if (dryRun) {
            System.out.printf("  [dry-run] bybit POST %s %s%n", path, body);
            return new SubmitResult(SubmitOutcome.ACCEPTED,
                    new OrderHandle("bybit", venueSymbol, "DRYRUN", clientId), "dry run");
        }
        String raw = body.toString();
        String timestamp = Long.toString(System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(URI.create(REST + path))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("X-BAPI-API-KEY", apiKey)
                .header("X-BAPI-TIMESTAMP", timestamp)
                .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .header("X-BAPI-SIGN", sign(timestamp + apiKey + RECV_WINDOW + raw))
                .POST(HttpRequest.BodyPublishers.ofString(raw))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new SubmitResult(SubmitOutcome.UNKNOWN, handle,
                        "HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = MAPPER.readTree(response.body());
            int code = json.path("retCode").asInt(-1);
            if (code == 0) {
                return new SubmitResult(SubmitOutcome.ACCEPTED,
                        new OrderHandle("bybit", venueSymbol,
                                json.path("result").path("orderId").asText(""), clientId), "ok");
            }
            return new SubmitResult(SubmitOutcome.REJECTED, handle,
                    "retCode " + code + ": " + json.path("retMsg").asText());
        } catch (java.io.IOException e) {
            return new SubmitResult(SubmitOutcome.UNKNOWN, handle, "io: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SubmitResult(SubmitOutcome.UNKNOWN, handle, "interrupted");
        }
    }

    private JsonNode signedGet(String path, String query) {
        String timestamp = Long.toString(System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(URI.create(REST + path + "?" + query))
                .timeout(Duration.ofSeconds(15))
                .header("X-BAPI-API-KEY", apiKey)
                .header("X-BAPI-TIMESTAMP", timestamp)
                .header("X-BAPI-RECV-WINDOW", RECV_WINDOW)
                .header("X-BAPI-SIGN", sign(timestamp + apiKey + RECV_WINDOW + query))
                .GET().build();
        return send(request, path);
    }

    private JsonNode publicGet(String pathWithQuery) {
        return send(HttpRequest.newBuilder(URI.create(REST + pathWithQuery))
                .timeout(Duration.ofSeconds(15)).GET().build(), pathWithQuery);
    }

    private JsonNode send(HttpRequest request, String what) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("bybit " + what + " HTTP " + response.statusCode());
            }
            return MAPPER.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("bybit " + what + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    // ---------- private stream ----------

    @Override
    public AutoCloseable streamOrderUpdates(Consumer<OrderUpdate> listener) {
        if (dryRun) {
            System.out.println("  [dry-run] bybit private stream not opened");
            return () -> { };
        }
        WebSocket socket = http.newWebSocketBuilder()
                .buildAsync(URI.create(WS), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        // Auth first, then subscribe. A subscribe before the auth reply is silently
                        // ignored, which looks exactly like a working stream that never delivers.
                        long expires = System.currentTimeMillis() + 10_000;
                        String signature = sign("GET/realtime" + expires);
                        ws.sendText("{\"op\":\"auth\",\"args\":[\"" + apiKey + "\","
                                + expires + ",\"" + signature + "\"]}", true);
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            handle(ws, buffer.toString(), listener);
                            buffer.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        System.out.printf("!!!! bybit private stream error: %s — treat as fills "
                                + "possibly missed, reconcile by polling%n", error.getMessage());
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                        System.out.printf("!!!! bybit private stream closed (%d %s)%n", code, reason);
                        return null;
                    }
                }).join();

        // Bybit drops a private connection that is silent for 20 minutes.
        heartbeat.scheduleAtFixedRate(() -> socket.sendText("{\"op\":\"ping\"}", true),
                20, 20, TimeUnit.SECONDS);

        return () -> {
            socket.abort();
            heartbeat.shutdownNow();
        };
    }

    private void handle(WebSocket ws, String frame, Consumer<OrderUpdate> listener) {
        try {
            JsonNode node = MAPPER.readTree(frame);
            if ("auth".equals(node.path("op").asText())) {
                if (!node.path("success").asBoolean(false)) {
                    System.out.printf("!!!! bybit stream auth FAILED: %s — no fills will arrive%n",
                            node.path("ret_msg").asText());
                    return;
                }
                ws.sendText("{\"op\":\"subscribe\",\"args\":[\"order\"]}", true);
                return;
            }
            if (!"order".equals(node.path("topic").asText())) {
                return;
            }
            for (JsonNode o : node.path("data")) {
                if (!CATEGORY.equals(o.path("category").asText(CATEGORY))) {
                    continue;
                }
                // cumExecQty is CUMULATIVE for the order, like Binance's z. The engine differences it
                // against a watermark; passing it through unchanged is intentional.
                String avg = o.path("avgPrice").asText("0");
                listener.accept(new OrderUpdate("bybit", o.path("symbol").asText(),
                        o.path("orderLinkId").asText(),
                        parseState(o.path("orderStatus").asText()),
                        new BigDecimal(o.path("cumExecQty").asText("0")),
                        new BigDecimal(avg.isEmpty() ? "0" : avg),
                        node.path("ts").asLong(System.currentTimeMillis())));
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            System.out.printf("!! bybit stream frame unparsed: %s%n", e.getMessage());
        }
    }

    private static OrderState parseState(String venueStatus) {
        return switch (venueStatus) {
            case "New", "Untriggered", "Triggered" -> OrderState.RESTING;
            case "PartiallyFilled" -> OrderState.PARTIALLY_FILLED;
            case "Filled" -> OrderState.FILLED;
            // PartiallyFilledCanceled is terminal with a real fill behind it - an IOC that took some
            // and cancelled the rest, which is the normal outcome for the capped taker leg.
            case "Cancelled", "Deactivated", "PartiallyFilledCanceled" -> OrderState.CANCELLED;
            case "Rejected" -> OrderState.REJECTED;
            default -> OrderState.RESTING;
        };
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(apiSecret, "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("signing failed", e);
        }
    }

    /** Never render credentials, even accidentally through a debug print of this object. */
    @Override
    public String toString() {
        return "BybitGateway[dryRun=" + dryRun + "]";
    }
}
