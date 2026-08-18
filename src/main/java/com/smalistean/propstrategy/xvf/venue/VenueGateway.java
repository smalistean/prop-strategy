package com.smalistean.propstrategy.xvf.venue;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One venue's trading surface: place orders, reconcile them, and hear about your own fills.
 *
 * <p>The listener is the point of this interface. XVF's entry is a post-only limit on one venue and a
 * crossing order on the other <b>the instant the first fills</b> — so what matters is the user data
 * stream (your order events), not the market data stream. Between the two fills the book is naked in
 * that coin, and the measured cost of that window is what the design minimises: 15 minutes of drift
 * costs 92bp at the median and 273bp at p90, against 3.2bp to cross the spread.
 *
 * <h2>The caller owns the client order ID</h2>
 * Not the adapter. An ID generated inside a gateway cannot be persisted before the request is sent,
 * which makes an ambiguous submission unresolvable — there is nothing to query by. Every submit takes
 * an ID the caller has already written down.
 *
 * <h2>A timeout is UNKNOWN, not a rejection</h2>
 * {@link SubmitResult} distinguishes three outcomes because a network failure after the venue
 * accepted an order looks identical to one before. Retrying an {@code UNKNOWN} places a second order;
 * treating it as {@code REJECTED} leaves a live order nobody is tracking. The only safe move is
 * {@link #orderByClientId} against the ID the caller already owns.
 *
 * <h2>Capped IOC, never an unbounded market order</h2>
 * Crossing is intentional; crossing at any price is not. A limit-priced IOC still executes
 * immediately against available liquidity but refuses to print through a chosen worst price, and it
 * maps consistently onto every venue. XVF hedges into coins chosen for being dislocated, which is
 * exactly where an unbounded market order is worst.
 *
 * <p>Implementations own their credentials. Nothing here reads keys, and no key material should be
 * logged, held in fields longer than a request needs, or written to the database.
 *
 * <h2>Per-venue stream endpoints</h2>
 * <ul>
 *   <li><b>Binance</b> — {@code POST /fapi/v1/listenKey}, then the current routed futures WebSocket
 *       endpoint, event {@code ORDER_TRADE_UPDATE}. The key expires after 60 minutes and must be
 *       extended with {@code PUT} every 30.</li>
 *   <li><b>Bybit</b> — {@code wss://stream.bybit.com/v5/private}, authenticate then subscribe to
 *       {@code order.linear} and {@code execution.linear}; deduplicate on {@code execId}.</li>
 *   <li><b>Hyperliquid</b> — {@code wss://api.hyperliquid.xyz/ws}, subscribe {@code orderUpdates}
 *       and {@code userFills}.</li>
 * </ul>
 *
 * <p>Note that Binance's <em>market data</em> futures stream connects but never delivers a frame in
 * this environment — see {@code ChallengeMonitorApplication}. The user data stream is a different
 * endpoint and is not known to have that problem, but it should be verified before being relied on,
 * and the engine must survive the stream going silent regardless.
 */
public interface VenueGateway {

    /** Venue name as used everywhere else: binance, bybit, hyperliquid. */
    String name();

    /**
     * Rests a limit order that is rejected rather than filled if it would cross.
     *
     * <p>Post-only, not a plain limit: a limit that crosses executes immediately and pays taker,
     * which silently converts the cheap leg into the expensive one. Rejection is the desired
     * behaviour — a missed entry costs one position's funding for one period, which is nothing.
     */
    SubmitResult placePostOnly(String venueSymbol, Side side, BigDecimal quantity,
                               BigDecimal limitPrice, String clientOrderId);

    /**
     * Crosses the spread immediately, but never worse than {@code worstPrice}.
     *
     * <p>Unfilled remainder is cancelled rather than rested, so the caller learns straight away how
     * much exposure is still open instead of discovering it later behind a resting order.
     */
    SubmitResult placeCappedIoc(String venueSymbol, Side side, BigDecimal quantity,
                                BigDecimal worstPrice, String clientOrderId);

    /** Best effort; a fill that has already happened cannot be cancelled. */
    void cancel(OrderHandle handle);

    /**
     * Authoritative state for one order, by the ID the caller supplied.
     *
     * <p>This is how an {@code UNKNOWN} submission is resolved, and how the engine rebuilds truth
     * after a restart. An empty result means the venue never saw the order.
     */
    Optional<OrderSnapshot> orderByClientId(String venueSymbol, String clientOrderId);

    /**
     * Best bid and ask, for pricing a resting order at the touch and capping a crossing one.
     *
     * <p>A mid or last price is not sufficient: resting at mid crosses and is rejected under
     * post-only, and capping from a stale last price sets a cap that is not related to the book.
     */
    TopOfBook topOfBook(String venueSymbol);

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

    enum Side {
        BUY, SELL;

        public Side opposite() {
            return this == BUY ? SELL : BUY;
        }
    }

    enum OrderState { RESTING, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED }

    /**
     * What a venue said about a submission.
     *
     * <p>{@code UNKNOWN} is not an error state to be logged and swallowed — it is the one outcome
     * that requires action before anything else happens on that pair.
     */
    enum SubmitOutcome { ACCEPTED, REJECTED, UNKNOWN }

    record SubmitResult(SubmitOutcome outcome, OrderHandle handle, String detail) {
        public boolean accepted() {
            return outcome == SubmitOutcome.ACCEPTED;
        }
    }

    record OrderHandle(String venue, String venueSymbol, String venueOrderId, String clientOrderId) { }

    /** Point-in-time truth for one order, from a query rather than a stream. */
    record OrderSnapshot(OrderHandle handle, OrderState state,
                         BigDecimal filledQuantity, BigDecimal averagePrice) { }

    /**
     * @param filledQuantity CUMULATIVE filled quantity for the order, not the size of this fill.
     *                       Binance sends this as {@code z}. Offsetting it on every event
     *                       over-hedges; see {@code PairedEntryEngine.onOrderUpdate}.
     */
    record OrderUpdate(String venue, String venueSymbol, String clientOrderId, OrderState state,
                       BigDecimal filledQuantity, BigDecimal averagePrice, long eventTimeMillis) { }

    record TopOfBook(BigDecimal bid, BigDecimal ask, long eventTimeMillis) {
        public BigDecimal touch(Side side) {
            // Resting SELL sits at the ask, resting BUY at the bid: the side of the book you are
            // joining, not the one you would cross into.
            return side == Side.SELL ? ask : bid;
        }
    }

    /**
     * @param stepSize        quantity increment
     * @param minNotionalUsd  smallest order the venue accepts
     * @param tickSize        price increment
     */
    record SymbolRules(BigDecimal stepSize, BigDecimal minNotionalUsd, BigDecimal tickSize) { }
}
