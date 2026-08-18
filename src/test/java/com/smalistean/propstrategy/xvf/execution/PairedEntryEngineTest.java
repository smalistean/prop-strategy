package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        Consumer<OrderUpdate> listener;

        RecordingGateway(String name) {
            this.name = name;
        }

        @Override public String name() {
            return name;
        }
        @Override public OrderHandle placePostOnly(String s, Side side, BigDecimal q, BigDecimal p) {
            return new OrderHandle(name, s, "V1", "C1");
        }
        @Override public OrderHandle placeMarket(String s, Side side, BigDecimal q) {
            marketOrders.add(q);
            return new OrderHandle(name, s, "V2", "C2");
        }
        @Override public void cancel(OrderHandle handle) {
        }
        @Override public AutoCloseable streamOrderUpdates(Consumer<OrderUpdate> l) {
            this.listener = l;
            return () -> { };
        }
        @Override public SymbolRules rules(String venueSymbol) {
            return new SymbolRules(new BigDecimal("0.001"), new BigDecimal("5"), new BigDecimal("0.01"));
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
}
