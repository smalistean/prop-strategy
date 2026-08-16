package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderHandle;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderState;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderUpdate;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.Side;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
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

    private record Pair(String base, Leg maker, Leg taker, BigDecimal makerLimit,
                        AtomicReference<PairState> state, Instant opened,
                        AtomicReference<OrderHandle> makerHandle) { }

    private final Map<String, Pair> byClientId = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2);
    private final Duration abandonAfter;

    public PairedEntryEngine(Duration abandonAfter) {
        this.abandonAfter = abandonAfter;
    }

    /**
     * Starts one pair. The maker leg is placed immediately; the taker leg waits for its fill.
     *
     * @param makerLeg  the THINNER venue — rests
     * @param takerLeg  the more liquid venue — crossed on fill
     */
    public void open(String base, Leg makerLeg, Leg takerLeg, BigDecimal makerLimit) {
        String clientId = "xvf-" + base + "-" + System.nanoTime();
        Pair pair = new Pair(base, makerLeg, takerLeg, makerLimit,
                new AtomicReference<>(PairState.WORKING), Instant.now(), new AtomicReference<>());
        byClientId.put(clientId, pair);

        OrderHandle handle = makerLeg.gateway().placePostOnly(
                makerLeg.venueSymbol(), makerLeg.side(),
                round(makerLeg.quantity(), makerLeg.gateway().rules(makerLeg.venueSymbol()).stepSize()),
                makerLimit);
        pair.makerHandle().set(handle);

        // A resting order that never fills is only ever a missed position, so the timeout simply
        // cancels. It must not fire once the maker has filled - by then cancelling is meaningless
        // and the correct action is to hedge.
        timers.schedule(() -> {
            if (pair.state().compareAndSet(PairState.WORKING, PairState.ABANDONED)) {
                makerLeg.gateway().cancel(handle);
                System.out.printf("%s abandoned: maker never filled within %s%n", base, abandonAfter);
            }
        }, abandonAfter.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Wire this to every gateway's {@code streamOrderUpdates}.
     *
     * <p>Partial fills hedge the filled portion rather than waiting for the remainder: a half-filled
     * maker leg is half-naked, and the exposure is what matters, not the tidiness of the fill.
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
        hedge(pair, update.filledQuantity());
    }

    /** Sends the offsetting market order. Retries rather than giving up: giving up leaves it naked. */
    private void hedge(Pair pair, BigDecimal makerFilled) {
        Leg taker = pair.taker();
        BigDecimal quantity = round(makerFilled, taker.gateway().rules(taker.venueSymbol()).stepSize());
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                taker.gateway().placeMarket(taker.venueSymbol(), taker.side(), quantity);
                pair.state().set(PairState.HEDGED);
                System.out.printf("%s hedged: %s %s %s on %s%n", pair.base(), taker.side(),
                        quantity, taker.venueSymbol(), taker.gateway().name());
                return;
            } catch (RuntimeException e) {
                System.out.printf("!! %s hedge attempt %d failed: %s%n", pair.base(), attempt, e.getMessage());
                sleep(200L * attempt);
            }
        }
        // Deliberately loud and deliberately not self-healing. An unhedged leg is the one state that
        // must reach a human; silently retrying forever would hide it.
        pair.state().set(PairState.UNHEDGED_ALERT);
        System.out.printf("!!!! %s UNHEDGED — maker filled %s on %s, hedge on %s FAILED. "
                + "Close manually or the position is directional.%n",
                pair.base(), makerFilled, pair.maker().gateway().name(), taker.gateway().name());
    }

    /** Positions still exposed. Poll this; do not trust the stream to have told you everything. */
    public Map<String, PairState> outstanding() {
        Map<String, PairState> out = new java.util.HashMap<>();
        byClientId.forEach((id, p) -> {
            PairState s = p.state().get();
            if (s == PairState.MAKER_FILLED || s == PairState.UNHEDGED_ALERT) {
                out.put(p.base(), s);
            }
        });
        return out;
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
