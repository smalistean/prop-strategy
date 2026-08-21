package com.smalistean.propstrategy.xvf.shadow;

import com.fasterxml.jackson.databind.JsonNode;
import com.smalistean.propstrategy.xvf.shadow.XvfPublicJsonTransport.TimedJson;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ActivitySnapshot;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Bybit V5 linear-perpetual public market snapshot source. */
public final class BybitXvfVenueSnapshotSource implements XvfVenueSnapshotSource {

    private static final URI PRODUCTION = URI.create("https://api.bybit.com");
    private static final String VENUE = "bybit";
    private static final Set<String> CRYPTO_SYMBOL_TYPES = Set.of("", "innovation");

    private final XvfPublicJsonTransport transport;
    private final URI baseUri;

    public BybitXvfVenueSnapshotSource() {
        this(new JdkXvfPublicJsonTransport(), PRODUCTION);
    }

    BybitXvfVenueSnapshotSource(XvfPublicJsonTransport transport, URI baseUri) {
        this.transport = java.util.Objects.requireNonNull(transport, "transport");
        this.baseUri = java.util.Objects.requireNonNull(baseUri, "baseUri");
    }

    @Override
    public String venue() {
        return VENUE;
    }

    @Override
    public VenueSnapshot fetch(Set<String> venueSymbols) {
        Set<String> symbols = XvfSnapshotParsing.symbols(venueSymbols);
        Map<String, TickerData> tickers = tickers(transport.get(XvfSnapshotParsing.uri(
                baseUri, "/v5/market/tickers?category=linear")));
        Map<String, InstrumentData> instruments = instruments();
        Map<String, InstrumentSnapshot> snapshots = new LinkedHashMap<>();
        List<SnapshotIssue> issues = new ArrayList<>();

        for (String symbol : symbols.stream().sorted().toList()) {
            XvfInstrumentSnapshotBuilder builder = builder(symbol);
            TickerData ticker = tickers.get(symbol);
            if (ticker == null) {
                builder.missing.addAll(List.of("reference", "activity", "topOfBook"));
            } else {
                builder.reference = Optional.of(ticker.reference());
                builder.activity = Optional.of(ticker.activity());
                builder.topOfBook = ticker.topOfBook();
                if (builder.topOfBook.isEmpty()) {
                    builder.missing.add("topOfBook");
                }
                addReferenceMissingFields(builder);
            }

            InstrumentData instrument = instruments.get(symbol);
            if (instrument == null) {
                builder.missing.add("rules");
            } else {
                builder.rules = Optional.of(instrument.rules());
                instrument.refusalReason().ifPresent(reason -> issues.add(new SnapshotIssue(
                        IssueSeverity.ERROR, VENUE, Optional.of(symbol),
                        "NON_CRYPTO_OR_UNTRADEABLE_INSTRUMENT", reason)));
            }
            fetchDepth(symbol, builder, issues);
            snapshots.put(symbol, builder.build());
        }
        return new VenueSnapshot(VENUE, snapshots, issues);
    }

    private void fetchDepth(String symbol, XvfInstrumentSnapshotBuilder builder,
                            List<SnapshotIssue> issues) {
        try {
            TimedJson response = transport.get(XvfSnapshotParsing.uri(baseUri,
                    "/v5/market/orderbook?category=linear&symbol="
                            + XvfSnapshotParsing.encode(symbol) + "&limit=50"));
            JsonNode result = result(response, "bybit orderbook");
            Optional<Instant> sourceAt = XvfSnapshotParsing.optionalEpochMillis(result, "cts")
                    .or(() -> XvfSnapshotParsing.optionalEpochMillis(result, "ts"));
            builder.orderBook = Optional.of(new OrderBookSnapshot(
                    XvfSnapshotParsing.priceLevels(result.get("b"), "bybit bids", false),
                    XvfSnapshotParsing.priceLevels(result.get("a"), "bybit asks", false),
                    XvfSnapshotParsing.timing(response, sourceAt)));
        } catch (RuntimeException e) {
            builder.missing.add("orderBook");
            issues.add(new SnapshotIssue(IssueSeverity.ERROR, VENUE, Optional.of(symbol),
                    "DEPTH_UNAVAILABLE", detail(e)));
        }
    }

    static Map<String, TickerData> tickers(TimedJson response) {
        JsonNode root = XvfSnapshotParsing.requireObject(response.body(), "bybit tickers");
        JsonNode result = result(response, "bybit tickers");
        JsonNode rows = XvfSnapshotParsing.requireArray(result.get("list"), "bybit tickers.list");
        Optional<Instant> sourceAt = XvfSnapshotParsing.optionalEpochMillis(root, "time");
        ResponseTiming timing = XvfSnapshotParsing.timing(response, sourceAt);
        Map<String, TickerData> out = new HashMap<>();
        for (JsonNode row : rows) {
            XvfSnapshotParsing.requireObject(row, "bybit ticker row");
            String symbol = XvfSnapshotParsing.text(row, "symbol", "bybit ticker row");
            ReferenceSnapshot reference = new ReferenceSnapshot(
                    XvfSnapshotParsing.optionalDecimal(row, "markPrice"),
                    XvfSnapshotParsing.optionalDecimal(row, "indexPrice"),
                    Optional.empty(),
                    XvfSnapshotParsing.optionalDecimal(row, "fundingRate"),
                    XvfSnapshotParsing.optionalEpochMillis(row, "nextFundingTime"),
                    XvfSnapshotParsing.optionalPositiveInteger(row, "fundingIntervalHour"),
                    XvfSnapshotParsing.optionalDecimal(row, "openInterest"), timing);
            ActivitySnapshot activity = new ActivitySnapshot(
                    XvfSnapshotParsing.optionalDecimal(row, "turnover24h"), timing);
            Optional<TopOfBookSnapshot> top = topOfBook(row, timing);
            out.put(symbol, new TickerData(reference, activity, top));
        }
        return Map.copyOf(out);
    }

    private Map<String, InstrumentData> instruments() {
        Map<String, InstrumentData> out = new HashMap<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = "";
        do {
            String path = "/v5/market/instruments-info?category=linear&limit=1000"
                    + (cursor.isBlank() ? "" : "&cursor=" + XvfSnapshotParsing.encode(cursor));
            TimedJson response = transport.get(XvfSnapshotParsing.uri(baseUri, path));
            JsonNode root = XvfSnapshotParsing.requireObject(response.body(), "bybit instruments-info");
            JsonNode result = result(response, "bybit instruments-info");
            JsonNode rows = XvfSnapshotParsing.requireArray(result.get("list"),
                    "bybit instruments-info.list");
            ResponseTiming timing = XvfSnapshotParsing.timing(response,
                    XvfSnapshotParsing.optionalEpochMillis(root, "time"));
            for (JsonNode row : rows) {
                XvfSnapshotParsing.requireObject(row, "bybit instrument row");
                String symbol = XvfSnapshotParsing.text(row, "symbol", "bybit instrument row");
                JsonNode lot = XvfSnapshotParsing.requireObject(row.get("lotSizeFilter"),
                        "bybit instrument lotSizeFilter");
                JsonNode price = XvfSnapshotParsing.requireObject(row.get("priceFilter"),
                        "bybit instrument priceFilter");
                JsonNode leverage = row.path("leverageFilter");
                String symbolType = row.path("symbolType").asText("");
                String status = row.path("status").asText("");
                String contractType = row.path("contractType").asText("LinearPerpetual");
                boolean crypto = CRYPTO_SYMBOL_TYPES.contains(symbolType);
                boolean trading = crypto && "Trading".equals(status)
                        && "LinearPerpetual".equals(contractType);
                Optional<String> refusal = trading ? Optional.empty() : Optional.of(
                        "Bybit " + symbol + " status=" + status + ", contractType=" + contractType
                                + ", symbolType=" + symbolType);
                out.put(symbol, new InstrumentData(new InstrumentRules(
                        XvfSnapshotParsing.optionalDecimal(price, "tickSize"),
                        XvfSnapshotParsing.optionalDecimal(lot, "qtyStep"),
                        XvfSnapshotParsing.optionalDecimal(lot, "minOrderQty"),
                        XvfSnapshotParsing.optionalDecimal(lot, "minNotionalValue"),
                        XvfSnapshotParsing.optionalDecimal(lot, "maxOrderQty"),
                        XvfSnapshotParsing.optionalPositiveInteger(leverage, "maxLeverage"),
                        trading, timing), refusal));
            }
            cursor = result.path("nextPageCursor").asText("");
            if (!cursor.isBlank() && !seenCursors.add(cursor)) {
                throw XvfSnapshotParsing.schema("bybit instruments-info repeated cursor " + cursor);
            }
        } while (!cursor.isBlank());
        return Map.copyOf(out);
    }

    static JsonNode result(TimedJson response, String what) {
        JsonNode root = XvfSnapshotParsing.requireObject(response.body(), what);
        JsonNode code = root.get("retCode");
        if (code == null || !code.canConvertToInt()) {
            throw XvfSnapshotParsing.schema(what + ".retCode must be an integer");
        }
        if (code.intValue() != 0) {
            throw new IllegalStateException(what + " retCode " + code.intValue() + ": "
                    + root.path("retMsg").asText("unknown error"));
        }
        return XvfSnapshotParsing.requireObject(root.get("result"), what + ".result");
    }

    private static Optional<TopOfBookSnapshot> topOfBook(JsonNode row, ResponseTiming timing) {
        Optional<BigDecimal> bid = XvfSnapshotParsing.optionalDecimal(row, "bid1Price");
        Optional<BigDecimal> bidSize = XvfSnapshotParsing.optionalDecimal(row, "bid1Size");
        Optional<BigDecimal> ask = XvfSnapshotParsing.optionalDecimal(row, "ask1Price");
        Optional<BigDecimal> askSize = XvfSnapshotParsing.optionalDecimal(row, "ask1Size");
        if (bid.isEmpty() || bidSize.isEmpty() || ask.isEmpty() || askSize.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TopOfBookSnapshot(
                bid.get(), bidSize.get(), ask.get(), askSize.get(), timing));
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
            missing(builder, "reference.pendingFundingRate", reference.pendingFundingRate());
            missing(builder, "reference.nextFundingTime", reference.nextFundingTime());
            missing(builder, "reference.fundingIntervalHours", reference.fundingIntervalHours());
        });
    }

    private static void missing(XvfInstrumentSnapshotBuilder builder, String field, Optional<?> value) {
        if (value.isEmpty()) {
            builder.missing.add(field);
        }
    }

    private static String detail(RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    record TickerData(ReferenceSnapshot reference, ActivitySnapshot activity,
                      Optional<TopOfBookSnapshot> topOfBook) {
        TickerData {
            java.util.Objects.requireNonNull(reference, "reference");
            java.util.Objects.requireNonNull(activity, "activity");
            java.util.Objects.requireNonNull(topOfBook, "topOfBook");
        }
    }

    private record InstrumentData(InstrumentRules rules, Optional<String> refusalReason) {
        private InstrumentData {
            java.util.Objects.requireNonNull(rules, "rules");
            java.util.Objects.requireNonNull(refusalReason, "refusalReason");
        }
    }
}
