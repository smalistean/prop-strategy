package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderHandle;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderSnapshot;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitOutcome;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitResult;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderState;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderUpdate;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opens one XVF position: post-only on the maker venue, market on the other the instant it fills.
 *
 * <h2>Why this shape</h2>
 * A pair opened as two independent post-only orders can fill one side and leave the other resting,
 * which is not a hedged position but a naked directional bet in a coin selected precisely because it
 * is dislocated. Measured on the selected universe, the median coin travels 0.56% in five minutes and
 * 1.81% in an hour; crossing the spread costs 3.2bp. There is no horizon at which waiting is
 * rational, so the second leg is sent on the fill event rather than on a timer.
 *
 * <h2>Which leg rests</h2>
 * The <b>thinner</b> venue takes the post-only order. That is where crossing costs most and where a
 * resting order is most valuable; the liquid venue absorbs a market order cheaply. Putting the limit
 * on the liquid side would earn the smaller rebate and pay the larger slippage.
 *
 * <h2>The resting price is not static</h2>
 * A post-only order sits exactly where it was placed; if the market moves away, it can wait for the
 * full {@code abandonAfter} window and never fill. This engine re-prices it every {@code chaseEvery}:
 * cancel whatever is resting, read the touch again, place a fresh order for whatever quantity is
 * still unfilled. Verified live 2026-08-19 - a maker resting untouched for over three minutes on a
 * price the book had already moved past.
 *
 * <h2>A chase creates a NEW order, not a modified one</h2>
 * Every venue here treats cancel-then-replace as two independent orders, each with its own venue-side
 * cumulative fill counter starting at zero. Differencing a single pair-wide watermark against
 * whatever the latest update reports - correct for one order's whole lifetime - breaks the moment a
 * second order exists: a fresh order's small cumulative would read as LESS than the first order's
 * high-water mark, and the increment would be silently dropped. So the fill watermark is kept PER
 * ORDER (per client id), and the pair's total is the sum of every order's own watermark, never a
 * separately-mutated running total that could drift from it.
 *
 * <h2>Failure states, and which one is worst</h2>
 * <ol>
 *   <li><b>Maker never fills</b> — harmless. Cancel, skip the position, lose one period of funding on
 *       one of twenty positions.</li>
 *   <li><b>Maker fills, hedge send fails</b> — the dangerous one. The engine retries the hedge
 *       immediately and escalates; it does NOT cancel the maker leg, because that leg is already
 *       filled and "cancelling" it means another market order in the same direction as the exposure.</li>
 *   <li><b>Stream goes silent</b> — treated as failure, not as absence of fills. A fill that arrived
 *       while the listener was dead leaves an unhedged position nobody is watching, so the engine
 *       reconciles by polling rather than assuming.</li>
 * </ol>
 */
public final class PairedEntryEngine implements AutoCloseable {

    /** One pair being opened. */
    public record Leg(VenueGateway gateway, String venueSymbol, Side side, BigDecimal quantity) { }

    public enum PairState { WORKING, MAKER_FILLED, HEDGED, ABANDONED, UNHEDGED_ALERT }

    /**
     * @param orderFilled cumulative filled quantity PER CLIENT ID (per order), monotonic within each
     *                    order. A pair may own several of these over its life, one per chase.
     */
    private record Pair(String base, Leg maker, Leg taker,
                        AtomicReference<PairState> state, Instant opened,
                        AtomicReference<OrderHandle> makerHandle,
                        Map<String, BigDecimal> orderFilled,
                        AtomicReference<BigDecimal> hedged,
                        BigDecimal hedgeRatio, boolean closing) {

        /** Total maker fill across every order this pair has ever rested, chased or not. */
        BigDecimal totalFilled() {
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal v : orderFilled.values()) {
                sum = sum.add(v);
            }
            return sum;
        }
    }

    private final Map<String, Pair> byClientId = new ConcurrentHashMap<>();
    // One entry per pair, added exactly once at creation - unlike byClientId, which gains a new
    // entry every chase re-placement and would double-count a heavily-chased pair if used for
    // progress reporting. Append-once, read-often is exactly what this collection is for.
    private final java.util.List<Pair> allPairs = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    private final Duration abandonAfter;
    private final Duration chaseEvery;

    public PairedEntryEngine(Duration abandonAfter) {
        this(abandonAfter, Duration.ofSeconds(30));
    }

    /** @param chaseEvery how often an unfilled (or partially filled) maker is cancelled and re-priced */
    public PairedEntryEngine(Duration abandonAfter, Duration chaseEvery) {
        this.abandonAfter = abandonAfter;
        this.chaseEvery = chaseEvery;
    }

    /**
     * Starts one pair. The maker leg is placed immediately and re-priced every {@code chaseEvery}
     * until it fills or {@code abandonAfter} passes; the taker leg waits for the fill event.
     *
     * @param makerLeg  the THINNER venue — rests
     * @param takerLeg  the more liquid venue — crossed on fill
     */
    /**
     * @return true if a maker order ended up resting, false if the venue rejected it outright (for
     *         example insufficient margin) - the caller's signal to walk to the next candidate
     *         instead of counting this base as having filled a slot. Distinct from abandonment after
     *         {@code abandonAfter}, which the caller learns about later, from {@link #outstanding()}.
     */
    public boolean open(String base, Leg makerLeg, Leg takerLeg, BigDecimal makerLimit) {
        return work(base, makerLeg, takerLeg, makerLimit, false);
    }

    /**
     * Unwinds a pair the same way it was opened: resting on the thin venue, crossing on the fill.
     *
     * <p>The shape is identical to {@link #open} and the economics are why. All-taker execution costs
     * about 34bp for a pair's open and close against roughly 21bp of funding per 3-day cycle, so an
     * exit that always crosses spends more than the position earns. Resting the exit on the thinner
     * venue recovers most of that, which is what makes the strategy solvent rather than merely correct.
     *
     * <p><b>Both legs are reduce-only</b>, so an order arriving after its leg has already gone flat is
     * ignored by the venue rather than opening a fresh position in the opposite direction.
     *
     * <h2>Where this differs from opening, and it is the important part</h2>
     * An entry whose maker never fills is a missed opportunity, so {@link #open} simply cancels and
     * forgets. An exit whose maker never fills still holds the position - forgetting it would leave a
     * pair open that the book has already decided to be rid of, and the caller would have been told it
     * was closed. So the deadline here cancels and then <em>crosses</em>. Paying the spread is the
     * point: the position has to go, and 5bp is the price of certainty.
     */
    public void close(String base, Leg makerLeg, Leg takerLeg, BigDecimal makerLimit) {
        work(base, makerLeg, takerLeg, makerLimit, true);
    }

    private boolean work(String base, Leg makerLeg, Leg takerLeg, BigDecimal makerLimit, boolean closing) {
        // Taker units per maker unit. The two venues may quote different contract sizes for the
        // same asset - 1000PEPE against PEPE - so hedging the maker's NATIVE filled quantity on the
        // taker venue would be out by that multiple. The caller has already sized both legs to equal
        // USD notional, so their ratio is exactly the conversion needed.
        BigDecimal hedgeRatio = takerLeg.quantity().divide(makerLeg.quantity(), 12, RoundingMode.HALF_UP);
        Pair pair = new Pair(base, makerLeg, takerLeg,
                new AtomicReference<>(PairState.WORKING), Instant.now(), new AtomicReference<>(),
                new ConcurrentHashMap<>(), new AtomicReference<>(BigDecimal.ZERO), hedgeRatio, closing);
        allPairs.add(pair);
        long deadline = System.nanoTime() + abandonAfter.toNanos();
        boolean resting = placeMaker(pair, makerLeg.quantity(), makerLimit);
        if (shouldKeepChasing(pair, resting)) {
            scheduleChase(pair, deadline);
        }
        return resting;
    }

    /**
     * Whether this pair still needs a chase timer after an attempted placement.
     *
     * <p>An entry that never rested is done - {@link #placeMaker} already marked it
     * {@link PairState#ABANDONED} and there is nothing left to chase. A CLOSING pair is different by
     * this class's own contract (see {@link #close}'s javadoc): giving up is not available, so a
     * rejected placement - initial or mid-chase - must still get another chase timer, right up to the
     * point {@link #finalizeDeadline} takes over and crosses. Confirmed missing live 2026-08-22 on
     * KAITO: a single post-only rejection (price ticked between the reference quote and the placement,
     * ordinary and transient) left the pair silently WORKING forever with no timer ever scheduled
     * again - not reported by {@link #outstanding()} either, since that filters out exactly the
     * terminal state this same rejection would have produced for an entry.
     */
    private boolean shouldKeepChasing(Pair pair, boolean placedOk) {
        return placedOk || (pair.closing() && !isTerminal(pair.state().get()));
    }

    /**
     * Places one resting order for {@code quantity}, registered under a fresh client id. Returns
     * false if nothing ended up resting (rounds to nothing, or the venue rejected it) - the caller
     * must not schedule a chase for an order that does not exist.
     */
    private boolean placeMaker(Pair pair, BigDecimal quantity, BigDecimal price) {
        Leg makerLeg = pair.maker();
        BigDecimal stepped = round(quantity, makerLeg.gateway().rules(makerLeg.venueSymbol()).stepSize());
        if (stepped.signum() <= 0) {
            return false;   // whatever is left rounds below one step; functionally filled
        }
        String clientId = (pair.closing() ? "xvfx-" : "xvf-") + pair.base() + "-" + System.nanoTime();
        byClientId.put(clientId, pair);

        SubmitResult submitted = makerLeg.gateway().placePostOnly(
                makerLeg.venueSymbol(), makerLeg.side(), stepped, price, clientId, pair.closing());
        // An UNKNOWN maker submission may already be resting. Resolving it by the client ID the
        // caller owns is the only safe move: retrying would place a second order, and treating it as
        // rejected would leave a live order nobody is tracking.
        if (submitted.outcome() == SubmitOutcome.UNKNOWN) {
            submitted = resolve(makerLeg, clientId, submitted);
        }
        if (submitted.outcome() == SubmitOutcome.REJECTED) {
            byClientId.remove(clientId);
            System.out.printf("%s maker rejected: %s%n", pair.base(), submitted.detail());
            // A rejection is evidence that THIS order did not rest. It is NOT evidence that nothing
            // filled, and the two are easy to confuse: a venue also rejects a reduce-only order once
            // the position is already flat, which is exactly what a filled-but-unreported maker looks
            // like. Measured live 2026-08-20 on CASHCAT - the Hyperliquid maker filled in full at
            // 19:00:58, no stream event ever arrived, the chase re-placement came back "reduce only
            // order would increase position", and the pair was recorded ABANDONED while the Bybit leg
            // sat naked. ABANDONED means "no exposure was taken", it is terminal, and outstanding()
            // does not report it - so the one position that needed a human was the one nothing
            // mentioned. Ask the venue what it holds before trusting our own records.
            if (reconcileMissedFill(pair)) {
                return false;
            }
            // A rejection on a CHASE re-placement, after some quantity from an earlier order has
            // already filled and hedged, must not claim ABANDONED either, for the same reason.
            //
            // Nor may a CLOSING pair ever claim ABANDONED here, regardless of how much has filled -
            // see close()'s own javadoc: giving up is not available for an exit, only cancel-then-cross
            // at the deadline. shouldKeepChasing() is what keeps a closing pair's chase timer alive
            // past this rejection so that deadline is actually reached instead of the pair rotting in
            // WORKING with nothing left scheduled to look at it again.
            if (!pair.closing() && pair.totalFilled().signum() == 0) {
                pair.state().set(PairState.ABANDONED);
            }
            return false;
        }
        pair.makerHandle().set(submitted.handle());
        return true;
    }

    /**
     * Asks the maker venue what it actually holds, and hedges anything that filled without the stream
     * ever saying so. Returns true when such a fill was found.
     *
     * <p>The engine's own records come from the order-update stream, so they are exactly as complete
     * as that stream was. The venue's position is the one account that cannot have missed a fill,
     * which is why this asks rather than infers - the same reasoning {@code XvfReconciler} is built
     * on, applied to the one moment there is concrete evidence of a disagreement.
     *
     * <p>Position is compared in maker units and in the direction the pair is travelling: an exit
     * should still hold {@code quantity - filled}, an entry should hold exactly what has filled so
     * far. Either way a position smaller (exit) or larger (entry) than that means fills landed
     * unseen, and the difference is what the taker side never hedged.
     */
    private boolean reconcileMissedFill(Pair pair) {
        Leg makerLeg = pair.maker();
        BigDecimal actual = BigDecimal.ZERO;
        try {
            for (VenueGateway.PositionSnapshot p : makerLeg.gateway().positions()) {
                if (p.venueSymbol().equals(makerLeg.venueSymbol())) {
                    actual = p.signedQuantity().abs();
                }
            }
        } catch (RuntimeException e) {
            // Cannot verify, so cannot rule a missed fill out. Say so rather than letting the caller
            // read "false" as "the venue agrees".
            System.out.printf("!! %s could not read %s positions to check the rejection against the "
                    + "venue: %s — if a fill was missed it will not be reported%n",
                    pair.base(), makerLeg.gateway().name(), e.getMessage());
            return false;
        }
        BigDecimal known = pair.totalFilled();
        BigDecimal expected = pair.closing() ? makerLeg.quantity().subtract(known) : known;
        BigDecimal unaccounted = pair.closing() ? expected.subtract(actual) : actual.subtract(expected);
        if (unaccounted.signum() <= 0) {
            return false;   // the venue agrees with our records; the rejection meant what it said
        }

        System.out.printf("!! %s %s holds %s where this engine expected %s — %s filled with no stream "
                + "event. Hedging it now rather than recording the pair as abandoned.%n",
                pair.base(), makerLeg.gateway().name(), actual.toPlainString(),
                expected.toPlainString(), unaccounted.toPlainString());
        pair.state().compareAndSet(PairState.WORKING, PairState.MAKER_FILLED);
        BigDecimal quantity;
        synchronized (pair) {
            // Recorded under its own key so it adds to the pair's total exactly once, and so a later
            // stream event for a real order cannot overwrite it.
            pair.orderFilled().put("xvfpolled-" + System.nanoTime(), unaccounted);
            quantity = reserveHedge(pair);
        }
        if (quantity.signum() > 0) {
            hedge(pair, quantity);
        }
        return true;
    }

    private void scheduleChase(Pair pair, long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            finalizeDeadline(pair);
            return;
        }
        long delay = Math.min(chaseEvery.toNanos(), remaining);
        timers.schedule(() -> chase(pair, deadlineNanos), delay, TimeUnit.NANOSECONDS);
    }

    /**
     * Cancels whatever is resting and re-places the unfilled remainder at the current touch. Runs on
     * a timer rather than waiting once, because the alternative is a resting order that can wait
     * forever if the market has moved away from it - measured live, over three minutes with no fill.
     */
    private void chase(Pair pair, long deadlineNanos) {
        if (isTerminal(pair.state().get())) {
            // Resolved since the last check, so nothing to chase - but something may still be
            // RESTING. Returning bare here was how a written-off pair kept a live order: this is
            // the last timer that will ever fire for it, so leaving without cancelling leaves the
            // order with nothing scheduled to clean it up. HEDGED and ABANDONED have nothing
            // resting by construction and the cancel is a no-op for them.
            cancelResting(pair);
            return;
        }
        if (System.nanoTime() >= deadlineNanos) {
            finalizeDeadline(pair);
            return;
        }

        OrderHandle current = pair.makerHandle().get();
        if (current != null) {
            pair.maker().gateway().cancel(current);
            // The cancel can race a fill landing at the venue right now. That fill still reaches
            // onOrderUpdate() through the OLD client id, which stays registered, so it hedges
            // exactly as it would without a chase - the EXPOSURE is safe either way. What is not
            // safe is sizing the replacement from a watermark the racing fill has not reached yet,
            // because then the replacement covers quantity the venue has already filled and the
            // position comes out oversized. Measured live 2026-08-20 on SLP: one order for 148,490
            // filled 26,570 and then 114,940 in the same second, the chase saw only the first, and
            // it placed a replacement for exactly 148,490 - 26,570 = 121,920, which also filled.
            // The pair ended hedged but 75% too large, $149 a leg against a target of $85. So ask
            // the venue what that order actually did before deciding what is left.
            adoptVenueFill(pair, current);
        }
        // Read state AFTER the cancel and the reconcile, not before: either may have resolved it.
        if (isTerminal(pair.state().get())) {
            return;
        }

        BigDecimal remaining = pair.maker().quantity().subtract(pair.totalFilled());
        if (remaining.signum() <= 0) {
            return;   // fully filled; the stream already hedged it, nothing left to chase
        }
        BigDecimal freshPrice = pair.maker().gateway()
                .topOfBook(pair.maker().venueSymbol()).touch(pair.maker().side());
        boolean placedOk = placeMaker(pair, remaining, freshPrice);
        // For an ENTRY, a rejection here is final: the rejection is already logged above, and
        // re-placing forever past an explicit answer is not chasing a price, it is ignoring the venue.
        // For a CLOSING pair, shouldKeepChasing() overrides that and keeps a timer alive regardless -
        // giving up is not available for an exit, only cancel-then-cross once finalizeDeadline takes
        // over. See shouldKeepChasing()'s javadoc for the live incident this distinction came from.
        if (shouldKeepChasing(pair, placedOk)) {
            scheduleChase(pair, deadlineNanos);
        }
    }

    /**
     * Raises a cancelled order's watermark to whatever the venue says it actually filled, and hedges
     * the difference.
     *
     * <p>The watermark is fed by the order-update stream, so between a fill landing and its event
     * arriving the engine's figure is legitimately behind. That gap is harmless for exposure - the
     * event still hedges when it turns up - but it is not harmless for sizing a replacement order,
     * which is why this runs before the remainder is computed rather than being left to the stream.
     *
     * <p>Writes under the same client id the stream uses, so a late event for that order sees a
     * watermark already at or above its own cumulative and correctly adds nothing.
     */
    private void adoptVenueFill(Pair pair, OrderHandle handle) {
        Leg makerLeg = pair.maker();
        BigDecimal venueFilled;
        try {
            Optional<OrderSnapshot> snapshot =
                    makerLeg.gateway().orderByClientId(handle.venueSymbol(), handle.clientOrderId());
            if (snapshot.isEmpty()) {
                return;
            }
            venueFilled = snapshot.get().filledQuantity();
        } catch (RuntimeException e) {
            // Cannot confirm, so do not guess. Leaving the watermark alone keeps the old behaviour:
            // the replacement may overlap, which the imbalance check downstream can still catch.
            System.out.printf("!! %s could not read the cancelled maker back from %s: %s%n",
                    pair.base(), makerLeg.gateway().name(), e.getMessage());
            return;
        }
        if (venueFilled == null || venueFilled.signum() <= 0) {
            return;
        }
        BigDecimal quantity;
        synchronized (pair) {
            BigDecimal known = pair.orderFilled().getOrDefault(handle.clientOrderId(), BigDecimal.ZERO);
            if (venueFilled.compareTo(known) <= 0) {
                return;   // the stream was already level with the venue
            }
            System.out.printf("!! %s cancelled maker had filled %s on %s, the stream had reported %s "
                    + "- adopting the venue's figure before re-sizing%n",
                    pair.base(), venueFilled.toPlainString(), makerLeg.gateway().name(),
                    known.toPlainString());
            pair.orderFilled().put(handle.clientOrderId(), venueFilled);
            quantity = reserveHedge(pair);
        }
        pair.state().compareAndSet(PairState.WORKING, PairState.MAKER_FILLED);
        if (quantity.signum() > 0) {
            hedge(pair, quantity);
        }
    }

    /**
     * True when this quantity is worth too little for the venue to accept.
     *
     * <p>Priced off the touch rather than a stored entry price: the minimum is checked against what
     * the order would be worth when it is sent. A quote failure is treated as "not below" so a
     * transient price problem never silently withholds a hedge - the wrong direction to fail in.
     */
    private static boolean belowMinNotional(Leg taker, BigDecimal quantity,
                                            VenueGateway.SymbolRules rules) {
        if (rules.minNotionalUsd() == null || rules.minNotionalUsd().signum() <= 0) {
            return false;
        }
        try {
            BigDecimal price = taker.gateway().topOfBook(taker.venueSymbol()).touch(taker.side());
            return price.signum() > 0
                    && quantity.multiply(price).compareTo(rules.minNotionalUsd()) < 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean isTerminal(PairState s) {
        return s == PairState.ABANDONED || s == PairState.HEDGED || s == PairState.UNHEDGED_ALERT;
    }

    /**
     * Cancels whatever maker order is still resting for this pair, and forgets the handle so a
     * second call does nothing.
     *
     * <p>Failures are swallowed on purpose: every reason a cancel fails here - already filled,
     * already cancelled, unknown to the venue - means nothing is resting, which is the outcome being
     * asked for. Throwing would abort a shutdown path whose whole job is to leave nothing behind.
     */
    private void cancelResting(Pair pair) {
        OrderHandle current = pair.makerHandle().getAndSet(null);
        if (current == null) {
            return;
        }
        try {
            pair.maker().gateway().cancel(current);
        } catch (RuntimeException e) {
            System.out.printf("   %s maker cancel returned \"%s\" - already filled or gone%n",
                    pair.base(), e.getMessage());
        }
    }

    /** Deadline reached: stop resting, and for an exit only, cross whatever is still open. */
    private void finalizeDeadline(Pair pair) {
        boolean stopped = pair.state().compareAndSet(PairState.WORKING, PairState.ABANDONED)
                || pair.state().compareAndSet(PairState.MAKER_FILLED, PairState.ABANDONED);
        if (!stopped) {
            // Already terminal. HEDGED and ABANDONED have nothing resting by construction, but
            // UNHEDGED_ALERT does: it is set from the hedge path, which never touches the maker
            // order, and every other route out of here is short-circuited by isTerminal(). Measured
            // live 2026-08-20 - a BNT maker for 281 kept resting after the pair was written off,
            // kept filling in ones, and each fill was too small to hedge, so the imbalance grew
            // with nothing watching it. Cancel it: the pair is not coming back, and an order nobody
            // is tracking is exactly what the abandon path exists to prevent.
            cancelResting(pair);
            return;
        }
        cancelResting(pair);
        if (!pair.closing()) {
            System.out.printf("%s abandoned: maker never filled within %s%n", pair.base(), abandonAfter);
            return;
        }
        // Closing, so giving up is not available: the position is still open and the caller has
        // been told it is going away. Cancel, then cross. The cancel can race a fill, so the
        // quantity crossed is whatever the venue still shows rather than what was asked for -
        // crossing the original size after a partial fill would open a position the other way,
        // except that reduce-only stops it, which is the second reason this is reduce-only.
        System.out.printf("%s exit maker never filled within %s — crossing instead%n",
                pair.base(), abandonAfter);
        crossToClose(pair, pair.maker());
    }

    /** Last resort for an exit: take the spread rather than leave a position the book has dropped. */
    private void crossToClose(Pair pair, Leg makerLeg) {
        BigDecimal remaining = BigDecimal.ZERO;
        for (VenueGateway.PositionSnapshot p : makerLeg.gateway().positions()) {
            if (p.venueSymbol().equals(makerLeg.venueSymbol())) {
                remaining = p.signedQuantity().abs();
            }
        }
        BigDecimal quantity = round(remaining.min(makerLeg.quantity()),
                makerLeg.gateway().rules(makerLeg.venueSymbol()).stepSize());
        if (quantity.signum() <= 0) {
            return;   // already flat; the cancel won or the fill did
        }
        String crossId = "xvfxc-" + pair.base() + "-" + System.nanoTime();
        SubmitResult crossed = makerLeg.gateway().placeCappedIoc(makerLeg.venueSymbol(),
                makerLeg.side(), quantity, worstAcceptable(makerLeg), crossId, true);
        if (crossed.accepted()) {
            System.out.printf("%s exit crossed %s on %s%n",
                    pair.base(), quantity, makerLeg.gateway().name());
        } else {
            System.out.printf("!!!! %s exit could NOT be crossed: %s — the pair is still open%n",
                    pair.base(), crossed.detail());
        }
    }

    /**
     * Wire this to every gateway's {@code streamOrderUpdates}.
     *
     * <p>Partial fills hedge immediately rather than waiting for the remainder: a half-filled maker
     * leg is half-naked, and the exposure is what matters, not the tidiness of the fill.
     *
     * <h2>Only the INCREMENT is hedged, and only within the ORDER it came from</h2>
     * {@link OrderUpdate#filledQuantity()} is <b>cumulative for that one order</b> - Binance's
     * {@code z} field is the running total for the order, not the size of the latest fill, and not a
     * running total across every order this pair has ever rested. A chase cancels and replaces, and
     * the replacement's cumulative starts at zero again; differencing against a single pair-wide
     * watermark would read a fresh order's small cumulative as LESS than an earlier order's
     * high-water mark and silently drop the increment. So the watermark lives per client id, and the
     * pair's total is the sum of every order's own watermark - see the class Javadoc.
     *
     * <p>Differencing a cumulative total is deliberately preferred to reading a per-event increment
     * ({@code l} on Binance). A dropped or duplicated message leaves the cumulative figure
     * self-correcting on the next event, where summing increments would be permanently wrong.
     *
     * <p>The watermark advances before the hedge is attempted. That direction is chosen on purpose:
     * a failed hedge under-hedges and raises {@link PairState#UNHEDGED_ALERT}, which a human sees,
     * while the alternative risks a silent double hedge, which nobody sees.
     */
    public void onOrderUpdate(OrderUpdate update) {
        Pair pair = byClientId.get(update.clientOrderId());
        if (pair == null) {
            return; // not ours
        }
        boolean filled = update.state() == OrderState.FILLED
                || update.state() == OrderState.PARTIALLY_FILLED;
        if (!filled || update.filledQuantity().signum() <= 0) {
            return;
        }
        pair.state().compareAndSet(PairState.WORKING, PairState.MAKER_FILLED);

        BigDecimal quantity;
        // Serialised per pair: two updates racing here would each read the same watermarks and both
        // send the same hedge.
        synchronized (pair) {
            BigDecimal previous = pair.orderFilled().getOrDefault(update.clientOrderId(), BigDecimal.ZERO);
            if (update.filledQuantity().compareTo(previous) <= 0) {
                return;  // duplicate or out-of-order event for THIS order; nothing new is exposed
            }
            pair.orderFilled().put(update.clientOrderId(), update.filledQuantity());
            quantity = reserveHedge(pair);
        }
        if (quantity.signum() <= 0) {
            return;  // what is newly owed still rounds below one taker step; the next fill sweeps it
        }
        hedge(pair, quantity);
    }

    /**
     * How much the taker side still owes, in taker units, rounded down to a whole step and reserved
     * against {@link Pair#hedged()} so a concurrent update cannot send it twice. Call this holding
     * the pair's monitor.
     *
     * <p><b>Computed from the pair's TOTAL maker fill, never from one increment.</b> Rounding each
     * increment down on its own discards a fraction of a step every time, and a chased maker delivers
     * many increments - so the shortfall accumulates instead of cancelling out. Measured live
     * 2026-08-20 during a full close: TRUTH filled in four increments and left 2 units unhedged on
     * Binance, WAL in four and left 3, COTI 2, GRIFFAIN 1, BLUAI 1, BMT 1. Every one of those was a
     * whole step or more - closeable, not dust - and each was a small naked position left behind by
     * an exit that reported success. Differencing against the running total carries every remainder
     * into the next hedge, so the taker side can end at most one part-step short rather than one per
     * fill event.
     */
    private BigDecimal reserveHedge(Pair pair) {
        Leg taker = pair.taker();
        VenueGateway.SymbolRules rules = taker.gateway().rules(taker.venueSymbol());
        BigDecimal owed = pair.totalFilled().multiply(pair.hedgeRatio()).subtract(pair.hedged().get());
        BigDecimal quantity = round(owed, rules.stepSize());
        // Below the venue's minimum notional the order cannot be sent at all - Binance answers -4164,
        // "notional must be no smaller than 5". Withholding it here is the same move as withholding a
        // sub-step quantity: nothing is reserved, so the amount stays owed and the next fill sweeps it
        // up in a single larger order. Sending it instead burns five retries and then raises
        // UNHEDGED_ALERT for what is really a $2 rounding tail - measured live 2026-08-20, where a
        // 108-unit ESPORTS partial worth $1.73 and a 1-unit BNT partial worth $0.30 each produced a
        // false alert on a pair that went on to hedge correctly moments later.
        if (quantity.signum() > 0 && belowMinNotional(taker, quantity, rules)) {
            return BigDecimal.ZERO;
        }
        if (quantity.signum() > 0) {
            // Reserved BEFORE the order is sent, matching the fill watermark's own ordering: a failed
            // hedge under-hedges and raises UNHEDGED_ALERT, which a human sees, while the reverse
            // risks a silent double hedge, which nobody sees.
            pair.hedged().set(pair.hedged().get().add(quantity));
        }
        return quantity;
    }

    /**
     * Sends one offsetting order, for a quantity {@link #reserveHedge} has already converted to taker
     * units, rounded to a step and reserved. Retries rather than giving up: giving up leaves the
     * exposure naked.
     *
     * @param quantity taker units to send, always a whole number of steps and always greater than zero
     */
    private void hedge(Pair pair, BigDecimal quantity) {
        Leg taker = pair.taker();
        for (int attempt = 1; attempt <= 5; attempt++) {
            String hedgeId = "xvfh-" + pair.base() + "-" + System.nanoTime();
            try {
                // Capped IOC, never an unbounded market order: crossing is intended, crossing at any
                // price is not, and this hedges into a coin selected for being dislocated.
                BigDecimal worst = worstAcceptable(taker);
                SubmitResult result = taker.gateway().placeCappedIoc(
                        taker.venueSymbol(), taker.side(), quantity, worst, hedgeId, pair.closing());
                if (result.outcome() == SubmitOutcome.UNKNOWN) {
                    result = resolve(taker, hedgeId, result);
                }
                if (result.accepted()) {
                    // HEDGED is a terminal state, and a chased maker can deliver several increments
                    // over its life - RAVE hedged five times in one live run. Marking HEDGED on the
                    // first of those would tell allResolved() this pair is done while four more
                    // fills are still coming, which is the same premature-exit risk the 2-second
                    // sleep fix removed, reappearing through a different door. Only the increment
                    // that brings the running total up to the full target quantity may close it out.
                    boolean fullyFilled = pair.totalFilled().compareTo(pair.maker().quantity()) >= 0;
                    if (fullyFilled) {
                        pair.state().compareAndSet(PairState.MAKER_FILLED, PairState.HEDGED);
                    }
                    System.out.printf("%s hedged: %s %s %s on %s (cap %s)%s%n", pair.base(),
                            taker.side(), quantity, taker.venueSymbol(),
                            taker.gateway().name(), worst.toPlainString(),
                            fullyFilled ? "" : " (partial - more may still fill)");
                    return;
                }
                System.out.printf("!! %s hedge attempt %d not accepted: %s%n",
                        pair.base(), attempt, result.detail());
            } catch (RuntimeException e) {
                System.out.printf("!! %s hedge attempt %d failed: %s%n", pair.base(), attempt, e.getMessage());
            }
            sleep(200L * attempt);
        }
        // Deliberately loud and deliberately not self-healing. An unhedged leg is the one state that
        // must reach a human; silently retrying forever would hide it.
        pair.state().set(PairState.UNHEDGED_ALERT);
        System.out.printf("!!!! %s UNHEDGED — %s %s on %s FAILED against a filled maker on %s. "
                + "Close manually or the position is directional.%n",
                pair.base(), taker.side(), quantity, taker.gateway().name(),
                pair.maker().gateway().name());
        // Cancelled here, not left for the next deadline check: the maker leg is still resting and
        // the market can keep filling it for as long as abandonAfter allows (up to 30 minutes) if
        // nothing stops it, each fill adding to an exposure already proven unhedgeable. Measured live
        // on CASHCAT, 2026-08-22: the maker grew past its original target while five hedge attempts
        // failed on the taker's side, because nothing cancelled it until the unrelated deadline path
        // eventually did. A repeated hedge failure is the same signal an abandon deadline is - stop
        // resting now rather than waiting for a timer to notice the same thing later.
        cancelResting(pair);
    }

    /**
     * True once every registered pair has stopped changing on its own - hedged, abandoned, or given
     * up on after retries. WORKING and MAKER_FILLED are not terminal: an order is still resting (or
     * being chased), or a fill has happened and the hedge is still in flight.
     *
     * <p>This is what a caller should wait for before doing anything that stops the stream from
     * being heard - closing the process, tearing down the WebSocket listeners, anything. A caller
     * that stops listening while a pair is still WORKING is betting that no fill arrives after it
     * stops watching, which is exactly the bet a resting order is designed to lose eventually.
     */
    public boolean allResolved() {
        return unresolvedCount() == 0;
    }

    /** How many pairs are still WORKING or MAKER_FILLED - resting/being chased, or filled and hedging. */
    public long unresolvedCount() {
        return allPairs.stream()
                .map(p -> p.state().get())
                .filter(s -> !isTerminal(s))
                .count();
    }

    /** Positions still exposed. Poll this; do not trust the stream to have told you everything. */
    public Map<String, PairState> outstanding() {
        Map<String, PairState> out = new java.util.HashMap<>();
        for (Pair p : allPairs) {
            PairState s = p.state().get();
            if (s == PairState.MAKER_FILLED || s == PairState.UNHEDGED_ALERT) {
                out.put(p.base(), s);
            }
        }
        return out;
    }

    /**
     * Turns an UNKNOWN submission into a definite one by asking the venue about the caller's own ID.
     *
     * <p>An empty answer means the venue never saw the order, which is a genuine rejection. Anything
     * else means it exists and must be tracked, whatever the network said.
     */
    private static SubmitResult resolve(Leg leg, String clientOrderId, SubmitResult unknown) {
        try {
            Optional<OrderSnapshot> found =
                    leg.gateway().orderByClientId(leg.venueSymbol(), clientOrderId);
            if (found.isEmpty()) {
                return new SubmitResult(SubmitOutcome.REJECTED, unknown.handle(),
                        "resolved: venue never saw " + clientOrderId);
            }
            return new SubmitResult(SubmitOutcome.ACCEPTED, found.get().handle(),
                    "resolved: " + found.get().state());
        } catch (RuntimeException e) {
            // Still ambiguous. Report it as such rather than guessing in either direction.
            System.out.printf("!!!! could not resolve %s on %s: %s — an order may be live and "
                    + "untracked%n", clientOrderId, leg.gateway().name(), e.getMessage());
            return unknown;
        }
    }

    /** Worst price the crossing leg may print, from the touch plus the configured slippage cap. */
    private static BigDecimal worstAcceptable(Leg taker) {
        VenueGateway.TopOfBook book = taker.gateway().topOfBook(taker.venueSymbol());
        BigDecimal cross = taker.side() == Side.BUY ? book.ask() : book.bid();
        BigDecimal slip = cross.multiply(BigDecimal.valueOf(XvfConfig.MAX_TAKER_SLIPPAGE_BPS / 10_000.0));
        return taker.side() == Side.BUY ? cross.add(slip) : cross.subtract(slip);
    }

    private static BigDecimal round(BigDecimal quantity, BigDecimal step) {
        if (step == null || step.signum() <= 0) {
            return quantity;
        }
        return quantity.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        timers.shutdownNow();
        // The timers were the only thing that would ever have cancelled a resting order, so once
        // they are gone anything still resting is unmonitored: it can fill after the process exits,
        // with no listener to hedge it and no record that it happened. Cancelling here is the last
        // chance to stop that, and it is a no-op for every pair that ended cleanly.
        for (Pair pair : allPairs) {
            cancelResting(pair);
        }
    }
}
