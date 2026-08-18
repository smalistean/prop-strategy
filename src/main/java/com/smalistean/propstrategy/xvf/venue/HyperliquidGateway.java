package com.smalistean.propstrategy.xvf.venue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Hyperliquid perpetuals: EIP-712-signed exchange actions, websocket for fills.
 *
 * <p>Verified byte-for-byte against a from-scratch Python reference built from the official SDK
 * source during implementation: the msgpack encoding of a real order action, the resulting
 * {@code action_hash}, {@link Keys#getAddress}, {@link org.web3j.crypto.Hash#sha3} (Keccak-256, NOT
 * SHA3-256 - the JDK has no Keccak provider, which is why this exists) and the EIP-712 domain
 * separator all matched known values exactly. A signing bug here is a safe failure - Hyperliquid
 * independently reconstructs the hash from the JSON it receives and rejects on mismatch, it can never
 * cause a wrong trade - but a rejected order is still a missed position, so this was checked before
 * being trusted.
 *
 * <h2>Two wallets, not one</h2>
 * {@code HL_ACCOUNT_ADDRESS} is the master account: the "user" every read (positions, fills, order
 * status) is scoped to, and the address that must hold collateral. {@code HL_API_WALLET_ADDRESS} /
 * {@code HL_API_PRIVATE_KEY} are a separate <b>agent wallet</b>, approved on the account (Hyperliquid
 * UI -> API) to sign orders without withdrawal rights. Signing with the master key or reading with the
 * agent address both fail in ways that look like a misconfigured account rather than a code bug, so
 * the derived agent address is checked against {@code HL_API_WALLET_ADDRESS} at construction.
 *
 * <h2>Fill accounting reads userFills, not orderUpdates</h2>
 * Hyperliquid's {@code orderUpdates} stream carries a {@code sz} field whose cumulative-vs-remaining
 * semantics are not documented precisely enough to trust for hedge sizing - getting this wrong is
 * exactly the over-hedge bug class fixed in {@code PairedEntryEngine} for Binance's {@code z} field.
 * {@code userFills} has no such ambiguity: each message is one discrete trade execution with its own
 * size, unique {@code tid}, and the {@code oid} it belongs to. Cumulative filled quantity is therefore
 * accumulated locally by summing individual fills per order, deduplicated by {@code tid} - a
 * construction whose correctness does not depend on interpreting an exchange-defined field.
 * {@code orderUpdates} is used only for terminal-state logging (rejected/canceled).
 *
 * <h2>Credentials</h2>
 * {@code HL_ACCOUNT_ADDRESS} / {@code HL_API_WALLET_ADDRESS} / {@code HL_API_PRIVATE_KEY} from the
 * environment, never system properties - {@code -D} arguments are visible in {@code ps aux}.
 * {@link #toString()} never renders the key.
 */
public final class HyperliquidGateway implements VenueGateway {

    private static final String INFO = "https://api.hyperliquid.xyz/info";
    private static final String EXCHANGE = "https://api.hyperliquid.xyz/exchange";
    private static final String WS = "wss://api.hyperliquid.xyz/ws";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Hyperliquid requires roughly $10 minimum notional per perpetual order. */
    private static final BigDecimal MIN_NOTIONAL_USD = new BigDecimal("10");

    private final String accountAddress;
    private final String apiWalletAddress;
    private final ECKeyPair signingKey;
    private final boolean dryRun;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();

    /** name -> (assetIndex, szDecimals). Populated once from {@code meta}. */
    private final Map<String, int[]> assetCache = new ConcurrentHashMap<>();
    private final Map<String, SymbolRules> ruleCache = new ConcurrentHashMap<>();

    /** Bridges Hyperliquid's numeric order id to the caller's client order id, and back for cancel. */
    private final Map<Long, String> oidToClientId = new ConcurrentHashMap<>();
    private final Map<String, Long> clientIdToOid = new ConcurrentHashMap<>();
    /** Cumulative filled quantity per oid, built by summing userFills - see class javadoc. */
    private final Map<Long, BigDecimal> cumulativeFilled = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> seenTradeIds = new ConcurrentHashMap<>();
    private final Map<Long, String> lastKnownStatus = new ConcurrentHashMap<>();

    /**
     * Strictly increasing, not just "current time". Two signed actions in the same millisecond -
     * plausible when a rebalance opens several pairs in a tight loop - would otherwise reuse a nonce,
     * which Hyperliquid rejects.
     */
    private final AtomicLong lastNonce = new AtomicLong();

    public HyperliquidGateway(boolean dryRun) {
        this.dryRun = dryRun;
        this.accountAddress = System.getenv().getOrDefault("HL_ACCOUNT_ADDRESS", "");
        this.apiWalletAddress = System.getenv().getOrDefault("HL_API_WALLET_ADDRESS", "");
        String privateKeyHex = System.getenv().getOrDefault("HL_API_PRIVATE_KEY", "");
        if (dryRun) {
            this.signingKey = ECKeyPair.create(BigInteger.ONE);   // never used; placePostOnly short-circuits
            return;
        }
        if (accountAddress.isBlank() || apiWalletAddress.isBlank() || privateKeyHex.isBlank()) {
            throw new IllegalStateException("HL_ACCOUNT_ADDRESS, HL_API_WALLET_ADDRESS and "
                    + "HL_API_PRIVATE_KEY must all be set to trade live on hyperliquid");
        }
        this.signingKey = ECKeyPair.create(new BigInteger(
                privateKeyHex.startsWith("0x") ? privateKeyHex.substring(2) : privateKeyHex, 16));
        // Fails at construction, not on the first signed call, and says exactly what is wrong: the
        // private key does not belong to the agent wallet it claims to, which every subsequent
        // signature would otherwise fail against with a much less clear error from the exchange.
        String derived = "0x" + Keys.getAddress(signingKey);
        if (!derived.equalsIgnoreCase(apiWalletAddress)) {
            throw new IllegalStateException("HL_API_PRIVATE_KEY derives to " + derived
                    + " but HL_API_WALLET_ADDRESS is " + apiWalletAddress + " - wrong key for this agent");
        }
    }

    @Override
    public String name() {
        return "hyperliquid";
    }

    @Override
    public SubmitResult placePostOnly(String venueSymbol, Side side, BigDecimal quantity,
                                      BigDecimal limitPrice, String clientOrderId,
                                      boolean reduceOnly) {
        return submit(venueSymbol, side, quantity,
                VenueGateway.roundToTick(limitPrice, rules(venueSymbol).tickSize(), side, false),
                "Alo", reduceOnly, clientOrderId);
    }

    @Override
    public SubmitResult placeCappedIoc(String venueSymbol, Side side, BigDecimal quantity,
                                       BigDecimal worstPrice, String clientOrderId,
                                       boolean reduceOnly) {
        return submit(venueSymbol, side, quantity,
                VenueGateway.roundToTick(worstPrice, rules(venueSymbol).tickSize(), side, true),
                "Ioc", reduceOnly, clientOrderId);
    }

    @Override
    public java.util.List<PositionSnapshot> positions() {
        if (dryRun) {
            return java.util.List.of();
        }
        JsonNode state = infoPost(Map.of("type", "clearinghouseState", "user", accountAddress));
        java.util.List<PositionSnapshot> out = new java.util.ArrayList<>();
        for (JsonNode ap : state.path("assetPositions")) {
            JsonNode p = ap.path("position");
            BigDecimal szi = new BigDecimal(p.path("szi").asText("0"));
            if (szi.signum() != 0) {
                // szi is already signed on Hyperliquid: negative is short.
                out.add(new PositionSnapshot("hyperliquid", p.path("coin").asText(), szi,
                        new BigDecimal(p.path("entryPx").asText("0"))));
            }
        }
        return out;
    }

    private SubmitResult submit(String venueSymbol, Side side, BigDecimal quantity, BigDecimal price,
                                String tif, boolean reduceOnly, String clientOrderId) {
        OrderHandle handle = new OrderHandle("hyperliquid", venueSymbol, "", clientOrderId);
        if (dryRun) {
            System.out.printf("  [dry-run] hyperliquid order %s %s %s @ %s (%s)%n",
                    side, quantity, venueSymbol, price, tif);
            return new SubmitResult(SubmitOutcome.ACCEPTED,
                    new OrderHandle("hyperliquid", venueSymbol, "DRYRUN", clientOrderId), "dry run");
        }
        int assetIndex = assetIndex(venueSymbol);
        String cloid = cloidFor(clientOrderId);

        Map<String, Object> tifMap = new LinkedHashMap<>();
        tifMap.put("tif", tif);
        Map<String, Object> orderType = new LinkedHashMap<>();
        orderType.put("limit", tifMap);

        Map<String, Object> orderWire = new LinkedHashMap<>();
        orderWire.put("a", assetIndex);
        orderWire.put("b", side == Side.BUY);
        orderWire.put("p", wireDecimal(price));
        orderWire.put("s", wireDecimal(quantity));
        orderWire.put("r", reduceOnly);
        orderWire.put("t", orderType);
        orderWire.put("c", cloid);

        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "order");
        action.put("orders", List.of(orderWire));
        action.put("grouping", "na");

        JsonNode response;
        try {
            response = postSigned(action);
        } catch (RuntimeException e) {
            return new SubmitResult(SubmitOutcome.UNKNOWN, handle, e.getMessage());
        }
        return classify(response, venueSymbol, clientOrderId, handle);
    }

    /**
     * Top-level {@code status:"err"} is treated as REJECTED, not UNKNOWN. Signature and nonce
     * validation on Hyperliquid is synchronous - if it failed, the action never reached matching, the
     * same guarantee an HTTP 4xx gives on Binance. A transport failure or an unrecognised shape is
     * UNKNOWN, because the order may have reached the exchange regardless of what happened after.
     */
    private SubmitResult classify(JsonNode response, String venueSymbol, String clientOrderId,
                                  OrderHandle handle) {
        if (!"ok".equals(response.path("status").asText())) {
            return new SubmitResult(SubmitOutcome.REJECTED, handle,
                    "status=err: " + response.path("response").asText());
        }
        JsonNode statuses = response.path("response").path("data").path("statuses");
        if (!statuses.isArray() || statuses.isEmpty()) {
            return new SubmitResult(SubmitOutcome.UNKNOWN, handle, "no statuses in response");
        }
        JsonNode s = statuses.get(0);
        if (s.has("error")) {
            return new SubmitResult(SubmitOutcome.REJECTED, handle, s.path("error").asText());
        }
        JsonNode resting = s.path("resting");
        JsonNode filled = s.path("filled");
        long oid = resting.has("oid") ? resting.path("oid").asLong()
                : filled.has("oid") ? filled.path("oid").asLong() : -1;
        if (oid < 0) {
            return new SubmitResult(SubmitOutcome.UNKNOWN, handle, "unrecognised status: " + s);
        }
        // Registered before returning: any fill can only arrive after the exchange has already
        // accepted this order, so there is no race with the websocket handler here.
        oidToClientId.put(oid, clientOrderId);
        clientIdToOid.put(clientOrderId, oid);
        return new SubmitResult(SubmitOutcome.ACCEPTED,
                new OrderHandle("hyperliquid", venueSymbol, Long.toString(oid), clientOrderId), "ok");
    }

    @Override
    public void cancel(OrderHandle handle) {
        if (dryRun || "DRYRUN".equals(handle.venueOrderId())) {
            return;
        }
        Long oid = clientIdToOid.get(handle.clientOrderId());
        if (oid == null) {
            try {
                oid = Long.parseLong(handle.venueOrderId());
            } catch (NumberFormatException e) {
                System.out.printf("!! hyperliquid cancel: no known oid for %s%n", handle.clientOrderId());
                return;
            }
        }
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("a", assetIndex(handle.venueSymbol()));
        c.put("o", oid);
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "cancel");
        action.put("cancels", List.of(c));
        try {
            postSigned(action);
        } catch (RuntimeException e) {
            System.out.printf("!! hyperliquid cancel failed for %s: %s%n", handle.clientOrderId(),
                    e.getMessage());
        }
    }

    @Override
    public Optional<OrderSnapshot> orderByClientId(String venueSymbol, String clientOrderId) {
        if (dryRun) {
            return Optional.empty();
        }
        Map<String, Object> body = Map.of("type", "orderStatus", "user", accountAddress,
                "oid", cloidFor(clientOrderId));
        JsonNode response = infoPost(body);
        if ("unknownOid".equals(response.path("status").asText())) {
            return Optional.empty();
        }
        if (!"order".equals(response.path("status").asText())) {
            // Not "not found" - a malformed request or a transient failure. The caller treats an
            // empty Optional as proof the venue never saw the order, so that must be earned, not
            // assumed on any non-success shape.
            throw new IllegalStateException("hyperliquid orderStatus unexpected shape: " + response);
        }
        JsonNode order = response.path("order").path("order");
        long oid = order.path("oid").asLong();
        String hlStatus = response.path("order").path("status").asText();
        BigDecimal filled = cumulativeFilled.getOrDefault(oid, BigDecimal.ZERO);
        return Optional.of(new OrderSnapshot(
                new OrderHandle("hyperliquid", venueSymbol, Long.toString(oid), clientOrderId),
                parseState(hlStatus), filled, new BigDecimal(order.path("limitPx").asText("0"))));
    }

    @Override
    public TopOfBook topOfBook(String venueSymbol) {
        JsonNode body = infoPost(Map.of("type", "l2Book", "coin", venueSymbol));
        JsonNode levels = body.path("levels");
        if (!levels.isArray() || levels.size() < 2 || levels.get(0).isEmpty() || levels.get(1).isEmpty()) {
            throw new IllegalStateException("hyperliquid has no book for " + venueSymbol);
        }
        // levels[0] = bids (best first), levels[1] = asks (best first).
        return new TopOfBook(new BigDecimal(levels.get(0).get(0).path("px").asText()),
                new BigDecimal(levels.get(1).get(0).path("px").asText()),
                body.path("time").asLong(System.currentTimeMillis()));
    }

    @Override
    public SymbolRules rules(String venueSymbol) {
        return ruleCache.computeIfAbsent(venueSymbol, symbol -> {
            int szDecimals = assetIndexAndDecimals(symbol)[1];
            BigDecimal step = BigDecimal.ONE.movePointLeft(szDecimals);
            // Not a real venue field - Hyperliquid caps price at 5 significant figures AND at most
            // (6 - szDecimals) decimal places, rather than a fixed tick. Every order this gateway
            // sends prices at the venue's OWN touch (see topOfBook), which is already valid by
            // construction, so this is reported only so the interface has a value and must not be
            // used to construct a new price from scratch.
            BigDecimal tick = BigDecimal.ONE.movePointLeft(Math.max(0, 6 - szDecimals));
            return new SymbolRules(step, MIN_NOTIONAL_USD, tick);
        });
    }

    private int assetIndex(String venueSymbol) {
        return assetIndexAndDecimals(venueSymbol)[0];
    }

    private int[] assetIndexAndDecimals(String venueSymbol) {
        if (assetCache.isEmpty()) {
            JsonNode meta = infoPost(Map.of("type", "meta"));
            int i = 0;
            for (JsonNode a : meta.path("universe")) {
                assetCache.put(a.path("name").asText(), new int[] {i, a.path("szDecimals").asInt()});
                i++;
            }
        }
        int[] found = assetCache.get(venueSymbol);
        if (found == null) {
            throw new IllegalStateException("no hyperliquid asset named " + venueSymbol);
        }
        return found;
    }

    // ---------- signing ----------

    /** Signs and posts one exchange action, returning the parsed JSON response. */
    private JsonNode postSigned(Map<String, Object> action) {
        long nonce = nextNonce();
        byte[] hash = actionHash(action, nonce);
        Sign.SignatureData sig = signAgent(hash);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", action);
        payload.put("nonce", nonce);
        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("r", "0x" + Numeric.toHexStringNoPrefix(sig.getR()));
        signature.put("s", "0x" + Numeric.toHexStringNoPrefix(sig.getS()));
        signature.put("v", sig.getV()[0] & 0xFF);
        payload.put("signature", signature);

        try {
            String body = MAPPER.writeValueAsString(payload);
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(EXCHANGE)).timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("hyperliquid exchange HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("hyperliquid exchange call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    /** Ensures every nonce this process sends is strictly greater than the last. */
    private long nextNonce() {
        return lastNonce.updateAndGet(prev -> Math.max(System.currentTimeMillis(), prev + 1));
    }

    /**
     * {@code action_hash = keccak256(msgpack(action) || nonce:8BE || vaultFlag)}, no vault used here.
     * Verified byte-for-byte against a Python reference during implementation - see class javadoc.
     */
    private static byte[] actionHash(Map<String, Object> action, long nonce) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MsgPack.write(action, out);
        for (int shift = 56; shift >= 0; shift -= 8) {
            out.write((int) (nonce >> shift));
        }
        out.write(0x00);   // no vault address
        return org.web3j.crypto.Hash.sha3(out.toByteArray());
    }

    /**
     * Wraps the action hash in Hyperliquid's "phantom agent" and signs it as EIP-712 typed data.
     * Domain and types are fixed by the protocol, not configuration - {@code chainId 1337} here is
     * the signing scheme's own dummy chain and is unrelated to which real chain the account lives on.
     */
    private Sign.SignatureData signAgent(byte[] actionHash) {
        String connectionId = Numeric.toHexStringNoPrefix(actionHash);
        String json = "{\"types\":{"
                + "\"EIP712Domain\":[{\"name\":\"name\",\"type\":\"string\"},"
                + "{\"name\":\"version\",\"type\":\"string\"},{\"name\":\"chainId\",\"type\":\"uint256\"},"
                + "{\"name\":\"verifyingContract\",\"type\":\"address\"}],"
                + "\"Agent\":[{\"name\":\"source\",\"type\":\"string\"},"
                + "{\"name\":\"connectionId\",\"type\":\"bytes32\"}]},"
                + "\"primaryType\":\"Agent\","
                + "\"domain\":{\"name\":\"Exchange\",\"version\":\"1\",\"chainId\":1337,"
                + "\"verifyingContract\":\"0x0000000000000000000000000000000000000000\"},"
                // "a" = mainnet. This account and every live call in this class talk to
                // api.hyperliquid.xyz, which is mainnet; there is no testnet toggle here.
                + "\"message\":{\"source\":\"a\",\"connectionId\":\"0x" + connectionId + "\"}}";
        try {
            byte[] finalHash = new StructuredDataEncoder(json).hashStructuredData();
            return Sign.signMessage(finalHash, signingKey, false);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("EIP-712 encoding failed", e);
        }
    }

    /**
     * A deterministic 128-bit hex client-order id derived from the caller's own id string.
     *
     * <p>Hyperliquid's {@code cloid} must be a 16-byte hex string; this project's client order IDs
     * are free-form ("xvf-COTI-2666983303735833"), matching the format every other gateway accepts.
     * Hashing rather than passing through preserves the one property that matters - the same
     * clientOrderId always derives the same cloid, so {@link #orderByClientId} can look up an order by
     * recomputing this from an id the caller already owns.
     */
    private static String cloidFor(String clientOrderId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(clientOrderId.getBytes(StandardCharsets.UTF_8));
            return "0x" + Numeric.toHexStringNoPrefix(java.util.Arrays.copyOf(digest, 16));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Canonical decimal string for a price or size: no trailing zeros, never scientific notation.
     * Mirrors the Python SDK's {@code float_to_wire} closely enough for the exchange's parser, without
     * reproducing its float round-trip check - the inputs here are already {@link BigDecimal}, so no
     * float precision is ever at risk of being lost in the first place.
     */
    private static String wireDecimal(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    // ---------- REST info ----------

    private JsonNode infoPost(Map<String, Object> body) {
        try {
            String json = MAPPER.writeValueAsString(body);
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(URI.create(INFO)).timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("hyperliquid info HTTP " + response.statusCode()
                        + ": " + response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("hyperliquid info call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    // ---------- private stream ----------

    @Override
    public AutoCloseable streamOrderUpdates(Consumer<OrderUpdate> listener) {
        if (dryRun) {
            System.out.println("  [dry-run] hyperliquid private stream not opened");
            return () -> { };
        }
        WebSocket socket = http.newWebSocketBuilder()
                .buildAsync(URI.create(WS), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        // No signature needed to SUBSCRIBE - only exchange actions are signed. Both
                        // channels are scoped by the master account address, not the agent wallet.
                        subscribe(ws, "orderUpdates");
                        subscribe(ws, "userFills");
                        ws.request(1);
                    }

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
                        System.out.printf("!!!! hyperliquid private stream error: %s — treat as fills "
                                + "possibly missed, reconcile by polling%n", error.getMessage());
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                        System.out.printf("!!!! hyperliquid private stream closed (%d %s)%n", code, reason);
                        return null;
                    }
                }).join();

        // Interval not confirmed against current documentation at implementation time - see
        // XVF_IMPLEMENTATION.md known gaps. Sending too often is harmless; the risk is only in
        // sending too rarely, so this errs short.
        heartbeat.scheduleAtFixedRate(() -> socket.sendText("{\"method\":\"ping\"}", true),
                30, 30, TimeUnit.SECONDS);

        return () -> {
            socket.abort();
            heartbeat.shutdownNow();
        };
    }

    private void subscribe(WebSocket ws, String type) {
        ws.sendText("{\"method\":\"subscribe\",\"subscription\":{\"type\":\"" + type
                + "\",\"user\":\"" + accountAddress + "\"}}", true);
    }

    private void handle(String frame, Consumer<OrderUpdate> listener) {
        try {
            JsonNode node = MAPPER.readTree(frame);
            String channel = node.path("channel").asText();
            if ("orderUpdates".equals(channel)) {
                handleOrderUpdates(node.path("data"));
            } else if ("userFills".equals(channel)) {
                handleUserFills(node.path("data"), listener);
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            System.out.printf("!! hyperliquid stream frame unparsed: %s%n", e.getMessage());
        }
    }

    /** Terminal-state logging only. Fill sizing never reads this - see class javadoc. */
    private void handleOrderUpdates(JsonNode data) {
        for (JsonNode u : data.isArray() ? data : List.<JsonNode>of()) {
            long oid = u.path("order").path("oid").asLong();
            String status = u.path("status").asText();
            lastKnownStatus.put(oid, status);
            if ("rejected".equals(status) || "marginCanceled".equals(status)) {
                String clientId = oidToClientId.get(oid);
                System.out.printf("!!!! hyperliquid order %s (%s) reached terminal status %s "
                        + "after acceptance%n", oid, clientId, status);
            }
        }
    }

    /** The only source of fill quantity. Sums discrete executions; see class javadoc for why. */
    private void handleUserFills(JsonNode data, Consumer<OrderUpdate> listener) {
        for (JsonNode fill : data.path("fills")) {
            long oid = fill.path("oid").asLong();
            long tid = fill.path("tid").asLong();
            String clientId = oidToClientId.get(oid);
            if (clientId == null) {
                continue;   // not one of ours, or not yet registered (see submit()'s ordering note)
            }
            boolean isNew = seenTradeIds.computeIfAbsent(oid, k -> new CopyOnWriteArraySet<>()).add(tid);
            if (!isNew) {
                continue;   // redelivery on reconnect; already counted
            }
            BigDecimal total = cumulativeFilled.merge(oid, new BigDecimal(fill.path("sz").asText("0")),
                    BigDecimal::add);
            boolean fullyFilled = "filled".equals(lastKnownStatus.get(oid));
            listener.accept(new OrderUpdate("hyperliquid", fill.path("coin").asText(), clientId,
                    fullyFilled ? OrderState.FILLED : OrderState.PARTIALLY_FILLED,
                    total, new BigDecimal(fill.path("px").asText("0")),
                    fill.path("time").asLong(System.currentTimeMillis())));
        }
    }

    private static OrderState parseState(String hlStatus) {
        return switch (hlStatus) {
            case "open", "triggered" -> OrderState.RESTING;
            case "filled" -> OrderState.FILLED;
            case "rejected" -> OrderState.REJECTED;
            default -> OrderState.CANCELLED;   // canceled and every *Canceled variant
        };
    }

    /** Never render credentials, even accidentally through a debug print of this object. */
    @Override
    public String toString() {
        return "HyperliquidGateway[dryRun=" + dryRun + ", account=" + accountAddress + "]";
    }

    /**
     * The minimal MessagePack subset {@code action_hash} needs: ordered maps, arrays, strings,
     * booleans, and non-negative integers, encoded exactly as Python's {@code msgpack.packb} does -
     * smallest fitting type, insertion order preserved rather than sorted. Insertion order matters
     * because Hyperliquid recomputes the same hash from the same field order; a {@code TreeMap} or any
     * key-sorting collection here would silently produce a different hash and every order would be
     * rejected as a bad signature.
     */
    private static final class MsgPack {
        static void write(Object value, ByteArrayOutputStream out) {
            if (value instanceof Map<?, ?> map) {
                header(map.size(), 0x80, 0xde, out);
                for (var e : map.entrySet()) {
                    write(e.getKey(), out);
                    write(e.getValue(), out);
                }
            } else if (value instanceof List<?> list) {
                header(list.size(), 0x90, 0xdc, out);
                for (Object o : list) {
                    write(o, out);
                }
            } else if (value instanceof String s) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                if (b.length < 32) {
                    out.write(0xa0 | b.length);
                } else if (b.length < 256) {
                    out.write(0xd9);
                    out.write(b.length);
                } else {
                    out.write(0xda);
                    out.write(b.length >> 8);
                    out.write(b.length);
                }
                out.writeBytes(b);
            } else if (value instanceof Boolean b) {
                out.write(b ? 0xc3 : 0xc2);
            } else if (value instanceof Integer || value instanceof Long) {
                writeInt(((Number) value).longValue(), out);
            } else {
                throw new IllegalArgumentException("msgpack: unsupported " + value);
            }
        }

        private static void header(int n, int fixBase, int wideTag, ByteArrayOutputStream out) {
            if (n < 16) {
                out.write(fixBase | n);
            } else {
                out.write(wideTag);
                out.write(n >> 8);
                out.write(n);
            }
        }

        /**
         * Non-negative only - every integer this action ever carries (asset index, order id) is one.
         *
         * <p>Discovered live: cancel actions carry Hyperliquid's numeric order id, e.g.
         * 519178520652 - past 2^32. An earlier version of this method had no branch above uint32 and
         * fell through to it regardless, silently truncating to the low 32 bits via Java's narrowing
         * {@code (int)} cast. The JSON body sent to the exchange carried the untruncated Long via
         * Jackson, so the exchange's own hash of the real value did not match the hash this method
         * built for signing - and a signature valid for the WRONG hash still recovers some
         * mathematically valid but meaningless address when checked against the right one. The
         * resulting error, "User or API Wallet 0xa817... does not exist", named an address that had
         * never been configured anywhere, which is what made this a hash mismatch and not a
         * credentials problem. Live cancels are the only path exercised with an integer this large
         * before order placement, which is why it surfaced there first.
         */
        private static void writeInt(long n, ByteArrayOutputStream out) {
            if (n < 0) {
                throw new IllegalArgumentException("msgpack: negative int not supported: " + n);
            }
            if (n <= 127) {
                out.write((int) n);
            } else if (n <= 0xFF) {
                out.write(0xcc);
                out.write((int) n);
            } else if (n <= 0xFFFF) {
                out.write(0xcd);
                out.write((int) (n >> 8));
                out.write((int) n);
            } else if (n <= 0xFFFFFFFFL) {
                out.write(0xce);
                out.write((int) (n >> 24));
                out.write((int) (n >> 16));
                out.write((int) (n >> 8));
                out.write((int) n);
            } else {
                out.write(0xcf);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) (n >> shift));
                }
            }
        }
    }
}
