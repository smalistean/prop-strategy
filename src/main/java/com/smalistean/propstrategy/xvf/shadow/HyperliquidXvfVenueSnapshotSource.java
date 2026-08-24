package com.smalistean.propstrategy.xvf.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.smalistean.propstrategy.xvf.shadow.XvfPublicJsonTransport.TimedJson;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ActivitySnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.BookLevel;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentRules;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.IssueSeverity;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.OrderBookSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ReferenceSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ResponseTiming;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.SnapshotIssue;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.TopOfBookSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.VenueSnapshot;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;

/** Hyperliquid public {@code /info} snapshot source. No agent wallet or private key is involved. */
public final class HyperliquidXvfVenueSnapshotSource implements XvfVenueSnapshotSource {

    private static final URI PRODUCTION = URI.create("https://api.hyperliquid.xyz");
    private static final String VENUE = "hyperliquid";
    private static final BigDecimal MINIMUM_NOTIONAL_USD = new BigDecimal("10");
    /** Stricter limit than the shared pool because Hyperliquid public endpoints are rate-limited. */
    private static final int MAX_CONCURRENT_SYMBOL_REQUESTS = 10;

    private final XvfPublicJsonTransport transport;
    private final URI baseUri;

    public HyperliquidXvfVenueSnapshotSource() {
        this(new JdkXvfPublicJsonTransport(), PRODUCTION);
    }

    HyperliquidXvfVenueSnapshotSource(XvfPublicJsonTransport transport, URI baseUri) {
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.baseUri = java.util.Objects.requireNonNull(baseUri, "baseUri");
    }

    @Override
    public String venue() {
        return VENUE;
    }

    @Override
    public VenueSnapshot fetch(Set<String> venueSymbols) {
        return fetch(venueSymbols, Runnable::run);
    }

    @Override
    public VenueSnapshot fetch(Set<String> venueSymbols, Executor executor) {
        Set<String> symbols = XvfSnapshotParsing.symbols(venueSymbols);
        Map<String, MetaData> metadata = metadata(transport.post(
                XvfSnapshotParsing.uri(baseUri, "/info"), "{\"type\":\"metaAndAssetCtxs\"}"));

        Semaphore semaphore = new Semaphore(MAX_CONCURRENT_SYMBOL_REQUESTS);
        List<String> sortedSymbols = symbols.stream().sorted().toList();
        List<Future<SymbolResult>> futures = new ArrayList<>(sortedSymbols.size());
        for (String symbol : sortedSymbols) {
            futures.add(submit(executor, () -> fetchSymbol(symbol, metadata, semaphore)));
        }

        Map<String, InstrumentSnapshot> snapshots = new LinkedHashMap<>();
        List<SnapshotIssue> issues = new ArrayList<>();
        for (int index = 0; index < sortedSymbols.size(); index++) {
            SymbolResult result = await(futures.get(index), sortedSymbols.get(index));
            snapshots.put(sortedSymbols.get(index), result.snapshot());
            issues.addAll(result.issues());
        }
        return new VenueSnapshot(VENUE, snapshots, issues);
    }

    private SymbolResult fetchSymbol(String symbol, Map<String, MetaData> metadata,
                                     Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for Hyperliquid rate limit", e);
        }
        try {
            XvfInstrumentSnapshotBuilder builder = builder(symbol);
            List<SnapshotIssue> issues = new ArrayList<>();
            MetaData data = metadata.get(symbol);
            if (data == null) {
                builder.missing.addAll(List.of("reference", "activity", "rules"));
            } else {
                builder.reference = Optional.of(data.reference());
                builder.activity = Optional.of(data.activity());
                builder.rules = Optional.of(data.rules());
                addReferenceMissingFields(builder);
                if (data.rules().tickSize().isEmpty()) {
                    // Hyperliquid uses significant-figure rules rather than a fixed price tick.
                    builder.missing.add("rules.fixedTickSizeNotPublished");
                }
            }
            fetchBook(symbol, builder, issues);
            return new SymbolResult(builder.build(), issues);
        } finally {
            semaphore.release();
        }
    }

    private void fetchBook(String symbol, XvfInstrumentSnapshotBuilder builder,
                           List<SnapshotIssue> issues) {
        try {
            String body = "{\"type\":\"l2Book\",\"coin\":\"" + jsonString(symbol) + "\"}";
            TimedJson response = transport.post(XvfSnapshotParsing.uri(baseUri, "/info"), body);
            JsonNode root = XvfSnapshotParsing.requireObject(response.body(), "hyperliquid l2Book");
            JsonNode levels = XvfSnapshotParsing.requireArray(root.get("levels"),
                    "hyperliquid l2Book.levels");
            if (levels.size() != 2) {
                throw XvfSnapshotParsing.schema("hyperliquid l2Book.levels must contain bids and asks");
            }
            List<BookLevel> bids = XvfSnapshotParsing.priceLevels(
                    levels.get(0), "hyperliquid bids", true);
            List<BookLevel> asks = XvfSnapshotParsing.priceLevels(
                    levels.get(1), "hyperliquid asks", true);
            ResponseTiming timing = XvfSnapshotParsing.timing(response,
                    XvfSnapshotParsing.optionalEpochMillis(root, "time"));
            builder.orderBook = Optional.of(new OrderBookSnapshot(bids, asks, timing));
            BookLevel bid = bids.get(0);
            BookLevel ask = asks.get(0);
            builder.topOfBook = Optional.of(new TopOfBookSnapshot(
                    bid.price(), bid.quantity(), ask.price(), ask.quantity(), timing));
        } catch (RuntimeException e) {
            builder.missing.addAll(List.of("topOfBook", "orderBook"));
            issues.add(new SnapshotIssue(IssueSeverity.ERROR, VENUE, Optional.of(symbol),
                    "L2_BOOK_UNAVAILABLE", detail(e)));
        }
    }

    static Map<String, MetaData> metadata(TimedJson response) {
        JsonNode root = XvfSnapshotParsing.requireArray(response.body(),
                "hyperliquid metaAndAssetCtxs");
        if (root.size() != 2) {
            throw XvfSnapshotParsing.schema(
                    "hyperliquid metaAndAssetCtxs must contain metadata and contexts");
        }
        JsonNode meta = XvfSnapshotParsing.requireObject(root.get(0), "hyperliquid metadata");
        JsonNode universe = XvfSnapshotParsing.requireArray(meta.get("universe"),
                "hyperliquid metadata.universe");
        JsonNode contexts = XvfSnapshotParsing.requireArray(root.get(1),
                "hyperliquid asset contexts");
        if (universe.size() != contexts.size()) {
            throw XvfSnapshotParsing.schema("hyperliquid universe/context lengths differ: "
                    + universe.size() + " vs " + contexts.size());
        }
        ResponseTiming timing = XvfSnapshotParsing.timing(response, Optional.empty());
        Map<String, MetaData> out = new HashMap<>();
        for (int index = 0; index < universe.size(); index++) {
            JsonNode instrument = XvfSnapshotParsing.requireObject(universe.get(index),
                    "hyperliquid universe row");
            JsonNode context = XvfSnapshotParsing.requireObject(contexts.get(index),
                    "hyperliquid context row");
            String symbol = XvfSnapshotParsing.text(instrument, "name", "hyperliquid universe row");
            int sizeDecimals = integer(instrument, "szDecimals", "hyperliquid universe row");
            if (sizeDecimals < 0) {
                throw XvfSnapshotParsing.schema("hyperliquid szDecimals cannot be negative");
            }
            BigDecimal quantityStep = BigDecimal.ONE.movePointLeft(sizeDecimals);
            boolean trading = !instrument.path("isDelisted").asBoolean(false);
            InstrumentRules rules = new InstrumentRules(
                    Optional.empty(), Optional.of(quantityStep), Optional.of(quantityStep),
                    Optional.of(MINIMUM_NOTIONAL_USD), Optional.empty(),
                    XvfSnapshotParsing.optionalPositiveInteger(instrument, "maxLeverage"),
                    trading, timing);
            ReferenceSnapshot reference = new ReferenceSnapshot(
                    XvfSnapshotParsing.optionalDecimal(context, "markPx"),
                    XvfSnapshotParsing.optionalDecimal(context, "oraclePx"),
                    XvfSnapshotParsing.optionalDecimal(context, "midPx"),
                    XvfSnapshotParsing.optionalDecimal(context, "funding"),
                    Optional.empty(), Optional.of(1),
                    XvfSnapshotParsing.optionalDecimal(context, "openInterest"), timing);
            ActivitySnapshot activity = new ActivitySnapshot(
                    XvfSnapshotParsing.optionalDecimal(context, "dayNtlVlm"), timing);
            out.put(symbol, new MetaData(reference, activity, rules));
        }
        return Map.copyOf(out);
    }

    private static int integer(JsonNode node, String field, String what) {
        JsonNode value = node.get(field);
        if (value == null || !value.canConvertToInt()) {
            throw XvfSnapshotParsing.schema(what + "." + field + " must be an integer");
        }
        return value.intValue();
    }

    private static Future<SymbolResult> submit(Executor executor,
                                               java.util.concurrent.Callable<SymbolResult> task) {
        FutureTask<SymbolResult> future = new FutureTask<>(task);
        executor.execute(future);
        return future;
    }

    private static SymbolResult await(Future<SymbolResult> future, String symbol) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching " + VENUE + " " + symbol, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Unexpected failure fetching " + VENUE + " " + symbol,
                    e.getCause());
        }
    }

    private static XvfInstrumentSnapshotBuilder builder(String symbol) {
        return new XvfInstrumentSnapshotBuilder(VENUE, symbol,
                XvfSnapshotParsing.canonicalBase(VENUE, symbol),
                XvfSnapshotParsing.baseUnitsPerContract(VENUE, symbol));
    }

    private static void addReferenceMissingFields(XvfInstrumentSnapshotBuilder builder) {
        builder.reference.ifPresent(reference -> {
            missing(builder, "reference.markPrice", reference.markPrice());
            missing(builder, "reference.indexPrice", reference.indexPrice());
            missing(builder, "reference.midPrice", reference.midPrice());
            missing(builder, "reference.pendingFundingRate", reference.pendingFundingRate());
            // Hyperliquid settles hourly but does not publish a next-stamp field in this response.
            missing(builder, "reference.nextFundingTimeNotPublished", reference.nextFundingTime());
        });
    }

    private static void missing(XvfInstrumentSnapshotBuilder builder, String field, Optional<?> value) {
        if (value.isEmpty()) {
            builder.missing.add(field);
        }
    }

    private static String jsonString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String detail(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    record MetaData(ReferenceSnapshot reference, ActivitySnapshot activity, InstrumentRules rules) {
        MetaData {
            java.util.Objects.requireNonNull(reference, "reference");
            java.util.Objects.requireNonNull(activity, "activity");
            java.util.Objects.requireNonNull(rules, "rules");
        }
    }

    private record SymbolResult(InstrumentSnapshot snapshot, List<SnapshotIssue> issues) {
        SymbolResult {
            java.util.Objects.requireNonNull(snapshot, "snapshot");
            issues = Collections.unmodifiableList(new ArrayList<>(
                    java.util.Objects.requireNonNull(issues, "issues")));
        }
    }
}
