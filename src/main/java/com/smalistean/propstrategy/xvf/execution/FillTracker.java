package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderState;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderUpdate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Waits for a specific order to fill, across every venue's stream at once.
 *
 * <h2>Cumulative, not incremental</h2>
 * {@link OrderUpdate#filledQuantity()} is the venue's running total for that order — Binance's
 * {@code z}, Bybit's {@code cumExecQty}, and Hyperliquid's locally-summed {@code userFills}. This
 * stores it as-is and never adds updates together: adding them is the over-hedge bug that
 * {@code PairedEntryEngine} exists to avoid, and it produces 1x, 2x, 3x of a three-partial order.
 * Taking the maximum instead makes a duplicate or out-of-order redelivery a no-op by construction.
 *
 * <h2>Waiting is bounded by time, never by an event</h2>
 * A caller waits for a target quantity or a timeout, whichever comes first, and then reads what
 * actually arrived. Nothing here assumes the stream is alive: a silent stream and an unfilled order
 * are indistinguishable from the outside, so the caller must treat a zero return as "unknown, go and
 * check" rather than "definitely nothing happened". That is why every caller in
 * {@code XvfRoundTripTest} re-reads state after a cancel rather than trusting the cancel won.
 */
public final class FillTracker {

    private final Map<String, BigDecimal> filled = new ConcurrentHashMap<>();
    private final Map<String, Object> monitors = new ConcurrentHashMap<>();
    private final Map<String, Boolean> terminal = new ConcurrentHashMap<>();

    /** Registers interest BEFORE the order is placed, so an instant fill has somewhere to land. */
    public void expect(String clientOrderId) {
        filled.put(clientOrderId, BigDecimal.ZERO);
        terminal.put(clientOrderId, Boolean.FALSE);
        monitors.put(clientOrderId, new Object());
    }

    /** Wire this to every gateway's {@code streamOrderUpdates}. */
    public void onUpdate(OrderUpdate update) {
        String id = update.clientOrderId();
        Object monitor = monitors.get(id);
        if (monitor == null) {
            return;   // not one of ours
        }
        synchronized (monitor) {
            // max(), never sum(): see the class javadoc.
            filled.merge(id, update.filledQuantity(), BigDecimal::max);
            if (update.state() == OrderState.FILLED
                    || update.state() == OrderState.CANCELLED
                    || update.state() == OrderState.REJECTED) {
                terminal.put(id, Boolean.TRUE);
            }
            monitor.notifyAll();
        }
    }

    /**
     * Blocks until the order reaches {@code target}, reaches a terminal state, or times out.
     *
     * @return how much filled — possibly zero, possibly less than {@code target}
     */
    public BigDecimal awaitFill(String clientOrderId, BigDecimal target, Duration timeout)
            throws InterruptedException {
        Object monitor = monitors.get(clientOrderId);
        if (monitor == null) {
            return BigDecimal.ZERO;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (true) {
                BigDecimal now = filled.getOrDefault(clientOrderId, BigDecimal.ZERO);
                if (now.compareTo(target) >= 0 || Boolean.TRUE.equals(terminal.get(clientOrderId))) {
                    return now;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return now;
                }
                monitor.wait(Math.max(1, remaining / 1_000_000));
            }
        }
    }

    /** Latest known cumulative fill, without waiting. Used to re-check after a cancel. */
    public BigDecimal cumulative(String clientOrderId) {
        return filled.getOrDefault(clientOrderId, BigDecimal.ZERO);
    }
}
