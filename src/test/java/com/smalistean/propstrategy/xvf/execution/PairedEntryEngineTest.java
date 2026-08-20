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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        final List<String> makerClientIds = new ArrayList<>();
        int cancels;
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
        @Override public void setLeverage(String venueSymbol, int leverage) {
        }
        SubmitOutcome nextOutcome = SubmitOutcome.ACCEPTED;
        OrderSnapshot lookupAnswer;
        int lookups;

        @Override public SubmitResult placePostOnly(String s, Side side, BigDecimal q, BigDecimal p,
                                                    String clientOrderId, boolean reduceOnly) {
            makerReduceOnly.add(reduceOnly);
            makerClientIds.add(clientOrderId);
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
            cancels++;
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

    /**
     * BigDecimal.equals() is scale-sensitive - 2 and 2.000 are NOT equal by it, only by
     * compareTo() - so asserting a list of quantities against literals via plain equals() couples
     * the test to whatever scale the engine's rounding happens to produce, which is exactly the
     * kind of detail a quantity test should not care about.
     */
    private static void assertQuantities(List<String> expected, List<BigDecimal> actual) {
        List<BigDecimal> parsed = expected.stream().map(BigDecimal::new).toList();
        assertEquals(parsed.size(), actual.size(),
                () -> "expected " + parsed + " but got " + actual);
        for (int i = 0; i < parsed.size(); i++) {
            String message = "at index %d: expected %s but got %s".formatted(i, parsed, actual);
            assertEquals(0, parsed.get(i).compareTo(actual.get(i)), message);
        }
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
    void aStaleMakerIsCancelledAndRePricedRatherThanLeftRestingForever() throws Exception {
        // The complaint this exists for: a maker that never fills can wait out the whole
        // abandonAfter window on a price the book has already moved past. Verified live 2026-08-19
        // - a maker resting untouched for over three minutes. A short chaseEvery here stands in for
        // that long real window.
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine =
                     new PairedEntryEngine(Duration.ofMinutes(30), Duration.ofMillis(50))) {
            engine.open("X",
                    new PairedEntryEngine.Leg(maker, "XUSDT", VenueGateway.Side.SELL, new BigDecimal("3")),
                    new PairedEntryEngine.Leg(taker, "XUSDT", VenueGateway.Side.BUY, new BigDecimal("3")),
                    new BigDecimal("100"));
            assertEquals(1, maker.makerClientIds.size(), "one order placed initially");

            Thread.sleep(200);   // several chase intervals, nothing ever fills

            assertTrue(maker.makerClientIds.size() > 1,
                    "an unfilled maker must be cancelled and re-placed rather than left resting");
            assertTrue(maker.cancels > 0, "the stale order must actually be cancelled before replacing it");
            // Every re-placement is a NEW order, and every one of them must carry the same
            // reduce-only flag as the first - a chase must not accidentally turn an opening order
            // into one that can no longer open anything, or vice versa for a close.
            assertTrue(maker.makerReduceOnly.stream().noneMatch(Boolean::booleanValue),
                    "every chase of an OPEN must stay non-reduce-only");
        }
    }

    @Test
    void chasingStopsAsSoonAsThePairIsFullyFilled() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine =
                     new PairedEntryEngine(Duration.ofMinutes(30), Duration.ofMillis(50))) {
            String firstId = openOne(engine, maker, taker);
            engine.onOrderUpdate(cumulative(firstId, "3", VenueGateway.OrderState.FILLED));
            Thread.sleep(50);
            int ordersRightAfterFill = maker.makerClientIds.size();

            Thread.sleep(200);   // long enough for several chase intervals to have fired, if unfilled

            assertEquals(ordersRightAfterFill, maker.makerClientIds.size(),
                    "a fully filled pair must not be chased any further - there is nothing left to fill");
        }
    }

    @Test
    void aFillOnASecondOrderAfterAChaseIsNotLostAgainstTheFirstOrdersWatermark() throws Exception {
        // The bug a chase could reintroduce: order A's cumulative fill is 2 (of 3). Chasing cancels
        // A and places order B for the remaining 1. B is a BRAND NEW order on the venue - its own
        // cumulative starts at 0, not at 2. If the increment were still computed against a single
        // pair-wide watermark left at 2 by order A, B's update reporting "1" would read as LESS than
        // 2 and be dropped as a stale duplicate, permanently stranding that last unit unhedged.
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            String orderA = openOne(engine, maker, taker);
            engine.onOrderUpdate(cumulative(orderA, "2", VenueGateway.OrderState.PARTIALLY_FILLED));
            Thread.sleep(50);
            assertQuantities(List.of("2"), taker.marketOrders);

            // Simulate what a chase does: cancel A, place a fresh order B for the remaining 1.
            String orderB = "xvf-X-" + System.nanoTime();
            // Register B against the SAME pair by reaching into byClientId, mirroring what a chase's
            // placeMaker() call does internally.
            java.lang.reflect.Field byClientId = PairedEntryEngine.class.getDeclaredField("byClientId");
            byClientId.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.Map<String, Object>) byClientId.get(engine);
            map.put(orderB, map.get(orderA));

            // Order B fills its own full 1 unit. Its OWN cumulative is 1 - smaller than order A's
            // watermark of 2 - which is exactly the case a single shared watermark would drop.
            engine.onOrderUpdate(cumulative(orderB, "1", VenueGateway.OrderState.FILLED));
            Thread.sleep(50);

            assertQuantities(List.of("2", "1"), taker.marketOrders);
        }
    }

    @Test
    void hedgedIsNotTerminalUntilTheFullMakerQuantityHasFilled() throws Exception {
        // The premature-exit risk this pins: a caller waiting on allResolved() must not be told a
        // pair is done after its FIRST partial hedge succeeds while more of the same order is still
        // expected to fill. A live run hedged one position five separate times; if state had gone
        // HEDGED after the first, a caller could have stopped listening while four more were coming.
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            String orderId = openOne(engine, maker, taker);

            engine.onOrderUpdate(cumulative(orderId, "1", VenueGateway.OrderState.PARTIALLY_FILLED));
            Thread.sleep(50);
            assertEquals(1, engine.unresolvedCount(),
                    "one of three units filled - the pair must still count as unresolved");

            engine.onOrderUpdate(cumulative(orderId, "3", VenueGateway.OrderState.FILLED));
            Thread.sleep(50);
            assertEquals(0, engine.unresolvedCount(), "the full quantity is filled and hedged now");
        }
    }

    @Test
    void aRestingPairIsNotResolvedUntilItIsHedgedOrGivenUp() throws Exception {
        // The regression this exists for: the real entry point used to sleep 2 seconds and then
        // close every venue's websocket regardless of what was still resting. A maker fill arriving
        // after that point had nothing listening for it - up to 20 real positions could go naked
        // with the process already dead. allResolved()/unresolvedCount() are what a caller now waits
        // on instead of a fixed sleep.
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            String clientId = openOne(engine, maker, taker);

            assertFalse(engine.allResolved(), "a freshly-placed maker order is still resting");
            assertEquals(1, engine.unresolvedCount());

            // The fill arrives, and the hedge is accepted - the pair should now count as resolved,
            // which is the exact signal a caller waits for before it is safe to stop listening.
            engine.onOrderUpdate(cumulative(clientId, "3", VenueGateway.OrderState.FILLED));
            Thread.sleep(100);

            assertTrue(engine.allResolved(), "hedged means resolved - nothing further will happen on its own");
            assertEquals(0, engine.unresolvedCount());
        }
    }

    @Test
    void aRejectedMakerCountsAsResolvedImmediately() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        maker.nextOutcome = SubmitOutcome.REJECTED;
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            engine.open("X",
                    new PairedEntryEngine.Leg(maker, "XUSDT", VenueGateway.Side.SELL, new BigDecimal("3")),
                    new PairedEntryEngine.Leg(taker, "XUSDT", VenueGateway.Side.BUY, new BigDecimal("3")),
                    new BigDecimal("100"));

            assertTrue(engine.allResolved(), "a rejected maker opened nothing - there is nothing to wait for");
        }
    }

    @Test
    void openReturnsFalseOnRejectionSoTheCallerCanTryTheNextCandidate() throws Exception {
        // XvfExecutionApplication relies on this return value to walk past a venue rejection (most
        // often insufficient margin on one leg's venue) to the next-ranked candidate, the same way it
        // already does for a rules() failure or an unwired venue - rather than silently leaving that
        // slot of the twenty empty.
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        maker.nextOutcome = SubmitOutcome.REJECTED;
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            boolean resting = engine.open("X",
                    new PairedEntryEngine.Leg(maker, "XUSDT", VenueGateway.Side.SELL, new BigDecimal("3")),
                    new PairedEntryEngine.Leg(taker, "XUSDT", VenueGateway.Side.BUY, new BigDecimal("3")),
                    new BigDecimal("100"));

            assertFalse(resting, "rejected outright - nothing is resting, the slot is still open");
        }
    }

    @Test
    void openReturnsTrueWhenTheMakerOrderActuallyRests() throws Exception {
        RecordingGateway maker = new RecordingGateway("maker");
        RecordingGateway taker = new RecordingGateway("taker");
        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            boolean resting = engine.open("X",
                    new PairedEntryEngine.Leg(maker, "XUSDT", VenueGateway.Side.SELL, new BigDecimal("3")),
                    new PairedEntryEngine.Leg(taker, "XUSDT", VenueGateway.Side.BUY, new BigDecimal("3")),
                    new BigDecimal("100"));

            assertTrue(resting, "accepted normally - a maker order is resting, this slot is filled");
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

            assertQuantities(List.of("1", "1", "1"), taker.marketOrders);
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
