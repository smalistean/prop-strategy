package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.venue.VenueGateway.Side;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.TopOfBook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a resting order is priced.
 *
 * <p>The failure this guards is quiet: a post-only order priced through the other side is rejected
 * rather than filled, and the caller reads that as a market that moved rather than a price it chose.
 * On a one-tick book there is no room to improve at all, so the clamp is the normal case, not an edge.
 */
class XvfRoundTripImproveTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("rtImproveTicks");
    }

    @Test
    void restsAtTheTouchWhenNoImprovementIsAsked() {
        TopOfBook book = new TopOfBook(bd("0.015620"), bd("0.015630"), 0L);

        assertEquals(0, XvfRoundTripTest.improve(book, Side.BUY, bd("0.000001"))
                .compareTo(bd("0.015620")), "a BUY joins the bid");
        assertEquals(0, XvfRoundTripTest.improve(book, Side.SELL, bd("0.000001"))
                .compareTo(bd("0.015630")), "a SELL joins the ask");
    }

    @Test
    void oneTickBetterStandsInFrontOfTheQueueOnAWideBook() {
        // Bybit BMT as measured live: ten ticks between bid and ask, so there is room to stand inside.
        System.setProperty("rtImproveTicks", "1");
        TopOfBook book = new TopOfBook(bd("0.015620"), bd("0.015630"), 0L);

        assertEquals(0, XvfRoundTripTest.improve(book, Side.BUY, bd("0.000001"))
                .compareTo(bd("0.015621")), "a BUY improves upward, becoming the best bid");
        assertEquals(0, XvfRoundTripTest.improve(book, Side.SELL, bd("0.000001"))
                .compareTo(bd("0.015629")), "a SELL improves downward, becoming the best offer");
    }

    @Test
    void aOneTickBookCannotBeImprovedAndIsLeftAtTheTouch() {
        // Binance BMT, and 9 of 10 liquid perps measured: bid and ask are adjacent, so any
        // improvement would cross and be rejected as post-only.
        System.setProperty("rtImproveTicks", "1");
        TopOfBook book = new TopOfBook(bd("0.0155700"), bd("0.0155800"), 0L);

        assertEquals(0, XvfRoundTripTest.improve(book, Side.BUY, bd("0.0000100"))
                .compareTo(bd("0.0155700")), "must clamp back to the bid, not cross to the ask");
        assertEquals(0, XvfRoundTripTest.improve(book, Side.SELL, bd("0.0000100"))
                .compareTo(bd("0.0155800")), "must clamp back to the ask, not cross to the bid");
    }

    @Test
    void anImprovementLargerThanTheSpreadIsClampedRatherThanCrossing() {
        System.setProperty("rtImproveTicks", "50");
        TopOfBook book = new TopOfBook(bd("0.015620"), bd("0.015630"), 0L);

        assertEquals(0, XvfRoundTripTest.improve(book, Side.BUY, bd("0.000001"))
                .compareTo(bd("0.015620")), "50 ticks is past the ask; clamp");
        assertEquals(0, XvfRoundTripTest.improve(book, Side.SELL, bd("0.000001"))
                .compareTo(bd("0.015630")), "50 ticks is past the bid; clamp");
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
