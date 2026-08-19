package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderHandle;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderSnapshot;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitOutcome;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitResult;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.TopOfBook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The over-hedge regression.
 *
 * <p>Venue order updates carry a CUMULATIVE filled quantity. Hedging that figure on every partial
 * fill sends 1x, then 2x, then 3x of a three-partial order — 2Q against a maker fill of Q, leaving a
 * naked short of Q. These tests pin the increment behaviour so it cannot come back.
 */
class PairedEntryEngineTest {

    /** Records what was sent to the taker venue, and reports fills back on demand. */
    private static final class RecordingGateway implements VenueGateway {
        private final String name;
        final List<BigDecimal> marketOrders = new ArrayList<>();
        final List<BigDecimal> caps = new ArrayList<>();
        final List<Boolean> makerReduceOnly = new ArrayList<>();
        final List<Boolean> takerReduceOnly = new ArrayList<>();
        Consumer<OrderUpdate> listener;

        RecordingGateway(String name) {
            this.name = name;
        }

        @Override public String name() {
            return name;
        }

        @Override public java.util.List<PositionSnapshot> positions() {
            return java.util.List.of();   // these tests assert on orders sent, not on venue state
        }
        SubmitOutcome nextOutcome = SubmitOutcome.ACCEPTED;
        OrderSnapshot lookupAnswer;
        int lookups;

        @Override public SubmitResult placePostOnly(String s, Side side, BigDecimal q, BigDecimal p,
                                                    String clientOrderId, boolean reduceOnly) {
            makerReduceOnly.add(reduceOnly);
            return new SubmitResult(nextOutcome, new OrderHandle(name, s, "V1", clientOrderId), "test");
        }
        @Override public SubmitResult placeCappedIoc(String s, Side side, BigDecimal q,
                                                     BigDecimal worst, String clientOrderId,
                                                     boolean reduceOnly) {
            marketOrders.add(q);
            caps.add(worst);
            takerReduceOnly.add(reduceOnly);
            return new SubmitResult(nextOutcome, new OrderHandle(name, s, "V2", clientOrderId), "test");
        }
        @Override public java.util.Optional<OrderSnapshot> orderByClientId(String s, String c) {
            lookups++;
            return java.util.Optional.ofNullable(lookupAnswer);
        }
        @Override public TopOfBook topOfBook(String venueSymbol) {
            return new TopOfBook(new BigDecimal("99"), new BigDecimal("101"), 0L);
        }
        @Override public void cancel(OrderHandle handle) {
        }
        @Override public AutoCloseable streamOrderUpdates(Consumer<OrderUpdate> l) {
            this.listener = l;
            return () -> { };
        }
        BigDecimal step = new BigDecimal("0.001");
        @Override public SymbolRules rules(String venueSymbol) {
            return new SymbolRules(step, new BigDecimal("5"), new BigDecimal("0.01"));
        }
    }

    private static VenueGateway.OrderUpdate cumulative(String clientId, String filledSoFar,
                                                      VenueGateway.OrderState state) {
        return new VenueGateway.OrderUpdate("maker", "XUSDT", clientId, state,
                new BigDecimal(filledSoFar), new BigDecimal("100"), 0L);
    }

    /** Drives the engine and returns the client order ID it generated. */
    private static String openOne(PairedEntryEngine engine, RecordingGateway maker,
                                  RecordingGateway taker) throws Exception {
        engine.open("X",
                new PairedEntryEngine.Leg(maker, "XUSDT", VenueGateway.Side.SELL, new BigDecimal("3")),
                new PairedEntryEngine.Leg(taker, "XUSDT", VenueGateway.Side.BUY, new BigDecimal("3")),
                new BigDecimal("100"));
        // The engine keys pairs by a generated client ID; recover it through the only handle we have.
        java.lang.reflect.Field field = PairedEntryEngine.class.getDeclaredField("byClientId");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, ?>) field.get(engine);
        return map.keySet().iterator().next();
    }

    @Test
    void openingIsNotReduceOnlyButClosingIsOnBothLegs() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            engine.open("X",
                    new PairedEntryEngine.Leg(maker, "XUSDT", VenueGateway.Side.SELL, new BigDecimal("3")),
                    new PairedEntryEngine.Leg(taker, "XUSDT", VenueGateway.Side.BUY, new BigDecimal("3")),
                    new BigDecimal("100"));
            assertEquals(List.of(false), maker.makerReduceOnly,
                    "an opening maker must NOT be reduce-only or it can never open anything");

            // Closing is the mirror: opposite sides, and reduce-only on both. Without it, an order
            // arriving after its leg has gone flat opens a fresh position the other way - a naked leg
            // created by the code meant to remove one.
            engine.close("Y",
                    new PairedEntryEngine.Leg(maker, "YUSDT", VenueGateway.Side.BUY, new BigDecimal("3")),
                    new PairedEntryEngine.Leg(taker, "YUSDT", VenueGateway.Side.SELL, new BigDecimal("3")),
                    new BigDecimal("100"));
            assertEquals(List.of(false, true), maker.makerReduceOnly,
                    "a closing maker must be reduce-only");

            String closingId = null;
            java.lang.reflect.Field field = PairedEntryEngine.class.getDeclaredField("byClientId");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, ?>) field.get(engine);
            for (String id : map.keySet()) {
                if (id.startsWith("xvfx-")) {
                    closingId = id;
                }
            }
            assertNotNull(closingId, "the closing pair should be registered under its own prefix");

            engine.onOrderUpdate(new VenueGateway.OrderUpdate("maker", "YUSDT", closingId,
                    VenueGateway.OrderState.FILLED, new BigDecimal("3"), new BigDecimal("100"), 0L));
            Thread.sleep(150);
            assertEquals(List.of(true), taker.takerReduceOnly,
                    "the hedge that offsets a closing fill must also be reduce-only");
        }
    }

    @Test
    void hedgesOnlyTheIncrementAcrossThreePartialFills() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            String id = openOne(engine, maker, taker);

            // Cumulative, as a venue reports it: 1, then 2, then 3 of a 3-unit order.
            engine.onOrderUpdate(cumulative(id, "1", VenueGateway.OrderState.PARTIALLY_FILLED));
            engine.onOrderUpdate(cumulative(id, "2", VenueGateway.OrderState.PARTIALLY_FILLED));
            engine.onOrderUpdate(cumulative(id, "3", VenueGateway.OrderState.FILLED));

            assertEquals(List.of(new BigDecimal("1.000"), new BigDecimal("1.000"),
                            new BigDecimal("1.000")), taker.marketOrders,
                    "each update must hedge only what is newly filled");
            BigDecimal total = taker.marketOrders.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, total.compareTo(new BigDecimal("3")),
                    "total hedged must equal the maker fill, not 2x it");
        }
    }

    @Test
    void ignoresDuplicateAndOutOfOrderUpdates() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            String id = openOne(engine, maker, taker);

            engine.onOrderUpdate(cumulative(id, "2", VenueGateway.OrderState.PARTIALLY_FILLED));
            engine.onOrderUpdate(cumulative(id, "2", VenueGateway.OrderState.PARTIALLY_FILLED));
            engine.onOrderUpdate(cumulative(id, "1", VenueGateway.OrderState.PARTIALLY_FILLED));

            BigDecimal total = taker.marketOrders.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, total.compareTo(new BigDecimal("2")),
                    "a repeated or stale cumulative figure exposes nothing new");
        }
    }

    @Test
    void aDroppedUpdateIsRecoveredByTheNextOne() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            String id = openOne(engine, maker, taker);

            // The "2" update never arrives. Differencing a cumulative total self-corrects, where
            // summing per-event increments would have been permanently short.
            engine.onOrderUpdate(cumulative(id, "1", VenueGateway.OrderState.PARTIALLY_FILLED));
            engine.onOrderUpdate(cumulative(id, "3", VenueGateway.OrderState.FILLED));

            BigDecimal total = taker.marketOrders.stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, total.compareTo(new BigDecimal("3")),
                    "cumulative differencing must recover the missed fill");
            assertTrue(engine.outstanding().isEmpty(), "the pair should end fully hedged");
        }
    }

    @Test
    void cappedIocPriceIsBoundedByTheTouch() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            String id = openOne(engine, maker, taker);
            engine.onOrderUpdate(cumulative(id, "3", VenueGateway.OrderState.FILLED));

            // Taker side is BUY, so the cap sits above the ask of 101 but within the configured
            // 25bp allowance - never an unbounded market order.
            assertEquals(1, taker.caps.size());
            BigDecimal cap = taker.caps.get(0);
            assertTrue(cap.compareTo(new BigDecimal("101")) > 0, "cap must clear the ask");
            assertTrue(cap.compareTo(new BigDecimal("101.3")) < 0, "cap must stay inside 25bp");
        }
    }

    @Test
    void unknownSubmissionIsResolvedByClientIdRatherThanRetried() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        maker.nextOutcome = SubmitOutcome.UNKNOWN;
        // The venue did accept it; the network merely failed to say so.
        maker.lookupAnswer = new OrderSnapshot(new OrderHandle("maker", "XUSDT", "V9", "C9"),
                VenueGateway.OrderState.RESTING, BigDecimal.ZERO, BigDecimal.ZERO);
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            openOne(engine, maker, taker);
            assertEquals(1, maker.lookups, "an ambiguous submission must be queried, not re-sent");
        }
    }

    @Test
    void unknownSubmissionTheVenueNeverSawCountsAsRejected() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        maker.nextOutcome = SubmitOutcome.UNKNOWN;
        maker.lookupAnswer = null;   // venue has no record: the order never landed
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            engine.open("X",
                    new PairedEntryEngine.Leg(maker, "XUSDT", VenueGateway.Side.SELL, new BigDecimal("3")),
                    new PairedEntryEngine.Leg(taker, "XUSDT", VenueGateway.Side.BUY, new BigDecimal("3")),
                    new BigDecimal("100"));

            // Deregistered entirely: no order exists, so a stray update carrying that ID must find
            // nothing to hedge against. The abandon TIMER path deliberately does the opposite,
            // because there a cancel can race a real fill.
            java.lang.reflect.Field field = PairedEntryEngine.class.getDeclaredField("byClientId");
            field.setAccessible(true);
            assertTrue(((java.util.Map<?, ?>) field.get(engine)).isEmpty(),
                    "a maker the venue never saw must leave no pair registered");

            engine.onOrderUpdate(cumulative("xvf-X-doesnotexist", "3", VenueGateway.OrderState.FILLED));
            assertTrue(taker.marketOrders.isEmpty(),
                    "a maker that never landed must not produce a hedge");
        }
    }

    @Test
    void hedgeConvertsMakerUnitsIntoTakerUnits() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");   // e.g. 1000PEPE contracts
        RecordingGateway taker = new RecordingGateway("taker");   // e.g. PEPE contracts
        taker.step = new BigDecimal("1");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            // Same USD notional, 1000x different contract units - the shape of 1000PEPE vs PEPE.
            engine.open("PEPE",
                    new PairedEntryEngine.Leg(maker, "1000PEPEUSDT", VenueGateway.Side.SELL,
                            new BigDecimal("50")),
                    new PairedEntryEngine.Leg(taker, "PEPE", VenueGateway.Side.BUY,
                            new BigDecimal("50000")),
                    new BigDecimal("5"));

            java.lang.reflect.Field field = PairedEntryEngine.class.getDeclaredField("byClientId");
            field.setAccessible(true);
            String id = ((java.util.Map<String, ?>) field.get(engine)).keySet().iterator().next();

            engine.onOrderUpdate(cumulative(id, "50", VenueGateway.OrderState.FILLED));

            assertEquals(1, taker.marketOrders.size());
            assertEquals(0, taker.marketOrders.get(0).compareTo(new BigDecimal("50000")),
                    "a 50-contract maker fill must hedge 50,000 taker units, not 50");
        }
    }

    @Test
    void equalContractUnitsStillHedgeOneForOne() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            String id = openOne(engine, maker, taker);   // both legs sized 3
            engine.onOrderUpdate(cumulative(id, "3", VenueGateway.OrderState.FILLED));
            assertEquals(0, taker.marketOrders.get(0).compareTo(new BigDecimal("3")),
                    "matched units must be unaffected by the conversion");
        }
    }
}
