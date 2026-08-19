package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.PositionSnapshot;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.Side;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitOutcome;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares the book against the accounts and closes the difference.
 *
 * <h2>Why a reconciler rather than an {@code exit()} call</h2>
 * {@link PairedEntryEngine} knows what it opened, in memory, for as long as the process lives. That
 * knowledge is worth nothing across a restart, and the one moment it matters most - a maker leg filled
 * and the hedge not yet placed - is exactly the moment a crash leaves a naked position nobody
 * remembers. The venues remember. So this reads {@link VenueGateway#positions()} rather than any local
 * record, which means it recovers state it never stored, and a stranded leg from a previous run looks
 * identical to one from this run.
 *
 * <p>The 3-day rebalance then stops being an event to catch. A new book lands, the previous pairs are
 * no longer desired, and the next pass closes them; nothing has to notice that a rebalance was due.
 *
 * <h2>It only ever reduces</h2>
 * The reconciler closes and trims. It never opens. Opening is a paired operation - a maker leg and its
 * hedge - and a reconciler that opens one leg because the book asked for it would create precisely the
 * directional position this exists to remove. Legs the book wants but the account lacks are reported
 * for {@code PairedEntryEngine} to fill, never opened here.
 *
 * <h2>Closing is sequenced by pair</h2>
 * Both legs of a base are closed back to back so the window in which one side is gone and the other
 * is not stays as short as the venues allow - about two seconds in live testing. That window cannot be
 * removed, only minimised: no venue can close another venue's position atomically. If the second leg
 * fails after the first succeeded, that is the loudest thing this class can say, because the account is
 * then directional and a human needs to act.
 */
public final class XvfReconciler {

    /** Below this, a difference is step-rounding residue rather than a position. */
    private static final BigDecimal DUST = new BigDecimal("0.0000001");

    /** One leg the book wants held. Short legs carry a negative quantity. */
    public record DesiredLeg(String base, String venue, String venueSymbol,
                             BigDecimal signedQuantity) { }

    /** A gap between book and account, with the order that closes it. */
    public record Drift(String base, String venue, String venueSymbol, BigDecimal actual,
                        BigDecimal desired, BigDecimal reduceBy, Reason reason) {

        /** The side that shrinks this position: a short is reduced by buying. */
        public Side closingSide() {
            return actual.signum() < 0 ? Side.BUY : Side.SELL;
        }
    }

    public enum Reason {
        /** Held but not in the book - the ordinary rebalance exit. */
        NOT_IN_BOOK,
        /** Held on the correct side but larger than the book wants. */
        OVERSIZED,
        /** Held on the opposite side to the book. Closed to flat; re-opening is the engine's job. */
        WRONG_SIDE,
        /** In the book but absent from the account. Reported only - never opened here. */
        MISSING
    }

    private XvfReconciler() {
    }

    /**
     * Works out what would have to change, without changing anything.
     *
     * <p>Separated from {@link #apply} so a scheduled run can be inspected, logged, or dry-run against
     * a live account without the possibility of sending an order.
     */
    public static List<Drift> plan(Map<String, VenueGateway> gateways, List<DesiredLeg> book) {
        Map<String, DesiredLeg> desired = new LinkedHashMap<>();
        for (DesiredLeg leg : book) {
            desired.merge(key(leg.venue(), leg.venueSymbol()), leg,
                    (a, b) -> new DesiredLeg(a.base(), a.venue(), a.venueSymbol(),
                            a.signedQuantity().add(b.signedQuantity())));
        }

        Map<String, PositionSnapshot> actual = new LinkedHashMap<>();
        for (Map.Entry<String, VenueGateway> entry : gateways.entrySet()) {
            for (PositionSnapshot p : entry.getValue().positions()) {
                actual.put(key(entry.getKey(), p.venueSymbol()), p);
            }
        }

        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(actual.keySet());
        keys.addAll(desired.keySet());

        List<Drift> drifts = new ArrayList<>();
        for (String k : keys) {
            PositionSnapshot held = actual.get(k);
            DesiredLeg want = desired.get(k);
            BigDecimal have = held == null ? BigDecimal.ZERO : held.signedQuantity();
            BigDecimal target = want == null ? BigDecimal.ZERO : want.signedQuantity();
            if (have.subtract(target).abs().compareTo(DUST) <= 0) {
                continue;
            }

            String venue = k.substring(0, k.indexOf('|'));
            String symbol = k.substring(k.indexOf('|') + 1);
            String base = want != null ? want.base() : XvfConfig.normaliseBase(venue, symbol);

            if (have.signum() == 0) {
                drifts.add(new Drift(base, venue, symbol, have, target, BigDecimal.ZERO, Reason.MISSING));
            } else if (target.signum() == 0) {
                drifts.add(new Drift(base, venue, symbol, have, target, have.abs(), Reason.NOT_IN_BOOK));
            } else if (have.signum() != target.signum()) {
                // Held the wrong way round. Close to flat rather than flipping in one order: a flip is
                // an open, and opening one leg is what this class exists to avoid.
                drifts.add(new Drift(base, venue, symbol, have, target, have.abs(), Reason.WRONG_SIDE));
            } else if (have.abs().compareTo(target.abs()) > 0) {
                drifts.add(new Drift(base, venue, symbol, have, target,
                        have.abs().subtract(target.abs()), Reason.OVERSIZED));
            } else {
                // Correct side, too small. Topping it up is an open; the engine does that.
                drifts.add(new Drift(base, venue, symbol, have, target, BigDecimal.ZERO, Reason.MISSING));
            }
        }
        // Pairs travel together, so both legs of a base are adjacent when applied.
        drifts.sort(Comparator.comparing(Drift::base).thenComparing(Drift::venue));
        return drifts;
    }

    /**
     * Sends the reducing orders. Returns true when every one of them succeeded.
     *
     * <p>Market-crosses rather than resting: this is a scheduled unwind, and an exit that might not
     * happen is worth less than the few basis points a post-only would have saved. That is also what
     * {@code XVF_V1_SCOPE.md} specifies for the rebalance exit.
     */
    public static boolean apply(Map<String, VenueGateway> gateways, List<Drift> drifts, boolean live) {
        boolean allDone = true;
        String currentBase = null;
        for (Drift d : drifts) {
            if (!d.base().equals(currentBase)) {
                currentBase = d.base();
                System.out.printf("  %s%n", currentBase);
            }
            if (d.reason() == Reason.MISSING) {
                System.out.printf("      %-12s %-10s book wants %s, account has %s — left for the "
                        + "entry engine; the reconciler never opens%n",
                        d.venue(), d.venueSymbol(), d.desired(), d.actual());
                continue;
            }
            System.out.printf("      %-12s %-10s %s: %s -> %s, %s %s%n", d.venue(), d.venueSymbol(),
                    d.reason(), d.actual(), d.desired(), d.closingSide(), d.reduceBy());
            if (!live) {
                continue;
            }
            if (!close(gateways.get(d.venue()), d)) {
                allDone = false;
                System.out.printf("!!!! %s %s DID NOT CLOSE — the account is directional until this is "
                        + "resolved by hand%n", d.venue(), d.venueSymbol());
            }
        }
        return allDone;
    }

    /** One reducing cross, capped so an empty book cannot fill it at any price. */
    private static boolean close(VenueGateway gateway, Drift drift) {
        if (gateway == null) {
            System.out.printf("!!!! no gateway for %s%n", drift.venue());
            return false;
        }
        Side side = drift.closingSide();
        BigDecimal quantity = floorToStep(drift.reduceBy(), gateway.rules(drift.venueSymbol()).stepSize());
        if (quantity.signum() <= 0) {
            return true;   // rounds below one step; nothing to do
        }
        for (int attempt = 1; attempt <= 3; attempt++) {
            VenueGateway.TopOfBook book = gateway.topOfBook(drift.venueSymbol());
            BigDecimal cross = side == Side.BUY ? book.ask() : book.bid();
            BigDecimal slip = cross.multiply(
                    BigDecimal.valueOf(XvfConfig.MAX_TAKER_SLIPPAGE_BPS / 10_000.0));
            BigDecimal worst = side == Side.BUY ? cross.add(slip) : cross.subtract(slip);
            String clientId = "xvfrec-" + drift.base() + "-" + System.nanoTime();

            SubmitResult result = gateway.placeCappedIoc(
                    drift.venueSymbol(), side, quantity, worst, clientId, true);
            if (result.outcome() == SubmitOutcome.UNKNOWN) {
                // Ambiguous submission: ask the venue rather than resending, which would double it.
                var seen = gateway.orderByClientId(drift.venueSymbol(), clientId);
                if (seen.isPresent() && seen.get().filledQuantity().signum() > 0) {
                    return true;
                }
            }
            if (result.accepted()) {
                // The position, not the order report, is the authority on whether this worked.
                BigDecimal remaining = held(gateway, drift.venueSymbol());
                if (remaining.abs().subtract(drift.desired().abs()).compareTo(DUST) <= 0) {
                    return true;
                }
                System.out.printf("      still holding %s after attempt %d%n", remaining, attempt);
            } else {
                System.out.printf("      close attempt %d not accepted: %s%n", attempt, result.detail());
            }
        }
        return false;
    }

    private static BigDecimal held(VenueGateway gateway, String venueSymbol) {
        for (PositionSnapshot p : gateway.positions()) {
            if (p.venueSymbol().equals(venueSymbol)) {
                return p.signedQuantity();
            }
        }
        return BigDecimal.ZERO;
    }

    private static BigDecimal floorToStep(BigDecimal quantity, BigDecimal step) {
        if (step == null || step.signum() <= 0) {
            return quantity;
        }
        return quantity.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    private static String key(String venue, String venueSymbol) {
        return venue + "|" + venueSymbol;
    }
}
