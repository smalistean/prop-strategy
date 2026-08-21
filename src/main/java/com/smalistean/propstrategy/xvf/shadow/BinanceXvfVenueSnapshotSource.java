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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Binance USD-M public market snapshot source. It has no signed/account endpoint. */
public final class BinanceXvfVenueSnapshotSource implements XvfVenueSnapshotSource {

    private static final URI PRODUCTION = URI.create("https://fapi.binance.com");
    private static final String VENUE = "binance";
    private static final int STANDARD_FUNDING_INTERVAL_HOURS = 8;

    private final XvfPublicJsonTransport transport;
    private final URI baseUri;

    public BinanceXvfVenueSnapshotSource() {
        this(new JdkXvfPublicJsonTransport(), PRODUCTION);
    }

    BinanceXvfVenueSnapshotSource(XvfPublicJsonTransport transport, URI baseUri) {
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
        TimedJson premiumResponse = transport.get(XvfSnapshotParsing.uri(
                baseUri, "/fapi/v1/premiumIndex"));
        TimedJson activityResponse = transport.get(XvfSnapshotParsing.uri(
                baseUri, "/fapi/v1/ticker/24hr"));
        TimedJson rulesResponse = transport.get(XvfSnapshotParsing.uri(
                baseUri, "/fapi/v1/exchangeInfo"));
        TimedJson fundingInfoResponse = transport.get(XvfSnapshotParsing.uri(
                baseUri, "/fapi/v1/fundingInfo"));

        Map<String, ReferenceSnapshot> references = references(
                premiumResponse, fundingIntervals(fundingInfoResponse));
        Map<String, ActivitySnapshot> activities = activities(activityResponse);
        Map<String, InstrumentRules> rules = rules(rulesResponse);
        Map<String, InstrumentSnapshot> snapshots = new LinkedHashMap<>();
        List<SnapshotIssue> issues = new ArrayList<>();

        for (String symbol : symbols.stream().sorted().toList()) {
            XvfInstrumentSnapshotBuilder builder = builder(symbol);
            builder.reference = Optional.ofNullable(references.get(symbol));
            builder.activity = Optional.ofNullable(activities.get(symbol));
            builder.rules = Optional.ofNullable(rules.get(symbol));
            missing(builder, "reference", builder.reference);
            missing(builder, "activity", builder.activity);
            missing(builder, "rules", builder.rules);

            fetchTopOfBook(symbol, builder, issues);
            fetchDepth(symbol, builder, issues);
            addReferenceMissingFields(builder);
            snapshots.put(symbol, builder.build());
        }
        return new VenueSnapshot(VENUE, snapshots, issues);
    }

    private void fetchTopOfBook(String symbol, XvfInstrumentSnapshotBuilder builder,
                                List<SnapshotIssue> issues) {
        try {
            TimedJson response = transport.get(XvfSnapshotParsing.uri(baseUri,
                    "/fapi/v1/ticker/bookTicker?symbol=" + XvfSnapshotParsing.encode(symbol)));
            JsonNode body = XvfSnapshotParsing.requireObject(response.body(), "binance bookTicker");
            ResponseTiming timing = XvfSnapshotParsing.timing(response,
                    XvfSnapshotParsing.optionalEpochMillis(body, "time"));
            builder.topOfBook = Optional.of(new TopOfBookSnapshot(
                    XvfSnapshotParsing.decimal(body, "bidPrice", "binance bookTicker"),
                    XvfSnapshotParsing.decimal(body, "bidQty", "binance bookTicker"),
                    XvfSnapshotParsing.decimal(body, "askPrice", "binance bookTicker"),
                    XvfSnapshotParsing.decimal(body, "askQty", "binance bookTicker"), timing));
        } catch (RuntimeException e) {
            builder.missing.add("topOfBook");
            issues.add(issue(symbol, "BOOK_TICKER_UNAVAILABLE", e));
        }
    }

    private void fetchDepth(String symbol, XvfInstrumentSnapshotBuilder builder,
                            List<SnapshotIssue> issues) {
        try {
            TimedJson response = transport.get(XvfSnapshotParsing.uri(baseUri,
                    "/fapi/v1/depth?symbol=" + XvfSnapshotParsing.encode(symbol) + "&limit=50"));
            JsonNode body = XvfSnapshotParsing.requireObject(response.body(), "binance depth");
            Optional<Instant> sourceAt = XvfSnapshotParsing.optionalEpochMillis(body, "T")
                    .or(() -> XvfSnapshotParsing.optionalEpochMillis(body, "E"));
            builder.orderBook = Optional.of(new OrderBookSnapshot(
                    XvfSnapshotParsing.priceLevels(body.get("bids"), "binance bids", false),
                    XvfSnapshotParsing.priceLevels(body.get("asks"), "binance asks", false),
                    XvfSnapshotParsing.timing(response, sourceAt)));
        } catch (RuntimeException e) {
            builder.missing.add("orderBook");
            issues.add(issue(symbol, "DEPTH_UNAVAILABLE", e));
        }
    }

    static Map<String, ReferenceSnapshot> references(
            TimedJson response, Map<String, Integer> adjustedFundingIntervals) {
        JsonNode rows = XvfSnapshotParsing.requireArray(response.body(), "binance premiumIndex");
        Map<String, ReferenceSnapshot> out = new HashMap<>();
        for (JsonNode row : rows) {
            XvfSnapshotParsing.requireObject(row, "binance premiumIndex row");
            String symbol = XvfSnapshotParsing.text(row, "symbol", "binance premiumIndex row");
            ResponseTiming timing = XvfSnapshotParsing.timing(response,
                    XvfSnapshotParsing.optionalEpochMillis(row, "time"));
            out.put(symbol, new ReferenceSnapshot(
                    XvfSnapshotParsing.optionalDecimal(row, "markPrice"),
                    XvfSnapshotParsing.optionalDecimal(row, "indexPrice"),
                    Optional.empty(),
                    XvfSnapshotParsing.optionalDecimal(row, "lastFundingRate"),
                    XvfSnapshotParsing.optionalEpochMillis(row, "nextFundingTime"),
                    Optional.of(adjustedFundingIntervals.getOrDefault(
                            symbol, STANDARD_FUNDING_INTERVAL_HOURS)),
                    Optional.empty(),
                    timing));
        }
        return Map.copyOf(out);
    }

    static Map<String, Integer> fundingIntervals(TimedJson response) {
        JsonNode rows = XvfSnapshotParsing.requireArray(response.body(), "binance fundingInfo");
        Map<String, Integer> out = new HashMap<>();
        for (JsonNode row : rows) {
            XvfSnapshotParsing.requireObject(row, "binance fundingInfo row");
            String symbol = XvfSnapshotParsing.text(row, "symbol", "binance fundingInfo row");
            int interval = XvfSnapshotParsing.optionalPositiveInteger(row, "fundingIntervalHours")
                    .orElseThrow(() -> XvfSnapshotParsing.schema(
                            "binance fundingInfo row.fundingIntervalHours must be present"));
            out.put(symbol, interval);
        }
        return Map.copyOf(out);
    }

    static Map<String, ActivitySnapshot> activities(TimedJson response) {
        JsonNode rows = XvfSnapshotParsing.requireArray(response.body(), "binance ticker/24hr");
        Map<String, ActivitySnapshot> out = new HashMap<>();
        for (JsonNode row : rows) {
            XvfSnapshotParsing.requireObject(row, "binance ticker/24hr row");
            String symbol = XvfSnapshotParsing.text(row, "symbol", "binance ticker/24hr row");
            out.put(symbol, new ActivitySnapshot(
                    XvfSnapshotParsing.optionalDecimal(row, "quoteVolume"),
                    XvfSnapshotParsing.timing(response,
                            XvfSnapshotParsing.optionalEpochMillis(row, "closeTime"))));
        }
        return Map.copyOf(out);
    }

    static Map<String, InstrumentRules> rules(TimedJson response) {
        JsonNode root = XvfSnapshotParsing.requireObject(response.body(), "binance exchangeInfo");
        JsonNode rows = XvfSnapshotParsing.requireArray(root.get("symbols"),
                "binance exchangeInfo.symbols");
        ResponseTiming timing = XvfSnapshotParsing.timing(response,
                XvfSnapshotParsing.optionalEpochMillis(root, "serverTime"));
        Map<String, InstrumentRules> out = new HashMap<>();
        for (JsonNode row : rows) {
            XvfSnapshotParsing.requireObject(row, "binance exchangeInfo symbol");
            String symbol = XvfSnapshotParsing.text(row, "symbol", "binance exchangeInfo symbol");
            Map<String, JsonNode> filters = new HashMap<>();
            for (JsonNode filter : XvfSnapshotParsing.requireArray(row.get("filters"),
                    "binance exchangeInfo filters")) {
                filters.put(XvfSnapshotParsing.text(filter, "filterType", "binance filter"), filter);
            }
            JsonNode lot = filters.get("LOT_SIZE");
            JsonNode price = filters.get("PRICE_FILTER");
            JsonNode notional = filters.get("MIN_NOTIONAL");
            boolean trading = "TRADING".equals(row.path("status").asText())
                    && "PERPETUAL".equals(row.path("contractType").asText("PERPETUAL"));
            out.put(symbol, new InstrumentRules(
                    XvfSnapshotParsing.optionalDecimal(price, "tickSize"),
                    XvfSnapshotParsing.optionalDecimal(lot, "stepSize"),
                    XvfSnapshotParsing.optionalDecimal(lot, "minQty"),
                    XvfSnapshotParsing.optionalDecimal(notional, "notional"),
                    XvfSnapshotParsing.optionalDecimal(lot, "maxQty"),
                    Optional.empty(), trading, timing));
        }
        return Map.copyOf(out);
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

    private static SnapshotIssue issue(String symbol, String code, RuntimeException error) {
        return new SnapshotIssue(IssueSeverity.ERROR, VENUE, Optional.of(symbol), code,
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
    }
}
