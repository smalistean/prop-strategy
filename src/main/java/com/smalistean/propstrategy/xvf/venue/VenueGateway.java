package com.smalistean.propstrategy.xvf.venue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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
     * Whether this gateway can actually trade.
     *
     * <p>A pair needing an unwired venue must be SKIPPED, not attempted - one unimplemented venue
     * should cost that pair, not the whole rebalance. The unwired implementation still throws from
     * every other method, so a code path that bypasses this check fails loudly rather than opening
     * one leg of a hedge.
     */
    default boolean wired() {
        return true;
    }

    /**
     * Rests a limit order that is rejected rather than filled if it would cross.
     *
     * <p>Post-only, not a plain limit: a limit that crosses executes immediately and pays taker,
     * which silently converts the cheap leg into the expensive one. Rejection is the desired
     * behaviour — a missed entry costs one position's funding for one period, which is nothing.
     */
    default SubmitResult placePostOnly(String venueSymbol, Side side, BigDecimal quantity,
                                       BigDecimal limitPrice, String clientOrderId) {
        return placePostOnly(venueSymbol, side, quantity, limitPrice, clientOrderId, false);
    }

    /**
     * @param reduceOnly true when this order may only shrink an existing position, never open or
     *                   flip one. Closing legs must set it: without it, an order sent against a
     *                   position that has already gone flat silently opens a NEW position in the
     *                   opposite direction - a naked leg created by the very code meant to remove one.
     */
    SubmitResult placePostOnly(String venueSymbol, Side side, BigDecimal quantity,
                               BigDecimal limitPrice, String clientOrderId, boolean reduceOnly);

    /**
     * Crosses the spread immediately, but never worse than {@code worstPrice}.
     *
     * <p>Unfilled remainder is cancelled rather than rested, so the caller learns straight away how
     * much exposure is still open instead of discovering it later behind a resting order.
     */
    default SubmitResult placeCappedIoc(String venueSymbol, Side side, BigDecimal quantity,
                                        BigDecimal worstPrice, String clientOrderId) {
        return placeCappedIoc(venueSymbol, side, quantity, worstPrice, clientOrderId, false);
    }

    /** @param reduceOnly see {@link #placePostOnly}; closing legs must set it. */
    SubmitResult placeCappedIoc(String venueSymbol, Side side, BigDecimal quantity,
                                BigDecimal worstPrice, String clientOrderId, boolean reduceOnly);

    /**
     * Rests a reduce-only market order at the venue that fires when price crosses {@code triggerPrice}.
     *
     * <p>Unlike everything else here, this order survives the process that placed it. That is the
     * entire point: it is the exit that still works when the JVM is dead, the machine is asleep or the
     * network is gone, none of which the venue can distinguish from a strategy that has simply stopped
     * caring about its position.
     *
     * <p>A pair needs <b>four</b> of these, two per leg, one either side. A single trigger per leg is
     * the dangerous arrangement: the two legs of an XVF pair move together and oppositely, so a stop on
     * only the losing leg closes the hedge and leaves the winning leg naked, converting a market-
     * neutral position into a directional one during the exact move that provoked it. With a trigger
     * on both sides of both legs, any move far enough in either direction closes all four.
     *
     * @param side         the side that shrinks the position: SELL closes a long, BUY closes a short
     * @param triggerPrice the price that arms it, on the venue's own tick grid
     * @param when         which direction of crossing fires it
     */
    default SubmitResult placeReduceOnlyTrigger(String venueSymbol, Side side, BigDecimal quantity,
                                                BigDecimal triggerPrice, TriggerWhen when,
                                                String clientOrderId) {
        throw new UnsupportedOperationException(name() + " does not implement trigger orders");
    }

    enum TriggerWhen { PRICE_RISES_TO, PRICE_FALLS_TO }

    /**
     * Whether this trigger is the loss-side one, which is how every venue names the two variants.
     *
     * <p>Binance picks between STOP_MARKET and TAKE_PROFIT_MARKET, Hyperliquid between {@code "sl"} and
     * {@code "tp"}, and both mean the same thing: does this trigger fire when the position is losing or
     * when it is winning. A SELL closes a long, so falling price is its loss side; a BUY closes a short,
     * so rising price is. Bybit needs only the raw direction and ignores this.
     *
     * <p>For XVF neither name is meaningful - the pair earns funding, not price - and both orders exist
     * only so the two legs close together. The distinction is kept because the venues insist on it.
     */
    static boolean isLossSide(Side side, TriggerWhen when) {
        return side == Side.SELL
                ? when == TriggerWhen.PRICE_FALLS_TO
                : when == TriggerWhen.PRICE_RISES_TO;
    }

    /**
     * Parses a price that the venue may legitimately not have, returning null when it does not.
     *
     * <p>Liquidation price is the case that matters: Binance sends {@code "0"}, Bybit an empty string,
     * Hyperliquid {@code null}, and all three mean the same thing - the position is small enough
     * against its cross-margin collateral that no liquidation level applies. Parsing that into
     * {@code BigDecimal.ZERO} would be read as "liquidates at zero", which is the widest possible band
     * rather than no band at all.
     */
    static BigDecimal optionalPrice(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            BigDecimal parsed = new BigDecimal(raw);
            return parsed.signum() > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

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

    /**
     * Every open position on this venue. Empty means flat.
     *
     * <p>Venue truth, not local bookkeeping. This is what makes "the pair closed cleanly" a checkable
     * claim rather than an assumption - our own fill accounting can be right about every message it
     * received and still be wrong about the account, if a message never arrived.
     */
    List<PositionSnapshot> positions();

    /**
     * FREE collateral this venue can put behind a NEW XVF leg, in USD - not the venue's total balance
     * or equity, which includes margin already committed to existing positions. Measured live
     * 2026-08-22: total balance stayed near $1,787 on an account whose real new-order headroom had
     * fallen to $5.16, and every implementation originally read the total figure instead of this one.
     *
     * <p>Capital is siloed per venue - there is no cross-margin between Binance, Bybit and
     * Hyperliquid - so a book that fits the TOTAL can still fail on the one venue that happens to
     * carry the most legs. That is not a hypothetical: with 18 of 20 pairs CEX-CEX on 2026-08-20,
     * Bybit had to carry 20 legs against Binance's 18 and Hyperliquid's 2, and Bybit alone decided
     * the largest workable capital figure.
     *
     * <p>Reported as ONE number per venue, including on Hyperliquid, whose unified account splits the
     * same balance across two {@code info} responses - a perp side that can read {@code 0.0} while
     * the USDC sits under the spot side. Both are spendable, so both are counted; reading only the
     * perp figure reports a funded account as empty.
     */
    BigDecimal availableCapital();

    /**
     * Sets the leverage this account uses for a symbol, on both sides of a one-way position.
     *
     * <p>Must be called before opening, never attached to an order. Every venue measured here
     * defaulted a fresh symbol to whatever an earlier session had left set - 20x on Binance, 3x on
     * Hyperliquid, on the very account this project trades with - which has nothing to do with
     * {@code XvfConfig.LEG_LEVERAGE} and can differ leg to leg. A caller that opens a position
     * without calling this first is trusting leftover account state, and the two legs of a pair can
     * end up carrying wildly different risk for a reason neither leg chose.
     *
     * <p>Throws rather than returning a boolean: a leverage call that silently failed and left a leg
     * at 20x is a correctness problem the caller cannot detect from a {@code false}, and every call
     * site opens real money moments later. Most venues reject a leverage change against a symbol
     * that already has an open position or a resting order, so this belongs strictly before either.
     */
    void setLeverage(String venueSymbol, int leverage);

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

    /**
     * @param signedQuantity  positive is long, negative is short, zero never appears - a flat symbol
     *                        is simply absent from {@link #positions()}
     * @param liquidationPrice where the venue will close this leg for us, or null when it reports
     *                        none - which happens when cross-margin collateral dwarfs the position.
     *                        Null means "no meaningful liquidation risk", not "unknown"
     */
    record PositionSnapshot(String venue, String venueSymbol, BigDecimal signedQuantity,
                            BigDecimal entryPrice, BigDecimal liquidationPrice) {

        /** Kept for callers that only care about size and side. */
        public PositionSnapshot(String venue, String venueSymbol, BigDecimal signedQuantity,
                                BigDecimal entryPrice) {
            this(venue, venueSymbol, signedQuantity, entryPrice, null);
        }
    }

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

    /**
     * Snaps a price onto the venue's tick grid, rounding in whichever direction preserves intent.
     *
     * <p>A price read straight from the book is already on the grid, but any price <em>derived</em>
     * from one - a slippage cap, a mid, an offset - generally is not, and venues differ in how they
     * react. Binance rejects the order outright ({@code -1111}), while Bybit silently accepts it. That
     * asymmetry is worse than either behaviour alone: in a paired trade it fills one leg and rejects
     * the other, which is exactly how a hedged position becomes a naked one.
     *
     * <p>Direction is chosen so rounding never defeats the order type. A {@code marketable} price is
     * a bound on how far the order may cross, so it rounds <em>outward</em> - a BUY cap up, a SELL cap
     * down - and stays marketable; at most one tick of extra permitted slippage, against the certainty
     * of the order being accepted. A passive price must never cross, so it rounds <em>inward</em> - a
     * BUY down, a SELL up - keeping a post-only order post-only.
     */
    static BigDecimal roundToTick(BigDecimal price, BigDecimal tick, Side side, boolean marketable) {
        if (tick == null || tick.signum() <= 0) {
            return price;
        }
        boolean up = marketable == (side == Side.BUY);
        BigDecimal ticks = price.divide(tick, 0, up ? RoundingMode.CEILING : RoundingMode.FLOOR);
        return ticks.multiply(tick).stripTrailingZeros();
    }
}
