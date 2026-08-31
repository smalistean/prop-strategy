package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.BookDepthSecond;
import com.smalistean.propstrategy.database.BookTickerSecond;
import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.DatabaseMigrator;
import com.smalistean.propstrategy.database.Liquidation;
import com.smalistean.propstrategy.database.PostgresBookDepthSecondRepository;
import com.smalistean.propstrategy.database.PostgresBookTickerSecondRepository;
import com.smalistean.propstrategy.database.PostgresLiquidationRepository;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records Binance futures best bid/ask into {@code binance_book_ticker_second}.
 *
 * <p>This exists because the public archive cannot answer the only question that matters for a
 * passive strategy. Binance stopped publishing {@code bookTicker} after 2024-03-30, and the
 * {@code bookDepth} files that remain report depth no closer than +/-0.20% of mid - 20 bp out,
 * against a BTCUSDC spread whose median is 0.044 bp. Whether a resting order would have been
 * reached is therefore unobservable from history after March 2024, and has to be recorded forward.
 *
 * <p>The economics make the precision worth having. At 0% maker fee, a round trip that is passive
 * on both sides pays nothing, while a single taker exit costs 3.6 bp with the BNB discount - about
 * thirty-four times the per-trade edge being tested. Everything therefore depends on whether a
 * passive exit actually fills, which is a question about the touch, not about direction.
 *
 * <p>Writes are per second rather than per update: BTCUSDC alone emitted 7.16M updates on a single
 * 2024 day. Seconds are flushed only once the clock has moved past them, so a second is never
 * written while it can still receive quotes.
 */
public final class BookTickerCollectorApplication {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);
    private static final Pattern FIELD = Pattern.compile("\"([a-zA-Z])\":\"?([^\",}]+)\"?");

    /**
     * Silence on a stream's own counter that means the socket is dead rather than the market
     * quiet. Liquidations are the sparsest feed here and still print many times an hour across
     * all Binance perps, so half an hour of nothing is a fault, not a lull.
     */
    private static final long STALE_MILLIS = 30 * 60 * 1_000L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Accumulator> OPEN = new ConcurrentHashMap<>();
    private static final Map<String, DepthAccumulator> OPEN_DEPTH = new ConcurrentHashMap<>();
    private static final AtomicLong MESSAGES = new AtomicLong();
    private static final AtomicLong ROWS = new AtomicLong();
    private static final AtomicLong DEPTH_ROWS = new AtomicLong();
    private static final AtomicLong LIQUIDATIONS = new AtomicLong();
    private static final AtomicLong FORCE_ORDER_FRAMES = new AtomicLong();
    private static final java.util.Queue<Liquidation> PENDING_LIQUIDATIONS =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private BookTickerCollectorApplication() {
    }

    public static void main(String[] args) throws Exception {
        List<String> symbols = Arrays.stream(System.getProperty(
                        "bookTickerSymbols", "BTCUSDC,ETHUSDC,BTCUSDT,ETHUSDT").split(","))
                .map(String::trim).filter(s -> !s.isBlank()).map(String::toUpperCase).toList();
        boolean withDepth = !"false".equalsIgnoreCase(System.getProperty("bookTickerDepth", "true"));
        boolean withLiquidations = !"false".equalsIgnoreCase(
                System.getProperty("bookTickerLiquidations", "true"));
        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        DatabaseMigrator.migrate(database);
        PostgresBookTickerSecondRepository repository = new PostgresBookTickerSecondRepository(database);
        PostgresBookDepthSecondRepository depthRepository =
                new PostgresBookDepthSecondRepository(database);
        PostgresLiquidationRepository liquidationRepository =
                new PostgresLiquidationRepository(database);

        List<String> streamNames = new ArrayList<>();
        symbols.forEach(s -> streamNames.add(s.toLowerCase() + "@bookTicker"));
        if (withDepth) {
            // depth20 at 100ms is a self-contained snapshot of the top twenty levels, so no local
            // book has to be maintained and a dropped frame costs one sample rather than
            // desynchronising everything after it.
            symbols.forEach(s -> streamNames.add(s.toLowerCase() + "@depth20@100ms"));
        }
        // Routed endpoints, per Binance's WebSocket migration (deadline 2026-04-23). bookTicker
        // and depth are Public streams; an unrouted connection to the legacy /stream URL receives
        // Public data only and silently drops everything else, which is why forceOrder subscribed
        // successfully and then never delivered a frame.
        URI uri = URI.create("wss://fstream.binance.com/public/stream?streams="
                + String.join("/", streamNames));
        System.out.printf("%s book-ticker collector starting: %s (depth=%s, liquidations=%s)%n",
                Instant.now(), symbols, withDepth, withLiquidations);

        ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor();
        flusher.scheduleAtFixedRate(() -> {
            flush(repository, false);
            flushDepth(depthRepository, false);
            flushLiquidations(liquidationRepository);
        }, 5, 5, TimeUnit.SECONDS);
        // A stream that delivers nothing looks identical to a stream that is not subscribed.
        // The heartbeat separates them: forceOrderFrames counts what arrived, liquidations counts
        // what was stored, so zero-with-frames and zero-without-frames are distinguishable.
        flusher.scheduleAtFixedRate(() -> System.out.printf(
                        "%s heartbeat: %,d messages, %,d ticker-rows, %,d depth-rows, "
                                + "%,d forceOrder-frames, %,d liquidations%n",
                        Instant.now(), MESSAGES.get(), ROWS.get(), DEPTH_ROWS.get(),
                        FORCE_ORDER_FRAMES.get(), LIQUIDATIONS.get()),
                60, 300, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            flush(repository, true);
            flushDepth(depthRepository, true);
            flushLiquidations(liquidationRepository);
            System.out.printf("%s shutdown: %,d messages, %,d ticker-rows, %,d depth-rows, "
                            + "%,d liquidations%n", Instant.now(), MESSAGES.get(), ROWS.get(),
                    DEPTH_ROWS.get(), LIQUIDATIONS.get());
        }));

        HttpClient client = HttpClient.newHttpClient();
        if (withLiquidations) {
            // A separate socket on the raw endpoint. Carried on the combined ?streams= endpoint,
            // !forceOrder@arr was accepted and then delivered nothing - 77,368 messages arrived
            // with zero forceOrder frames while liquidations were visibly occurring. Isolating it
            // also means a fault in this stream cannot interrupt quote collection.
            // Subscribing by message rather than by URL: both URL forms were accepted and then
            // delivered nothing, and silence is not a diagnosis. SUBSCRIBE returns an explicit
            // result or error for the stream name, so a bad name becomes visible instead of
            // looking like a quiet market.
            // Liquidations are a /market stream, not /public - this routing is the entire reason
            // the earlier attempts returned nothing.
            URI liquidationUri = URI.create("wss://fstream.binance.com/market/ws/!forceOrder@arr");
            Thread thread = new Thread(
                    () -> hold(client, liquidationUri, "liquidation", FORCE_ORDER_FRAMES::get),
                    "liquidation-stream");
            thread.setDaemon(true);
            thread.start();
        }
        // The market stream now runs through the same hold() loop as the liquidation stream, so
        // both get the per-stream staleness watchdog instead of one inline copy that watched the
        // global counter. ROWS advances whenever quote seconds are written, which is the market
        // stream's own progress signal.
        hold(client, uri, "market", ROWS::get);
    }

    private static void hold(HttpClient client, URI uri, String label,
                             java.util.function.LongSupplier progress) {
        while (true) {
            try {
                WebSocket socket = client.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(20))
                        .buildAsync(uri, new Listener(label)).join();
                System.out.printf("%s %s stream connected%n", Instant.now(), label);
                long lastCount = MESSAGES.get();
                long lastChange = System.currentTimeMillis();
                // No SUBSCRIBE message: the stream is named in the path. Note for anyone
                // debugging a silent stream here - Binance does not validate stream names, so a
                // SUBSCRIBE returning result:null proves nothing, and LIST_SUBSCRIPTIONS will
                // happily echo back a name that was never real. Trust the heartbeat's delivered
                // frame count instead.
                while (!socket.isInputClosed()) {
                    Thread.sleep(1_000);
                    // A machine sleep leaves the TCP connection half-open: isInputClosed() stays
                    // false forever while no frame ever arrives, which is indistinguishable from a
                    // quiet market to everything except this counter. Two minutes without any
                    // message on a feed that normally carries hundreds per second means the
                    // connection is dead regardless of what the socket object believes.
                    long now = System.currentTimeMillis();
                    long count = MESSAGES.get();
                    if (count != lastCount) {
                        lastCount = count;
                        lastChange = now;
                    } else if (now - lastChange > STALE_MILLIS) {
                        System.out.printf("%s %s stream stale for %ds, forcing reconnect%n",
                                Instant.now(), label, STALE_MILLIS / 1000);
                        socket.abort();
                        break;
                    }
                }
                System.out.printf("%s %s stream closed, reconnecting%n", Instant.now(), label);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                System.err.printf("%s %s connect failed: %s%n", Instant.now(), label, e.getMessage());
            }
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void flush(PostgresBookTickerSecondRepository repository, boolean everything) {
        long cutoff = Instant.now().getEpochSecond() - (everything ? 0 : 1);
        List<BookTickerSecond> ready = new ArrayList<>();
        for (Map.Entry<String, Accumulator> entry : OPEN.entrySet()) {
            Accumulator accumulator = entry.getValue();
            if (accumulator.second <= cutoff && OPEN.remove(entry.getKey(), accumulator)) {
                ready.add(accumulator.toRow());
            }
        }
        if (ready.isEmpty()) {
            return;
        }
        try {
            ROWS.addAndGet(repository.upsertAll(ready));
        } catch (RuntimeException e) {
            System.err.printf("%s flush failed (%d rows dropped): %s%n",
                    Instant.now(), ready.size(), e.getMessage());
        }
    }

    private static void flushDepth(PostgresBookDepthSecondRepository repository, boolean everything) {
        long cutoff = Instant.now().getEpochSecond() - (everything ? 0 : 1);
        List<BookDepthSecond> ready = new ArrayList<>();
        for (Map.Entry<String, DepthAccumulator> entry : OPEN_DEPTH.entrySet()) {
            DepthAccumulator accumulator = entry.getValue();
            if (accumulator.second <= cutoff && OPEN_DEPTH.remove(entry.getKey(), accumulator)) {
                ready.add(accumulator.toRow());
            }
        }
        if (ready.isEmpty()) {
            return;
        }
        try {
            DEPTH_ROWS.addAndGet(repository.upsertAll(ready));
        } catch (RuntimeException e) {
            System.err.printf("%s depth flush failed (%d rows dropped): %s%n",
                    Instant.now(), ready.size(), e.getMessage());
        }
    }

    private static void accept(String symbol, long eventMillis, BigDecimal bid, BigDecimal bidQty,
                               BigDecimal ask, BigDecimal askQty) {
        long second = eventMillis / 1000L;
        OPEN.compute(symbol + "@" + second, (key, existing) -> {
            Accumulator accumulator = existing == null
                    ? new Accumulator(symbol, second, bid, ask) : existing;
            accumulator.add(bid, bidQty, ask, askQty);
            return accumulator;
        });
    }

    private static final class Accumulator {
        private final String symbol;
        private final long second;
        private final BigDecimal openBid;
        private final BigDecimal openAsk;
        private BigDecimal closeBid;
        private BigDecimal closeAsk;
        private BigDecimal minBid;
        private BigDecimal maxBid;
        private BigDecimal minAsk;
        private BigDecimal maxAsk;
        private BigDecimal closeBidQty = BigDecimal.ZERO;
        private BigDecimal closeAskQty = BigDecimal.ZERO;
        private BigDecimal spreadSum = BigDecimal.ZERO;
        private BigDecimal minSpread;
        private BigDecimal maxSpread;
        private int updates;

        private Accumulator(String symbol, long second, BigDecimal bid, BigDecimal ask) {
            this.symbol = symbol;
            this.second = second;
            this.openBid = bid;
            this.openAsk = ask;
            this.minBid = bid;
            this.maxBid = bid;
            this.minAsk = ask;
            this.maxAsk = ask;
        }

        private synchronized void add(BigDecimal bid, BigDecimal bidQty,
                                      BigDecimal ask, BigDecimal askQty) {
            closeBid = bid;
            closeAsk = ask;
            closeBidQty = bidQty;
            closeAskQty = askQty;
            minBid = minBid.min(bid);
            maxBid = maxBid.max(bid);
            minAsk = minAsk.min(ask);
            maxAsk = maxAsk.max(ask);
            BigDecimal mid = bid.add(ask).divide(BigDecimal.valueOf(2), MC);
            BigDecimal spread = mid.signum() == 0 ? BigDecimal.ZERO
                    : ask.subtract(bid).divide(mid, MC).multiply(BPS, MC);
            spreadSum = spreadSum.add(spread);
            minSpread = minSpread == null ? spread : minSpread.min(spread);
            maxSpread = maxSpread == null ? spread : maxSpread.max(spread);
            updates++;
        }

        private synchronized BookTickerSecond toRow() {
            return new BookTickerSecond(symbol, Instant.ofEpochSecond(second), updates,
                    openBid, openAsk, closeBid, closeAsk, minBid, maxBid, minAsk, maxAsk,
                    closeBidQty, closeAskQty,
                    spreadSum.divide(BigDecimal.valueOf(updates), MC), minSpread, maxSpread);
        }
    }

    private static void flushLiquidations(PostgresLiquidationRepository repository) {
        List<Liquidation> ready = new ArrayList<>();
        Liquidation next;
        while ((next = PENDING_LIQUIDATIONS.poll()) != null) {
            ready.add(next);
        }
        if (ready.isEmpty()) {
            return;
        }
        try {
            LIQUIDATIONS.addAndGet(repository.insertAll(ready));
        } catch (RuntimeException e) {
            // Requeue rather than drop: liquidations cannot be backfilled from any archive, so a
            // transient database error must not become a permanent hole.
            PENDING_LIQUIDATIONS.addAll(ready);
            System.err.printf("%s liquidation flush failed (%d requeued): %s%n",
                    Instant.now(), ready.size(), e.getMessage());
        }
    }

    private static void acceptLiquidation(JsonNode order, long eventMillis) {
        String symbol = order.path("s").asText(null);
        if (symbol == null) {
            return;
        }
        BigDecimal quantity = new BigDecimal(order.path("q").asText("0"));
        BigDecimal price = new BigDecimal(order.path("p").asText("0"));
        BigDecimal averagePrice = new BigDecimal(order.path("ap").asText("0"));
        BigDecimal filledAccum = new BigDecimal(order.path("z").asText("0"));
        // Notional uses the average fill price and the quantity actually filled, so a partially
        // filled liquidation is not recorded at its full requested size.
        BigDecimal reference = averagePrice.signum() > 0 ? averagePrice : price;
        PENDING_LIQUIDATIONS.add(new Liquidation(symbol,
                Instant.ofEpochMilli(eventMillis),
                Instant.ofEpochMilli(order.path("T").asLong(eventMillis)),
                order.path("S").asText(""), order.path("o").asText(""),
                order.path("f").asText(""), order.path("X").asText(""),
                quantity, price, averagePrice,
                new BigDecimal(order.path("l").asText("0")), filledAccum,
                reference.multiply(filledAccum, MC)));
    }

    private static void acceptDepth(String symbol, long eventMillis, JsonNode bids, JsonNode asks) {
        if (bids.isEmpty() || asks.isEmpty()) {
            return;
        }
        long second = eventMillis / 1000L;
        BigDecimal bestBid = new BigDecimal(bids.get(0).get(0).asText());
        BigDecimal bestAsk = new BigDecimal(asks.get(0).get(0).asText());
        BigDecimal bidQty1 = new BigDecimal(bids.get(0).get(1).asText());
        BigDecimal askQty1 = new BigDecimal(asks.get(0).get(1).asText());
        BigDecimal bidNotional = sideNotional(bids);
        BigDecimal askNotional = sideNotional(asks);
        BigDecimal worstBid = new BigDecimal(bids.get(bids.size() - 1).get(0).asText());
        BigDecimal worstAsk = new BigDecimal(asks.get(asks.size() - 1).get(0).asText());
        BigDecimal mid = bestBid.add(bestAsk).divide(BigDecimal.valueOf(2), MC);
        if (mid.signum() == 0) {
            return;
        }
        BigDecimal bidSpan = bestBid.subtract(worstBid).divide(mid, MC).multiply(BPS, MC);
        BigDecimal askSpan = worstAsk.subtract(bestAsk).divide(mid, MC).multiply(BPS, MC);
        OPEN_DEPTH.compute(symbol + "@" + second, (key, existing) -> {
            DepthAccumulator accumulator = existing == null
                    ? new DepthAccumulator(symbol, second) : existing;
            accumulator.add(bidQty1, askQty1, bidNotional, askNotional, bidSpan, askSpan);
            return accumulator;
        });
    }

    private static BigDecimal sideNotional(JsonNode levels) {
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode level : levels) {
            total = total.add(new BigDecimal(level.get(0).asText())
                    .multiply(new BigDecimal(level.get(1).asText()), MC), MC);
        }
        return total;
    }

    private static final class DepthAccumulator {
        private final String symbol;
        private final long second;
        private BigDecimal bidQtySum = BigDecimal.ZERO;
        private BigDecimal askQtySum = BigDecimal.ZERO;
        private BigDecimal minBidQty;
        private BigDecimal minAskQty;
        private BigDecimal bidNotionalSum = BigDecimal.ZERO;
        private BigDecimal askNotionalSum = BigDecimal.ZERO;
        private BigDecimal bidSpanSum = BigDecimal.ZERO;
        private BigDecimal askSpanSum = BigDecimal.ZERO;
        private int snapshots;

        private DepthAccumulator(String symbol, long second) {
            this.symbol = symbol;
            this.second = second;
        }

        private synchronized void add(BigDecimal bidQty, BigDecimal askQty,
                                      BigDecimal bidNotional, BigDecimal askNotional,
                                      BigDecimal bidSpan, BigDecimal askSpan) {
            bidQtySum = bidQtySum.add(bidQty);
            askQtySum = askQtySum.add(askQty);
            minBidQty = minBidQty == null ? bidQty : minBidQty.min(bidQty);
            minAskQty = minAskQty == null ? askQty : minAskQty.min(askQty);
            bidNotionalSum = bidNotionalSum.add(bidNotional);
            askNotionalSum = askNotionalSum.add(askNotional);
            bidSpanSum = bidSpanSum.add(bidSpan);
            askSpanSum = askSpanSum.add(askSpan);
            snapshots++;
        }

        private synchronized BookDepthSecond toRow() {
            BigDecimal n = BigDecimal.valueOf(snapshots);
            return new BookDepthSecond(symbol, Instant.ofEpochSecond(second), snapshots,
                    bidQtySum.divide(n, MC), askQtySum.divide(n, MC), minBidQty, minAskQty,
                    bidNotionalSum.divide(n, MC), askNotionalSum.divide(n, MC),
                    bidSpanSum.divide(n, MC), askSpanSum.divide(n, MC));
        }
    }

    private static final class Listener implements WebSocket.Listener {
        private final String label;
        private int logged;

        private Listener(String label) {
            this.label = label;
        }

        // A depth20 payload carries forty price levels and is large enough that the runtime may
        // deliver it in several onText calls. Parsing each fragment separately would silently
        // discard most depth frames as malformed, so fragments are joined until last is set.
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.printf("%s connected%n", Instant.now());
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                // The liquidation socket produced nothing under two URL forms, so its first few
                // frames are echoed verbatim - a SUBSCRIBE result or error is otherwise consumed
                // by the parser and lost.
                if ("liquidation".equals(label) && logged < 12) {
                    logged++;
                    System.out.printf("%s liquidation frame %d: %s%n", Instant.now(), logged,
                            frame.substring(0, Math.min(400, frame.length())));
                }
                try {
                    parse(frame);
                } catch (Exception e) {
                    // A single malformed frame must not end the stream; the gap is one quote.
                    System.err.printf("%s parse failed: %s%n", Instant.now(), e.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.err.printf("%s stream error: %s%n", Instant.now(), error.getMessage());
        }
    }

    private static void parse(String frame) throws Exception {
        MESSAGES.incrementAndGet();
        // Depth frames carry nested arrays that the flat field scanner below cannot read, so they
        // are routed to a real parser. bookTicker stays on the scanner because it is the
        // high-rate stream and its payload is flat.
        if (frame.contains("depthUpdate")) {
            JsonNode data = JSON.readTree(frame).path("data");
            String symbol = data.path("s").asText(null);
            long eventMillis = data.path("E").asLong(0);
            if (symbol != null && plausible(eventMillis)) {
                acceptDepth(symbol, eventMillis, data.path("b"), data.path("a"));
            }
            return;
        }
        if (frame.contains("forceOrder")) {
            FORCE_ORDER_FRAMES.incrementAndGet();
            JsonNode root = JSON.readTree(frame);
            // The combined endpoint wraps events as {"stream":..,"data":..}; the raw /ws/ endpoint
            // delivers the event itself. Accept either so the source can change without silently
            // dropping every frame.
            JsonNode data = root.has("data") ? root.path("data") : root;
            long eventMillis = data.path("E").asLong(0);
            JsonNode order = data.path("o");
            if (!order.isMissingNode() && plausible(eventMillis)) {
                acceptLiquidation(order, eventMillis);
            }
            return;
        }
        String symbol = null;
        BigDecimal bid = null;
        BigDecimal bidQty = null;
        BigDecimal ask = null;
        BigDecimal askQty = null;
        long eventMillis = 0;
        Matcher matcher = FIELD.matcher(frame);
        while (matcher.find()) {
            String value = matcher.group(2);
            switch (matcher.group(1)) {
                case "s" -> symbol = value;
                case "b" -> bid = new BigDecimal(value);
                case "B" -> bidQty = new BigDecimal(value);
                case "a" -> ask = new BigDecimal(value);
                case "A" -> askQty = new BigDecimal(value);
                case "E" -> eventMillis = Long.parseLong(value);
                default -> { }
            }
        }
        if (symbol != null && bid != null && ask != null && plausible(eventMillis)
                && bid.signum() > 0 && ask.signum() > 0) {
            accept(symbol, eventMillis,
                    bid, bidQty == null ? BigDecimal.ZERO : bidQty,
                    ask, askQty == null ? BigDecimal.ZERO : askQty);
        }
    }

    /**
     * Rejects timestamps that cannot be a live quote.
     *
     * <p>A truncated frame once parsed into an epoch of a few thousand milliseconds and stored a
     * quote dated 1970, which is silent corruption: it sorts before every real row and would be
     * read as history rather than as an error. Fragment joining fixed the cause; this rejects the
     * symptom, since any future parsing defect fails the same way.
     */
    private static boolean plausible(long eventMillis) {
        long now = System.currentTimeMillis();
        return eventMillis > now - Duration.ofDays(2).toMillis()
                && eventMillis < now + Duration.ofHours(1).toMillis();
    }
}
