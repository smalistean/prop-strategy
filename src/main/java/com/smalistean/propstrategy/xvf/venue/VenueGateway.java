package com.smalistean.propstrategy.xvf.venue;

import java.math.BigDecimal;
import java.util.function.Consumer;

/**
 * One venue's trading surface: place orders, and hear about your own fills.
 *
 * <p>The listener is the point of this interface. XVF's entry is a post-only limit on one venue and a
 * market order on the other <b>the instant the first fills</b> — so what matters is the user data
 * stream (your order events), not the market data stream. Between the two fills the book is naked in
 * that coin, and the measured cost of that window is what the design minimises: 15 minutes of drift
 * costs 92bp at the median and 273bp at p90, against 3.2bp to cross the spread. Reacting to a fill
 * event in a network round trip rather than on a timer is the difference between those numbers.
 *
 * <p>Implementations own their credentials. Nothing here reads keys, and no key material should be
 * logged, held in fields longer than a request needs, or written to the database.
 *
 * <h2>Per-venue stream endpoints</h2>
 * <ul>
 *   <li><b>Binance</b> — {@code POST /fapi/v1/listenKey}, then
 *       {@code wss://fstream.binance.com/ws/<listenKey>}, event {@code ORDER_TRADE_UPDATE}. The key
 *       expires after 60 minutes and must be extended with {@code PUT} every 30.</li>
 *   <li><b>Bybit</b> — {@code wss://stream.bybit.com/v5/private}, authenticate then subscribe to
 *       {@code order} and {@code execution}.</li>
 *   <li><b>Hyperliquid</b> — {@code wss://api.hyperliquid.xyz/ws}, subscribe
 *       {@code {"type":"userFills","user":"0x..."}}.</li>
 *   <li><b>dYdX</b> — {@code wss://indexer.dydx.trade/v4/ws}, channel
 *       {@code v4_subaccounts}.</li>
 * </ul>
 *
 * <p>Note that Binance's <em>market data</em> futures stream connects but never delivers a frame in
 * this environment — see {@code ChallengeMonitorApplication}. The user data stream is a different
 * endpoint and is not known to have that problem, but it should be verified before being relied on,
 * and the engine must survive the stream going silent regardless.
 */
public interface VenueGateway {

    /** Venue name as used everywhere else: binance, bybit, hyperliquid, dydx. */
    String name();

    /**
     * Rests a limit order that is rejected rather than filled if it would cross.
     *
     * <p>Post-only, not a plain limit: a limit that crosses executes immediately and pays taker,
     * which silently converts the cheap leg into the expensive one. Rejection is the desired
     * behaviour — a missed entry costs one position's funding for one period, which is nothing.
     */
    OrderHandle placePostOnly(String venueSymbol, Side side, BigDecimal quantity, BigDecimal limitPrice);

    /** Crosses the spread immediately. Used for the second leg, and for anything urgent. */
    OrderHandle placeMarket(String venueSymbol, Side side, BigDecimal quantity);

    /** Best effort; a fill that has already happened cannot be cancelled. */
    void cancel(OrderHandle handle);

    /**
     * Subscribes to this account's order events and calls the listener on every update.
     *
     * <p>The engine treats a silent stream as a failure, not as "no fills" — an unhedged leg with a
     * dead listener is the worst state the system can be in, so implementations must surface
     * disconnects rather than reconnecting quietly.
     */
    AutoCloseable streamOrderUpdates(Consumer<OrderUpdate> listener);

    /** Quantity step and minimum notional, needed to size a leg without rounding it into nonsense. */
    SymbolRules rules(String venueSymbol);

    enum Side { BUY, SELL }

    enum OrderState { RESTING, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }

    record OrderHandle(String venue, String venueSymbol, String venueOrderId, String clientOrderId) { }

    record OrderUpdate(String venue, String venueSymbol, String clientOrderId, OrderState state,
                       BigDecimal filledQuantity, BigDecimal averagePrice, long eventTimeMillis) { }

    /**
     * @param stepSize        quantity increment
     * @param minNotionalUsd  smallest order the venue accepts
     * @param tickSize        price increment
     */
    record SymbolRules(BigDecimal stepSize, BigDecimal minNotionalUsd, BigDecimal tickSize) { }
}
