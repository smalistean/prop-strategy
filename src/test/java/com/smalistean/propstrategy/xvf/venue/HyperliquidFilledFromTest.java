package com.smalistean.propstrategy.xvf.venue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The regression for an unhedged short of 8.5 ATOM.
 *
 * <p>{@code orderByClientId} exists to answer one question: when the stream reported nothing, does
 * that mean no fill, or no message? It used to answer by reading {@code cumulativeFilled}, which the
 * stream itself populates - so a missed fill produced a confident zero twice over, and the caller
 * concluded nothing had traded while the venue held a live position. The order below is the real
 * response for the order that did it.
 */
class HyperliquidFilledFromTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aFilledIocIsReadFromTheVenueResponse() throws Exception {
        // Verbatim from orderStatus for oid 519515305389, the fill the harness called "nothing".
        var order = MAPPER.readTree("""
                {"coin":"ATOM","side":"A","limitPx":"1.4068","sz":"0.0","oid":519515305389,
                 "origSz":"8.5","tif":"Ioc","reduceOnly":false,
                 "cloid":"0xe91bc054852edbf3d8717efc65676900"}
                """);

        assertEquals(0, HyperliquidGateway.filledFrom(order).compareTo(new BigDecimal("8.5")),
                "the venue said origSz 8.5 and sz 0.0; that is a full fill");
    }

    @Test
    void aPartialFillReportsWhatTraded() throws Exception {
        var order = MAPPER.readTree("{\"origSz\":\"8.5\",\"sz\":\"3.5\"}");

        assertEquals(0, HyperliquidGateway.filledFrom(order).compareTo(new BigDecimal("5.0")));
    }

    @Test
    void anUntouchedRestingOrderReportsZero() throws Exception {
        var order = MAPPER.readTree("{\"origSz\":\"8.5\",\"sz\":\"8.5\"}");

        assertEquals(0, HyperliquidGateway.filledFrom(order).compareTo(BigDecimal.ZERO),
                "nothing traded, and this is the case the caller may legitimately treat as no fill");
    }

    @Test
    void missingFieldsCannotProduceANegativeFill() throws Exception {
        // A shape change should not manufacture a negative quantity, which would be hedged as a
        // position in the opposite direction.
        assertEquals(0, HyperliquidGateway.filledFrom(MAPPER.readTree("{}")).compareTo(BigDecimal.ZERO));
        assertEquals(0, HyperliquidGateway.filledFrom(MAPPER.readTree("{\"sz\":\"9.0\"}"))
                .compareTo(BigDecimal.ZERO));
    }
}
