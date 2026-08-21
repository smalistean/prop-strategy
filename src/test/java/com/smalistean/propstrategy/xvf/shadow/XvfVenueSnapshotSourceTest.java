package com.smalistean.propstrategy.xvf.shadow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.xvf.shadow.XvfPublicJsonTransport.TimedJson;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.VenueSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XvfVenueSnapshotSourceTest {

    private static final URI BASE = URI.create("https://fixture.test");

    @Test
    void binancePreservesExactDecimalsBundleMultiplierAndResponseProvenance() {
        FixtureTransport transport = new FixtureTransport()
                .get("/fapi/v1/premiumIndex", """
                        [{"symbol":"1000PEPEUSDT","markPrice":"0.006123456789",
                          "indexPrice":"0.006120000001","lastFundingRate":"0.00010001",
                          "nextFundingTime":1787308800000,"time":1787306400123}]
                        """)
                .get("/fapi/v1/ticker/24hr", """
                        [{"symbol":"1000PEPEUSDT","quoteVolume":"987654321.123456789",
                          "closeTime":1787306400100}]
                        """)
                .get("/fapi/v1/exchangeInfo", """
                        {"serverTime":1787306400000,"symbols":[{
                          "symbol":"1000PEPEUSDT","status":"TRADING","contractType":"PERPETUAL",
                          "filters":[
                            {"filterType":"PRICE_FILTER","tickSize":"0.0000001"},
                            {"filterType":"LOT_SIZE","minQty":"1","maxQty":"9000000","stepSize":"1"},
                            {"filterType":"MIN_NOTIONAL","notional":"5"}
                          ]}]}
                        """)
                .get("/fapi/v1/fundingInfo", """
                        [{"symbol":"1000PEPEUSDT","adjustedFundingRateCap":"0.02000000",
                          "adjustedFundingRateFloor":"-0.02000000","fundingIntervalHours":4}]
                        """)
                .get("/fapi/v1/ticker/bookTicker?symbol=1000PEPEUSDT", """
                        {"symbol":"1000PEPEUSDT","bidPrice":"0.0061234","bidQty":"123456",
                         "askPrice":"0.0061235","askQty":"654321","time":1787306400200}
                        """)
                .get("/fapi/v1/depth?symbol=1000PEPEUSDT&limit=50", """
                        {"lastUpdateId":77,"E":1787306400290,"T":1787306400288,
                         "bids":[["0.0061234","123456"],["0.0061233","234567"]],
                         "asks":[["0.0061235","654321"],["0.0061236","765432"]]}
                        """);

        VenueSnapshot snapshot = new BinanceXvfVenueSnapshotSource(transport, BASE)
                .fetch(Set.of("1000PEPEUSDT"));

        InstrumentSnapshot instrument = snapshot.instruments().get("1000PEPEUSDT");
        assertEquals("PEPE", instrument.canonicalBase());
        assertEquals(new BigDecimal("1000"), instrument.baseUnitsPerContract());
        assertEquals(new BigDecimal("0.006123456789"),
                instrument.reference().orElseThrow().markPrice().orElseThrow());
        assertEquals(new BigDecimal("987654321.123456789"),
                instrument.activity().orElseThrow().quoteVolume24hUsd().orElseThrow());
        assertEquals(Instant.ofEpochMilli(1787306400123L),
                instrument.reference().orElseThrow().timing().sourceAt().orElseThrow());
        assertEquals(new BigDecimal("123456"),
                instrument.topOfBook().orElseThrow().bidQuantity());
        assertEquals(2, instrument.orderBook().orElseThrow().bids().size());
        assertEquals(4,
                instrument.reference().orElseThrow().fundingIntervalHours().orElseThrow());
        assertTrue(instrument.missingData().isEmpty());
        assertTrue(snapshot.issues().isEmpty());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.instruments().put("X", instrument));
        transport.assertExhausted();
    }

    @Test
    void binanceDefaultsSymbolsAbsentFromFundingInfoToStandardEightHours() {
        FixtureTransport transport = new FixtureTransport();
        TimedJson premium = transport.json("""
                [{"symbol":"BTCUSDT","markPrice":"65000","indexPrice":"64999",
                  "lastFundingRate":"0.0001","nextFundingTime":1787308800000,
                  "time":1787306400123}]
                """);
        TimedJson fundingInfo = transport.json("[]");

        var references = BinanceXvfVenueSnapshotSource.references(
                premium, BinanceXvfVenueSnapshotSource.fundingIntervals(fundingInfo));

        assertEquals(8, references.get("BTCUSDT").fundingIntervalHours().orElseThrow());
    }

    @Test
    void bybitPaginatesRulesAndKeepsTickerSizesAndDepth() {
        FixtureTransport transport = new FixtureTransport()
                .get("/v5/market/tickers?category=linear", """
                        {"retCode":0,"retMsg":"OK","time":1787306401000,"result":{"list":[{
                          "symbol":"BTCUSDT","bid1Price":"65000.10","bid1Size":"1.23456789",
                          "ask1Price":"65000.20","ask1Size":"2.34567891","markPrice":"65000.15",
                          "indexPrice":"65000.12","fundingRate":"-0.00001234",
                          "nextFundingTime":"1787308800000","fundingIntervalHour":"8",
                          "turnover24h":"123456789.123456","openInterest":"9876.54321"}]}}
                        """)
                .get("/v5/market/instruments-info?category=linear&limit=1000", """
                        {"retCode":0,"retMsg":"OK","time":1787306400900,"result":{
                          "nextPageCursor":"next/page+1","list":[{
                            "symbol":"ETHUSDT","status":"Trading","contractType":"LinearPerpetual",
                            "symbolType":"","priceFilter":{"tickSize":"0.01"},
                            "lotSizeFilter":{"qtyStep":"0.001","minOrderQty":"0.001",
                              "minNotionalValue":"5","maxOrderQty":"10000"},
                            "leverageFilter":{"maxLeverage":"100"}}]}}
                        """)
                .get("/v5/market/instruments-info?category=linear&limit=1000&cursor=next%2Fpage%2B1", """
                        {"retCode":0,"retMsg":"OK","time":1787306400950,"result":{
                          "nextPageCursor":"","list":[{
                            "symbol":"BTCUSDT","status":"Trading","contractType":"LinearPerpetual",
                            "symbolType":"","priceFilter":{"tickSize":"0.10"},
                            "lotSizeFilter":{"qtyStep":"0.001","minOrderQty":"0.001",
                              "minNotionalValue":"5","maxOrderQty":"1190.000"},
                            "leverageFilter":{"maxLeverage":"100"}}]}}
                        """)
                .get("/v5/market/orderbook?category=linear&symbol=BTCUSDT&limit=50", """
                        {"retCode":0,"retMsg":"OK","time":1787306401100,"result":{
                          "s":"BTCUSDT","ts":1787306401090,"cts":1787306401088,
                          "b":[["65000.10","1.23456789"],["65000.00","3.00"]],
                          "a":[["65000.20","2.34567891"],["65000.30","4.00"]]}}
                        """);

        VenueSnapshot snapshot = new BybitXvfVenueSnapshotSource(transport, BASE)
                .fetch(Set.of("BTCUSDT"));

        InstrumentSnapshot instrument = snapshot.instruments().get("BTCUSDT");
        assertEquals("BTC", instrument.canonicalBase());
        assertEquals(BigDecimal.ONE, instrument.baseUnitsPerContract());
        assertEquals(new BigDecimal("1.23456789"),
                instrument.topOfBook().orElseThrow().bidQuantity());
        assertEquals(new BigDecimal("0.001"),
                instrument.rules().orElseThrow().quantityStep().orElseThrow());
        assertEquals(100, instrument.rules().orElseThrow().maximumLeverage().orElseThrow());
        assertEquals(8, instrument.reference().orElseThrow().fundingIntervalHours().orElseThrow());
        assertEquals(Instant.ofEpochMilli(1787306401088L),
                instrument.orderBook().orElseThrow().timing().sourceAt().orElseThrow());
        assertTrue(instrument.missingData().isEmpty());
        assertTrue(snapshot.issues().isEmpty());
        transport.assertExhausted();
    }

    @Test
    void hyperliquidMapsOracleAsIndexAndMakesUnpublishedFieldsExplicit() {
        FixtureTransport transport = new FixtureTransport()
                .post("/info", "{\"type\":\"metaAndAssetCtxs\"}", """
                        [{"universe":[{"name":"kPEPE","szDecimals":0,"maxLeverage":3}]},
                         [{"markPx":"0.00612345","oraclePx":"0.00612001","midPx":"0.00612340",
                           "funding":"-0.0000125","openInterest":"1234567.89",
                           "dayNtlVlm":"99887766.554433"}]]
                        """)
                .post("/info", "{\"type\":\"l2Book\",\"coin\":\"kPEPE\"}", """
                        {"coin":"kPEPE","time":1787306402200,"levels":[
                          [{"px":"0.0061234","sz":"100000","n":12},
                           {"px":"0.0061233","sz":"200000","n":8}],
                          [{"px":"0.0061235","sz":"110000","n":9},
                           {"px":"0.0061236","sz":"210000","n":7}]]}
                        """);

        VenueSnapshot snapshot = new HyperliquidXvfVenueSnapshotSource(transport, BASE)
                .fetch(Set.of("kPEPE"));

        InstrumentSnapshot instrument = snapshot.instruments().get("kPEPE");
        assertEquals("PEPE", instrument.canonicalBase());
        assertEquals(new BigDecimal("1000"), instrument.baseUnitsPerContract());
        assertEquals(new BigDecimal("0.00612001"),
                instrument.reference().orElseThrow().indexPrice().orElseThrow());
        assertEquals(new BigDecimal("-0.0000125"),
                instrument.reference().orElseThrow().pendingFundingRate().orElseThrow());
        assertEquals(1, instrument.reference().orElseThrow().fundingIntervalHours().orElseThrow());
        assertTrue(instrument.reference().orElseThrow().nextFundingTime().isEmpty());
        assertEquals(12, instrument.orderBook().orElseThrow().bids().getFirst()
                .orderCount().orElseThrow());
        assertEquals(List.of("reference.nextFundingTimeNotPublished",
                "rules.fixedTickSizeNotPublished"), instrument.missingData());
        assertTrue(snapshot.issues().isEmpty());
        transport.assertExhausted();
    }

    @Test
    void venueSchemaErrorsAreNotSilentlyDefaulted() {
        FixtureTransport transport = new FixtureTransport();
        TimedJson bybitError = transport.json("""
                {"retCode":10001,"retMsg":"bad request","result":{}}
                """);
        IllegalStateException bybit = assertThrows(IllegalStateException.class,
                () -> BybitXvfVenueSnapshotSource.tickers(bybitError));
        assertTrue(bybit.getMessage().contains("retCode 10001"));

        TimedJson hyperliquidMismatch = transport.json("""
                [{"universe":[{"name":"BTC","szDecimals":5}]},[]]
                """);
        IllegalStateException hyperliquid = assertThrows(IllegalStateException.class,
                () -> HyperliquidXvfVenueSnapshotSource.metadata(hyperliquidMismatch));
        assertTrue(hyperliquid.getMessage().contains("lengths differ"));
    }

    @Test
    void aPerSymbolFailureIsCapturedWithoutInventingAZeroBook() {
        FixtureTransport transport = new FixtureTransport()
                .get("/fapi/v1/premiumIndex", """
                        [{"symbol":"BTCUSDT","markPrice":"65000","indexPrice":"64999",
                          "lastFundingRate":"0.0001","nextFundingTime":1787308800000,
                          "time":1787306400123}]
                        """)
                .get("/fapi/v1/ticker/24hr", """
                        [{"symbol":"BTCUSDT","quoteVolume":"1000000","closeTime":1787306400100}]
                        """)
                .get("/fapi/v1/exchangeInfo", """
                        {"serverTime":1787306400000,"symbols":[{"symbol":"BTCUSDT",
                          "status":"TRADING","contractType":"PERPETUAL","filters":[
                          {"filterType":"PRICE_FILTER","tickSize":"0.1"},
                          {"filterType":"LOT_SIZE","minQty":"0.001","maxQty":"100","stepSize":"0.001"},
                          {"filterType":"MIN_NOTIONAL","notional":"5"}]}]}
                        """)
                .get("/fapi/v1/fundingInfo", "[]")
                .getFailure("/fapi/v1/ticker/bookTicker?symbol=BTCUSDT", "book timed out")
                .get("/fapi/v1/depth?symbol=BTCUSDT&limit=50", """
                        {"T":1787306400200,"bids":[["65000","1"]],"asks":[["65001","1"]]}
                        """);

        VenueSnapshot snapshot = new BinanceXvfVenueSnapshotSource(transport, BASE)
                .fetch(Set.of("BTCUSDT"));

        InstrumentSnapshot instrument = snapshot.instruments().get("BTCUSDT");
        assertTrue(instrument.topOfBook().isEmpty());
        assertFalse(instrument.orderBook().isEmpty());
        assertTrue(instrument.missingData().contains("topOfBook"));
        assertEquals("BOOK_TICKER_UNAVAILABLE", snapshot.issues().getFirst().code());
        transport.assertExhausted();
    }

    @Test
    void bundleMultiplierMatchesEverySupportedPrefixConvention() {
        assertEquals(new BigDecimal("1000000"),
                XvfSnapshotParsing.baseUnitsPerContract("binance", "1000000MOGUSDT"));
        assertEquals(new BigDecimal("1000000"),
                XvfSnapshotParsing.baseUnitsPerContract("bybit", "1MBABYDOGEUSDT"));
        assertEquals(new BigDecimal("1000"),
                XvfSnapshotParsing.baseUnitsPerContract("hyperliquid", "kPEPE"));
        assertEquals(BigDecimal.ONE,
                XvfSnapshotParsing.baseUnitsPerContract("binance", "BTCUSDT"));
    }

    private static final class FixtureTransport implements XvfPublicJsonTransport {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final Instant REQUESTED = Instant.parse("2026-08-21T12:00:00.000001Z");
        private static final Instant RECEIVED = Instant.parse("2026-08-21T12:00:00.123456Z");

        private final Map<String, Deque<Object>> responses = new HashMap<>();
        private final List<String> calls = new ArrayList<>();

        FixtureTransport get(String pathAndQuery, String json) {
            add("GET " + BASE + pathAndQuery, json);
            return this;
        }

        FixtureTransport getFailure(String pathAndQuery, String message) {
            add("GET " + BASE + pathAndQuery, new IllegalStateException(message));
            return this;
        }

        FixtureTransport post(String path, String body, String json) {
            add("POST " + BASE + path + " " + body, json);
            return this;
        }

        @Override
        public TimedJson get(URI uri) {
            return take("GET " + uri);
        }

        @Override
        public TimedJson post(URI uri, String jsonBody) {
            return take("POST " + uri + " " + jsonBody);
        }

        TimedJson json(String json) {
            return new TimedJson(parse(json), REQUESTED, RECEIVED);
        }

        void assertExhausted() {
            assertTrue(responses.values().stream().allMatch(Deque::isEmpty),
                    () -> "Unused fixtures after calls " + calls + ": " + responses);
        }

        private void add(String key, Object response) {
            responses.computeIfAbsent(key, ignored -> new ArrayDeque<>()).add(response);
        }

        private TimedJson take(String key) {
            calls.add(key);
            Deque<Object> queue = responses.get(key);
            if (queue == null || queue.isEmpty()) {
                throw new IllegalStateException("No fixture for " + key + "; calls=" + calls);
            }
            Object response = queue.removeFirst();
            if (response instanceof RuntimeException failure) {
                throw failure;
            }
            return json((String) response);
        }

        private static JsonNode parse(String json) {
            try {
                return MAPPER.readTree(json);
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Bad test fixture", e);
            }
        }
    }
}
