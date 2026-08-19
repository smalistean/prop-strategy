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
    public void open(String base, Leg makerLeg, Leg takerLeg, BigDecimal makerLimit) {
        work(base, makerLeg, takerLeg, makerLimit, false);
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

    private void work(String base, Leg makerLeg, Leg takerLeg, BigDecimal makerLimit, boolean closing) {
        // Taker units per maker unit. The two venues may quote different contract sizes for the
        // same asset - 1000PEPE against PEPE - so hedging the maker's NATIVE filled quantity on the
        // taker venue would be out by that multiple. The caller has already sized both legs to equal
        // USD notional, so their ratio is exactly the conversion needed.
        BigDecimal hedgeRatio = takerLeg.quantity().divide(makerLeg.quantity(), 12, RoundingMode.HALF_UP);
        Pair pair = new Pair(base, makerLeg, takerLeg,
                new AtomicReference<>(PairState.WORKING), Instant.now(), new AtomicReference<>(),
                new ConcurrentHashMap<>(), hedgeRatio, closing);
        allPairs.add(pair);
        long deadline = System.nanoTime() + abandonAfter.toNanos();
        if (placeMaker(pair, makerLeg.quantity(), makerLimit)) {
            scheduleChase(pair, deadline);
        }
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
            // A rejection on the very first placement means nothing was ever taken - harmless,
            // skip the position. A rejection on a CHASE re-placement, after some quantity from an
            // earlier order has already filled and hedged, must not claim ABANDONED: that state
            // means "no exposure was taken," which would no longer be true.
            if (pair.totalFilled().signum() == 0) {
                pair.state().set(PairState.ABANDONED);
            }
            System.out.printf("%s maker rejected: %s%n", pair.base(), submitted.detail());
            return false;
        }
        pair.makerHandle().set(submitted.handle());
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
            return;   // resolved by the stream since the last check; nothing to chase
        }
        if (System.nanoTime() >= deadlineNanos) {
            finalizeDeadline(pair);
            return;
        }

        OrderHandle current = pair.makerHandle().get();
        if (current != null) {
            pair.maker().gateway().cancel(current);
        }
        // The cancel can race a fill landing at the venue right now. That fill still reaches
        // onOrderUpdate() through the OLD client id, which stays registered, so it hedges exactly as
        // it would without a chase. This re-check only decides whether to place a NEW order for
        // whatever is left, so it must read state AFTER the cancel, not trust what it was before.
        if (isTerminal(pair.state().get())) {
            return;
        }

        BigDecimal remaining = pair.maker().quantity().subtract(pair.totalFilled());
        if (remaining.signum() <= 0) {
            return;   // fully filled; the stream already hedged it, nothing left to chase
        }
        BigDecimal freshPrice = pair.maker().gateway()
                .topOfBook(pair.maker().venueSymbol()).touch(pair.maker().side());
        if (placeMaker(pair, remaining, freshPrice)) {
            scheduleChase(pair, deadlineNanos);
        }
        // If placeMaker returned false, the rejection is already logged above; re-placing forever
        // past an explicit rejection is not chasing a price, it is ignoring a venue's answer.
    }

    private static boolean isTerminal(PairState s) {
        return s == PairState.ABANDONED || s == PairState.HEDGED || s == PairState.UNHEDGED_ALERT;
    }

    /** Deadline reached: stop resting, and for an exit only, cross whatever is still open. */
    private void finalizeDeadline(Pair pair) {
        boolean stopped = pair.state().compareAndSet(PairState.WORKING, PairState.ABANDONED)
                || pair.state().compareAndSet(PairState.MAKER_FILLED, PairState.ABANDONED);
        if (!stopped) {
            return;   // already resolved by the stream
        }
        OrderHandle current = pair.makerHandle().get();
        if (current != null) {
            pair.maker().gateway().cancel(current);
        }
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

        BigDecimal increment;
        // Serialised per pair: two updates racing here would each read the same watermark and both
        // hedge the same increment.
        synchronized (pair) {
            BigDecimal previous = pair.orderFilled().getOrDefault(update.clientOrderId(), BigDecimal.ZERO);
            increment = update.filledQuantity().subtract(previous);
            if (increment.signum() <= 0) {
                return;  // duplicate or out-of-order event for THIS order; nothing new is exposed
            }
            pair.orderFilled().put(update.clientOrderId(), update.filledQuantity());
        }
        hedge(pair, update.clientOrderId(), increment);
    }

    /**
     * Sends the offsetting order for ONE increment of maker fill. Retries rather than giving up:
     * giving up leaves the increment naked.
     *
     * @param sourceClientId the order this increment came from, needed only to roll back that
     *                       order's watermark if the increment rounds to nothing on the taker side
     * @param makerFilled    the newly filled amount in MAKER units, already differenced from that
     *                       order's own cumulative. Converted to taker units by {@code hedgeRatio}.
     */
    private void hedge(Pair pair, String sourceClientId, BigDecimal makerFilled) {
        Leg taker = pair.taker();
        // Convert maker units to taker units BEFORE rounding. Hedging makerFilled directly was
        // correct only when both venues quote the same contract size, which 3.6% of historical
        // selections do not.
        BigDecimal quantity = round(makerFilled.multiply(pair.hedgeRatio()),
                taker.gateway().rules(taker.venueSymbol()).stepSize());
        if (quantity.signum() <= 0) {
            // The converted increment rounded below one taker step. Leaving the watermark advanced
            // would strand it, so hand it back to the SAME order's watermark to be swept up with
            // that order's next fill.
            synchronized (pair) {
                pair.orderFilled().computeIfPresent(sourceClientId, (id, v) -> v.subtract(makerFilled));
            }
            return;
        }
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
        System.out.printf("!!!! %s UNHEDGED — maker filled %s on %s, hedge on %s FAILED. "
                + "Close manually or the position is directional.%n",
                pair.base(), makerFilled, pair.maker().gateway().name(), taker.gateway().name());
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
    }
}
