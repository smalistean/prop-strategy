package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.venue.VenueGateway.SymbolRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract-multiplier regression.
 *
 * <p>Until 2026-08-18 both legs were sized from ONE venue's price, which is correct only when the two
 * venues quote the same contract unit. 3.6% of historical selections pair 1000PEPE against PEPE or
 * kPEPE against PEPE, and on those the hedge was out by that multiple - short $250 against long
 * $250,000, or the reverse.
 */
class XvfExecutionSizingTest {

    private static SymbolRules rules(String step, String minNotional) {
        return new SymbolRules(new BigDecimal(step), new BigDecimal(minNotional), new BigDecimal("0.0001"));
    }

    @Test
    void differentContractUnitsProduceMatchedUsdNotional() {
        // 1000PEPE quoted per 1000 tokens against PEPE quoted per token: a 1000x price difference
        // that is purely a unit convention, not a dislocation.
        var sized = XvfExecutionApplication.size(250,
                new BigDecimal("0.0050"), rules("1", "5"),          // maker: 1000PEPE
                new BigDecimal("0.0000050"), rules("1000", "5"));   // taker: PEPE

        assertNull(sized.rejection(), "a pure unit difference must not be rejected");
        // 250 / 0.005 = 50,000 contracts of 1000 tokens = 50,000,000 tokens.
        // 250 / 0.000005 = 50,000,000 tokens. Same underlying, different native numbers.
        assertEquals(0, sized.makerQty().compareTo(new BigDecimal("50000")));
        assertEquals(0, sized.takerQty().compareTo(new BigDecimal("50000000")));
        assertTrue(sized.imbalance() < 1e-9, "notionals should match exactly here");
    }

    @Test
    void identicalUnitsGiveIdenticalQuantities() {
        var sized = XvfExecutionApplication.size(250,
                new BigDecimal("2.5"), rules("0.1", "5"),
                new BigDecimal("2.5"), rules("0.1", "5"));
        assertNull(sized.rejection());
        assertEquals(0, sized.makerQty().compareTo(sized.takerQty()));
    }

    @Test
    void coarseStepsThatSkewTheHedgeAreRejected() {
        // One step worth $100 against a $250 leg: 2.5 steps floors to 2, so that leg lands on $200
        // while the other reaches $250. A fifth of the position is naked - exactly what the
        // tolerance exists to catch.
        var sized = XvfExecutionApplication.size(250,
                new BigDecimal("100.0"), rules("1", "5"),
                new BigDecimal("1.0"), rules("1", "5"));

        assertNotNull(sized.rejection(), "a large residual must be rejected, not opened crooked");
        assertTrue(sized.rejection().contains("differ by"), sized.rejection());
        assertEquals(0.20, sized.imbalance(), 1e-6, "$200 against $250 is 20% out");
    }

    @Test
    void smallRoundingResidualIsAccepted() {
        // 250 / 3.00 = 83.33 -> 83.3 at a 0.1 step, leaving $249.90: 0.04% out, well inside 1%.
        var sized = XvfExecutionApplication.size(250,
                new BigDecimal("3.00"), rules("0.1", "5"),
                new BigDecimal("3.00"), rules("0.1", "5"));
        assertNull(sized.rejection());
        assertTrue(sized.imbalance() < 0.01);
    }

    @Test
    void aStepLargerThanTheLegIsRejected() {
        var sized = XvfExecutionApplication.size(250,
                new BigDecimal("400.0"), rules("1", "5"),
                new BigDecimal("1.0"), rules("1", "5"));
        assertNotNull(sized.rejection());
        assertTrue(sized.rejection().contains("step size exceeds"), sized.rejection());
    }

    @Test
    void belowAVenueMinimumIsRejected() {
        var sized = XvfExecutionApplication.size(20,
                new BigDecimal("1.0"), rules("1", "5"),
                new BigDecimal("1.0"), rules("1", "100"));   // taker demands $100 minimum
        assertNotNull(sized.rejection());
        assertTrue(sized.rejection().contains("minimum notional"), sized.rejection());
    }
}
