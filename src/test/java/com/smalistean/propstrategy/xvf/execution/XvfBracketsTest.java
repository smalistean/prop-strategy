package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.PositionSnapshot;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.Side;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.TriggerWhen;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bracket geometry, where a sign or width error is not a bug but a naked position.
 *
 * <p>The property that has to hold is that a move closes <em>both</em> legs. Closing one is worse than
 * placing nothing at all: it converts a market-neutral pair into an outright position at exactly the
 * moment the market is moving fast enough to have triggered it.
 */
class XvfBracketsTest {

    @Test
    void aLongIsClosedBySellingAndAShortByBuying() {
        var bands = XvfBrackets.pair(
                pos("hyperliquid", "ATOM", "8.41", "0.71"), p("1.4243"),
                pos("binance", "ATOMUSDT", "-8.41", "2.85"), p("1.425"));

        assertEquals(Side.SELL, bands.get(0).closingSide());
        assertEquals(Side.BUY, bands.get(1).closingSide());
    }

    @Test
    void aBigMoveClosesBothLegsAndNotJustOne() {
        // The pair as actually opened in live testing, with the liquidation prices the venues gave:
        // 0.71 below the Hyperliquid long, 2.85 above the Binance short. Sizing each leg from its own
        // liquidation price makes the bands different widths, so the narrow one fires alone and leaves
        // the other leg outright. This is that regression.
        var bands = XvfBrackets.pair(
                pos("hyperliquid", "ATOM", "8.41", "0.71"), p("1.4243"),
                pos("binance", "ATOMUSDT", "-8.41", "2.85"), p("1.425"));
        var longLeg = bands.get(0);
        var shortLeg = bands.get(1);

        BigDecimal crash = new BigDecimal("0.85");
        assertTrue(crash.compareTo(longLeg.lower()) <= 0, "long leg must close on a crash");
        assertTrue(crash.compareTo(shortLeg.lower()) <= 0,
                "short leg must close on the SAME crash, or the pair is left directional");

        BigDecimal spike = new BigDecimal("2.10");
        assertTrue(spike.compareTo(longLeg.upper()) >= 0, "long leg must close on a spike");
        assertTrue(spike.compareTo(shortLeg.upper()) >= 0,
                "short leg must close on the SAME spike, or the pair is left directional");
    }

    @Test
    void bothLegsShareTheTighterOfTheTwoDistances() {
        var bands = XvfBrackets.pair(
                pos("hyperliquid", "ATOM", "8.41", "0.71"), p("1.4243"),
                pos("binance", "ATOMUSDT", "-8.41", "2.85"), p("1.425"));

        BigDecimal longWidth = width(bands.get(0), p("1.4243"));
        BigDecimal shortWidth = width(bands.get(1), p("1.425"));
        assertEquals(0, longWidth.compareTo(shortWidth),
                "legs must share one relative band: " + longWidth + " vs " + shortWidth);

        // And it is the tighter one: half-way to liquidation is 25.08% of the mark on the Hyperliquid
        // leg and 50.18% on the Binance leg, so both take the Hyperliquid figure.
        assertEquals(0, longWidth.setScale(3, RoundingMode.HALF_UP).compareTo(new BigDecimal("0.502")),
                "expected twice the 25.08% half-width, got " + longWidth);
    }

    @Test
    void theDistanceIsHalfwayToLiquidation() {
        // 1.4243 against liquidation at 0.71 is 0.7143 away; half of that is 25.08% of the mark.
        BigDecimal fraction = XvfBrackets.relativeDistance(p("1.4243"), p("0.71"));

        assertEquals(0, fraction.setScale(4, RoundingMode.HALF_UP).compareTo(new BigDecimal("0.2508")),
                "got " + fraction);
    }

    @Test
    void noLiquidationPriceFallsBackToAFixedBandRatherThanZero() {
        // A venue reporting no liquidation price means the leg is small against cross collateral.
        // Treating it as "liquidates at 0" would put the band at half the mark on every such leg.
        assertEquals(0, XvfBrackets.relativeDistance(p("1.42"), null).compareTo(new BigDecimal("0.25")));
    }

    @Test
    void aLowerTriggerNeverGoesNegative() {
        // A band wider than the mark would put the lower trigger below zero, which no venue accepts.
        var bands = XvfBrackets.pair(
                pos("binance", "ATOMUSDT", "-8.4", "9.00"), p("1.42"),
                pos("bybit", "ATOMUSDT", "8.4", "0.01"), p("1.42"));

        for (var band : bands) {
            assertTrue(band.lower().signum() >= 0,
                    "a negative trigger price is not placeable: " + band.lower());
        }
    }

    @Test
    void theLossSideDependsOnWhichWayThePositionIsHeld() {
        // Selling closes a long, so falling price is its loss side.
        assertTrue(VenueGateway.isLossSide(Side.SELL, TriggerWhen.PRICE_FALLS_TO));
        assertFalse(VenueGateway.isLossSide(Side.SELL, TriggerWhen.PRICE_RISES_TO));
        // Buying closes a short, so it is the other way round.
        assertTrue(VenueGateway.isLossSide(Side.BUY, TriggerWhen.PRICE_RISES_TO));
        assertFalse(VenueGateway.isLossSide(Side.BUY, TriggerWhen.PRICE_FALLS_TO));
    }

    @Test
    void venuesReportNoLiquidationRiskThreeDifferentWaysAndAllMeanNull() {
        assertNull(VenueGateway.optionalPrice("0"), "binance sends 0");
        assertNull(VenueGateway.optionalPrice(""), "bybit sends empty");
        assertNull(VenueGateway.optionalPrice(null), "hyperliquid sends null");
        assertEquals(0, VenueGateway.optionalPrice("1.4243").compareTo(new BigDecimal("1.4243")));
    }

    /** Full band width as a fraction of the leg's own mark, so venues can be compared. */
    private static BigDecimal width(XvfBrackets.Band band, BigDecimal mark) {
        return band.upper().subtract(band.lower()).divide(mark, 4, RoundingMode.HALF_UP);
    }

    private static PositionSnapshot pos(String venue, String symbol, String qty, String liquidation) {
        return new PositionSnapshot(venue, symbol, new BigDecimal(qty), new BigDecimal("1.42"),
                liquidation == null ? null : new BigDecimal(liquidation));
    }

    private static BigDecimal p(String v) {
        return new BigDecimal(v);
    }
}
