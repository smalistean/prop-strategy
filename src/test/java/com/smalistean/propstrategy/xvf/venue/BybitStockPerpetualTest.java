package com.smalistean.propstrategy.xvf.venue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ticker-collision regression: 2026-08-19, a live pair matched Binance's ONUSDT (Orochi
 * Network, a crypto token trading near $0.24) against Bybit's ONUSDT (ON Semiconductor Corp,
 * NASDAQ: ON, a stock perpetual trading near $79) purely because both venues answer "ON" to a
 * ticker query. The sizing math never noticed - both legs priced to the same target notional on
 * their own venue regardless of what either one actually was - so the position that opened was
 * two unrelated, uncorrelated directional bets, not a hedge. It cost real money to close.
 */
class BybitStockPerpetualTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void aStockListingIsRefused() throws Exception {
        // Verbatim from instruments-info for ONUSDT, captured the day this was found.
        var instrument = MAPPER.readTree("""
                {"symbol":"ONUSDT","baseCoin":"ON","quoteCoin":"USDT","symbolType":"stock"}
                """);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> BybitGateway.requireCryptoPerp("ONUSDT", instrument));
        assertTrue(e.getMessage().contains("stock"), "the reason should name what it actually is");
        assertTrue(e.getMessage().contains("ONUSDT"), "the reason should name which symbol");
    }

    @Test
    void aGenuineCryptoPerpetualIsAccepted() throws Exception {
        // Verbatim from instruments-info for BTCUSDT, for contrast.
        var instrument = MAPPER.readTree("""
                {"symbol":"BTCUSDT","baseCoin":"BTC","quoteCoin":"USDT","symbolType":""}
                """);

        assertDoesNotThrow(() -> BybitGateway.requireCryptoPerp("BTCUSDT", instrument));
    }

    @Test
    void anInnovationZoneListingIsAcceptedAsGenuineCrypto() throws Exception {
        // Verbatim from instruments-info for EPICUSDT, captured 2026-08-19: a real, already-open,
        // correctly-hedged position that a first version of this guard refused outright by treating
        // any non-empty symbolType as a collision. EPICUSDT priced within 0.3% of Binance's EPICUSDT
        // at the time - nothing like ONUSDT's stock-vs-token gap - confirming this tag means "Bybit's
        // Innovation Zone", a crypto listing tier, not a different asset class.
        var instrument = MAPPER.readTree("""
                {"symbol":"EPICUSDT","baseCoin":"EPIC","quoteCoin":"USDT","symbolType":"innovation"}
                """);

        assertDoesNotThrow(() -> BybitGateway.requireCryptoPerp("EPICUSDT", instrument));
    }

    @Test
    void anEtfListingIsRefused() throws Exception {
        // Verbatim from instruments-info for CSOPSKHYNIX2LUSDT, captured 2026-08-19: a leveraged
        // fund product (CSOP is a real Hong Kong ETF issuer) that would collide with any unrelated
        // crypto base of the same name on another venue.
        var instrument = MAPPER.readTree("""
                {"symbol":"CSOPSKHYNIX2LUSDT","baseCoin":"CSOPSKHYNIX2L","quoteCoin":"USDT",
                 "symbolType":"ETF"}
                """);

        assertThrows(IllegalStateException.class,
                () -> BybitGateway.requireCryptoPerp("CSOPSKHYNIX2LUSDT", instrument));
    }

    @Test
    void aMissingSymbolTypeFieldIsTreatedAsCrypto() throws Exception {
        // Defensive: if Bybit ever omits the field entirely rather than sending "", this must not
        // fail closed for every ordinary symbol - only an explicit non-empty value refuses.
        var instrument = MAPPER.readTree("""
                {"symbol":"ETHUSDT","baseCoin":"ETH","quoteCoin":"USDT"}
                """);

        assertDoesNotThrow(() -> BybitGateway.requireCryptoPerp("ETHUSDT", instrument));
    }
}
