package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.execution.XvfReconciler.DesiredLeg;
import com.smalistean.propstrategy.xvf.execution.XvfReconciler.Drift;
import com.smalistean.propstrategy.xvf.execution.XvfReconciler.Reason;
import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The planning half, which decides what gets closed.
 *
 * <p>Worth testing far more than the sending half: an order that fails is loud and recoverable, while
 * a plan that quietly closes a position the book still wants is a silent loss, and one that quietly
 * keeps a position the book has dropped is an unhedged leg nobody is watching.
 */
class XvfReconcilerTest {

    @Test
    void positionsMissingFromTheBookAreClosed() {
        var gateways = Map.<String, VenueGateway>of(
                "binance", stub("binance", pos("binance", "ATOMUSDT", "-8.4")));

        List<Drift> drifts = XvfReconciler.plan(gateways, List.of());

        assertEquals(1, drifts.size());
        Drift d = drifts.getFirst();
        assertEquals(Reason.NOT_IN_BOOK, d.reason());
        assertEquals(new BigDecimal("8.4"), d.reduceBy());
        // A short is reduced by buying.
        assertEquals(VenueGateway.Side.BUY, d.closingSide());
    }

    @Test
    void aPositionTheBookStillWantsIsLeftAlone() {
        var gateways = Map.<String, VenueGateway>of(
                "binance", stub("binance", pos("binance", "ATOMUSDT", "-8.4")));

        List<Drift> drifts = XvfReconciler.plan(gateways,
                List.of(new DesiredLeg("ATOM", "binance", "ATOMUSDT", new BigDecimal("-8.4"))));

        assertTrue(drifts.isEmpty(), "matching position must not be touched");
    }

    @Test
    void bookedButAbsentIsReportedAndNeverOpened() {
        var gateways = Map.<String, VenueGateway>of("binance", stub("binance"));

        List<Drift> drifts = XvfReconciler.plan(gateways,
                List.of(new DesiredLeg("ATOM", "binance", "ATOMUSDT", new BigDecimal("-8.4"))));

        assertEquals(1, drifts.size());
        assertEquals(Reason.MISSING, drifts.getFirst().reason());
        // Zero reduction is what keeps the reconciler from opening a leg on its own.
        assertEquals(BigDecimal.ZERO, drifts.getFirst().reduceBy());
    }

    @Test
    void anOversizedPositionIsTrimmedRatherThanClosed() {
        var gateways = Map.<String, VenueGateway>of(
                "binance", stub("binance", pos("binance", "ATOMUSDT", "-12.0")));

        List<Drift> drifts = XvfReconciler.plan(gateways,
                List.of(new DesiredLeg("ATOM", "binance", "ATOMUSDT", new BigDecimal("-8.0"))));

        assertEquals(Reason.OVERSIZED, drifts.getFirst().reason());
        assertEquals(new BigDecimal("4.0"), drifts.getFirst().reduceBy());
    }

    @Test
    void aPositionHeldTheWrongWayRoundIsClosedToFlatNotFlipped() {
        var gateways = Map.<String, VenueGateway>of(
                "binance", stub("binance", pos("binance", "ATOMUSDT", "8.4")));

        List<Drift> drifts = XvfReconciler.plan(gateways,
                List.of(new DesiredLeg("ATOM", "binance", "ATOMUSDT", new BigDecimal("-8.4"))));

        Drift d = drifts.getFirst();
        assertEquals(Reason.WRONG_SIDE, d.reason());
        // 8.4, not 16.8: closing to flat. Flipping in one order would be an open, and opening a
        // single leg is the naked-position failure this class exists to prevent.
        assertEquals(new BigDecimal("8.4"), d.reduceBy());
    }

    @Test
    void bothLegsOfAStrandedPairAreAdjacentSoTheyCloseTogether() {
        var gateways = Map.<String, VenueGateway>of(
                "binance", stub("binance", pos("binance", "ATOMUSDT", "-8.4")),
                "bybit", stub("bybit", pos("bybit", "ATOMUSDT", "8.4")));

        List<Drift> drifts = XvfReconciler.plan(gateways, List.of());

        assertEquals(2, drifts.size());
        assertEquals("ATOM", drifts.get(0).base());
        assertEquals("ATOM", drifts.get(1).base());
        assertEquals(Reason.NOT_IN_BOOK, drifts.get(0).reason());
        assertEquals(Reason.NOT_IN_BOOK, drifts.get(1).reason());
    }

    @Test
    void stepRoundingResidueIsNotTreatedAsAPosition() {
        var gateways = Map.<String, VenueGateway>of(
                "binance", stub("binance", pos("binance", "ATOMUSDT", "-8.40000001")));

        List<Drift> drifts = XvfReconciler.plan(gateways,
                List.of(new DesiredLeg("ATOM", "binance", "ATOMUSDT", new BigDecimal("-8.4"))));

        assertTrue(drifts.isEmpty(), "a dust difference must not produce an order");
    }

    private static VenueGateway.PositionSnapshot pos(String venue, String symbol, String qty) {
        return new VenueGateway.PositionSnapshot(venue, symbol, new BigDecimal(qty),
                new BigDecimal("1.42"));
    }

    /** Only positions() and name() matter to plan(); everything else refuses so a slip is obvious. */
    private static VenueGateway stub(String name, VenueGateway.PositionSnapshot... held) {
        return new VenueGateway() {
            @Override public String name() {
                return name;
            }
            @Override public List<PositionSnapshot> positions() {
                return List.of(held);
            }
            @Override public SubmitResult placePostOnly(String s, Side side, BigDecimal q,
                                                        BigDecimal p, String c, boolean r) {
                throw new UnsupportedOperationException("plan() must not place orders");
            }
            @Override public SubmitResult placeCappedIoc(String s, Side side, BigDecimal q,
                                                         BigDecimal w, String c, boolean r) {
                throw new UnsupportedOperationException("plan() must not place orders");
            }
            @Override public void cancel(OrderHandle handle) {
                throw new UnsupportedOperationException();
            }
            @Override public Optional<OrderSnapshot> orderByClientId(String s, String c) {
                return Optional.empty();
            }
            @Override public TopOfBook topOfBook(String venueSymbol) {
                throw new UnsupportedOperationException("plan() must not read the book");
            }
            @Override public SymbolRules rules(String venueSymbol) {
                return new SymbolRules(new BigDecimal("0.01"), new BigDecimal("5"),
                        new BigDecimal("0.001"));
            }
            @Override public AutoCloseable streamOrderUpdates(Consumer<OrderUpdate> listener) {
                return () -> { };
            }
        };
    }
}
