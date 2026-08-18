package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.venue.BinanceGateway;
import com.smalistean.propstrategy.xvf.venue.BybitGateway;
import com.smalistean.propstrategy.xvf.venue.HyperliquidGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderHandle;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderSnapshot;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.PositionSnapshot;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.Side;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitOutcome;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Proves the full open-and-close cycle on real venues, one ordered pair at a time.
 *
 * <p>This is not the strategy. It trades one symbol at the smallest size the venues permit, purely to
 * establish that the maker-rests / fill-arrives / hedge-crosses / both-legs-close sequence works
 * against live exchanges in both directions for every venue combination. The strategy's own selection,
 * sizing and scheduling are all bypassed.
 *
 * <h2>What one pair does</h2>
 * <ol>
 *   <li><b>Open.</b> Post-only limit rests on the maker venue at its own touch. When the fill event
 *       arrives, a capped IOC crosses on the taker venue in the OPPOSITE direction.</li>
 *   <li><b>Verify.</b> Read positions from both venues and confirm they are opposite and matched.</li>
 *   <li><b>Close.</b> The mirror image: post-only reduce-only rests on the maker venue, and its fill
 *       triggers a capped IOC reduce-only on the taker.</li>
 *   <li><b>Verify flat.</b> Read positions again and confirm both venues are flat.</li>
 * </ol>
 *
 * <p>Six ordered pairs, because {@code (binance -> bybit)} and {@code (bybit -> binance)} exercise
 * different code: which venue rests and which crosses changes which gateway's stream must deliver the
 * fill and which gateway's IOC must price correctly.
 *
 * <h2>Why the maker chases</h2>
 * A post-only order resting at the touch fills only when the market comes to it, which for a test
 * means it might never fill. So an unfilled maker is cancelled and re-placed at the new touch, up to
 * {@code -DrtChases}. Cancelling can race a fill, so whatever filled before the cancel landed is
 * hedged rather than discarded - a partial fill is a valid outcome here and exercises the increment
 * hedging directly.
 *
 * <h2>Safety</h2>
 * Dry run is the default. {@code -DrtLive=false} must be explicitly overridden, and the notional is
 * capped: this is a plumbing test, and a plumbing test that can lose a meaningful amount of money has
 * been configured wrong. If a pair ends with exposure it could not unwind, the run STOPS rather than
 * continuing to the next pair - the first unhedged leg is the problem to solve, and opening five more
 * pairs on top of it would bury it.
 *
 * <h2>Usage</h2>
 * <pre>
 *   -DrtSymbol=ATOM        base asset, resolved per venue (default ATOM: $10/leg, the cheapest tier)
 *   -DrtNotional=12        USD per leg; must clear every venue's minimum, Hyperliquid's is $10
 *   -DrtLive=false         default; true actually trades
 *   -DrtPairs=all          or e.g. "binance:bybit,hyperliquid:binance"
 *   -DrtChases=6           how many times to re-place an unfilled maker
 *   -DrtChaseSeconds=20    how long to rest before re-placing
 *   -DrtCrossFirstLeg=true first leg crosses instead of resting — see {@code crossLeg}
 * </pre>
 *
 * <h2>Why {@code -DrtCrossFirstLeg=true} exists</h2>
 * Every liquid perpetual measured on Binance and Bybit quotes a <b>one-tick spread</b>, so a post-only
 * order can never improve the price and only ever joins the back of a queue. It may not fill for a very
 * long time, and re-placing it on a timer makes that worse by surrendering the queue position it just
 * built. A run where the maker never fills ends flat and safe but proves nothing. Crossing the first
 * leg fills immediately and exercises the chain that actually carries risk: fill event to stream, hedge
 * fired from that event, reduce-only closing both legs.
 */
public final class XvfRoundTripTest {

    /** A plumbing test that can lose real money has been configured wrong. */
    private static final double MAX_NOTIONAL_USD = 60;

    /** Every venue this harness knows how to gateway to, independent of which pairs get requested. */
    private static final List<String> KNOWN_VENUES = List.of("binance", "bybit", "hyperliquid");

    private record Venue(String name, String symbol, VenueGateway gateway) { }

    /** What one leg actually did, in that venue's own units. */
    private record Leg(Venue venue, Side side, BigDecimal filled, BigDecimal price) { }

    private XvfRoundTripTest() {
    }

    public static void main(String[] args) throws Exception {
        boolean live = Boolean.parseBoolean(System.getProperty("rtLive", "false"));
        String base = System.getProperty("rtSymbol", "ATOM");
        double notional = Double.parseDouble(System.getProperty("rtNotional", "12"));
        boolean crossFirst = Boolean.parseBoolean(System.getProperty("rtCrossFirstLeg", "false"));
        int chases = Integer.parseInt(System.getProperty("rtChases", "6"));
        Duration chaseFor = Duration.ofSeconds(Long.parseLong(
                System.getProperty("rtChaseSeconds", "20")));

        if (notional > MAX_NOTIONAL_USD) {
            throw new IllegalStateException(("rtNotional=%.0f exceeds the %.0f cap. This is a plumbing "
                    + "test; if it needs more than that, the symbol is wrong, not the cap.")
                    .formatted(notional, MAX_NOTIONAL_USD));
        }

        System.out.printf("XVF round-trip test — %s — %s — %.2f USD per leg%n",
                live ? "*** LIVE, REAL ORDERS ***" : "dry run", base, notional);
        if (!live) {
            System.out.println("  no orders will be sent. pass -DrtLive=true to trade for real.");
        }

        List<String[]> pairs = pairsToRun(KNOWN_VENUES);
        System.out.printf("  %d ordered pairs: %s%n%n", pairs.size(),
                pairs.stream().map(p -> p[0] + "->" + p[1]).toList());

        // Only the venues a requested pair actually touches get a gateway. A gateway's constructor
        // demands its own credentials when live, so building one nobody asked for turns "run these two
        // CEXs" into a Hyperliquid key error.
        java.util.Set<String> needed = new java.util.LinkedHashSet<>();
        for (String[] pair : pairs) {
            needed.add(pair[0]);
            needed.add(pair[1]);
        }
        Map<String, Venue> venues = new LinkedHashMap<>();
        if (needed.contains("binance")) {
            venues.put("binance", new Venue("binance", base + "USDT", new BinanceGateway(!live)));
        }
        if (needed.contains("bybit")) {
            venues.put("bybit", new Venue("bybit", base + "USDT", new BybitGateway(!live)));
        }
        if (needed.contains("hyperliquid")) {
            // Hyperliquid names perpetuals by the bare coin, with no quote suffix at all.
            venues.put("hyperliquid", new Venue("hyperliquid", base, new HyperliquidGateway(!live)));
        }

        FillTracker tracker = new FillTracker();
        List<AutoCloseable> streams = new ArrayList<>();
        // Streams open BEFORE any order is placed. Placing first opens a window in which a fill
        // arrives with nothing listening for it.
        for (Venue v : venues.values()) {
            streams.add(v.gateway().streamOrderUpdates(tracker::onUpdate));
        }

        if (Boolean.parseBoolean(System.getProperty("rtFlatten", "false"))) {
            flatten(venues.values(), tracker, live);
            for (AutoCloseable stream : streams) {
                stream.close();
            }
            return;
        }

        int roundTripped = 0;
        int neverOpened = 0;
        try {
            for (String[] pair : pairs) {
                Venue maker = venues.get(pair[0]);
                Venue taker = venues.get(pair[1]);
                System.out.printf("── %s (%s) → %s (taker) ──────────────%n",
                        maker.name(), crossFirst ? "crossing" : "maker", taker.name());
                boolean[] opened = {false};
                boolean ok = runPair(maker, taker, notional, tracker, chases, chaseFor, live,
                        crossFirst, opened);
                if (!ok) {
                    System.out.println("\n!!!! STOPPING: this pair did not end flat. Resolve the "
                            + "exposure above before running further pairs.");
                    break;
                }
                // Ending flat without ever opening is safe but proves nothing, so it is not a pass.
                if (opened[0]) {
                    roundTripped++;
                    System.out.printf("   PAIR OK — round trip proven%n%n");
                } else {
                    neverOpened++;
                    System.out.printf("   PAIR SKIPPED — never opened, nothing proven%n%n");
                }
            }
        } finally {
            for (AutoCloseable stream : streams) {
                stream.close();
            }
        }

        System.out.printf("%n%d of %d pairs completed a full open/close round trip", roundTripped,
                pairs.size());
        System.out.println(neverOpened > 0
                ? "; %d never opened and proved nothing.".formatted(neverOpened)
                : ".");
        if (live) {
            System.out.println("\nFinal position check across all venues:");
            boolean flat = true;
            for (Venue v : venues.values()) {
                for (PositionSnapshot p : v.gateway().positions()) {
                    System.out.printf("  !! %s %s %s%n", p.venue(), p.venueSymbol(), p.signedQuantity());
                    flat = false;
                }
            }
            System.out.println(flat ? "  all venues flat." : "  NOT FLAT — see above.");
        }
    }

    /**
     * Opens the pair, verifies it, closes it, verifies flat.
     *
     * @return true only if the pair ended with no exposure on either venue
     */
    private static boolean runPair(Venue maker, Venue taker, double notional, FillTracker tracker,
                                   int chases, Duration chaseFor, boolean live,
                                   boolean crossFirstLeg, boolean[] opened) throws Exception {
        // Direction is arbitrary for a plumbing test - what matters is that the two legs oppose.
        Side makerOpen = Side.SELL;
        Side takerOpen = Side.BUY;

        // Size is decided once, from the current touch, and then worked. Re-deriving it on every
        // chase would let the quantity drift with the price while the order is being worked.
        VenueGateway.SymbolRules makerRules = maker.gateway().rules(maker.symbol());
        BigDecimal openTarget = floorToStep(
                BigDecimal.valueOf(notional).divide(
                        maker.gateway().topOfBook(maker.symbol()).touch(makerOpen), 12,
                        RoundingMode.DOWN),
                makerRules.stepSize());
        if (openTarget.signum() <= 0) {
            System.out.printf("   %.2f USD rounds below one step (%s) on %s — raise -DrtNotional%n",
                    notional, makerRules.stepSize(), maker.name());
            return true;
        }

        Leg makerLeg = crossFirstLeg
                ? crossLeg(maker, makerOpen, openTarget, tracker, false)
                : restUntilFilled(maker, makerOpen, openTarget, tracker, chases, chaseFor, false);
        if (makerLeg == null || makerLeg.filled().signum() == 0) {
            System.out.println("   first leg never filled — nothing opened, nothing to unwind. Skipping.");
            return true;   // harmless: no exposure was ever taken
        }
        opened[0] = true;
        System.out.printf("   maker filled %s %s @ %s%n",
                makerOpen, makerLeg.filled(), makerLeg.price());

        Leg takerLeg = crossToHedge(maker, taker, takerOpen, makerLeg.filled(), tracker, false);
        if (takerLeg == null) {
            System.out.printf("!!!! UNHEDGED: %s holds %s %s with no offsetting position on %s.%n",
                    maker.name(), makerOpen, makerLeg.filled(), taker.name());
            return false;
        }
        System.out.printf("   taker hedged %s %s @ %s%n",
                takerOpen, takerLeg.filled(), takerLeg.price());

        if (live) {
            reportPositions(maker, taker, "after open");
        }

        // Close is the mirror image: same shape, opposite sides, reduce-only on both legs.
        // Closes exactly what opened, not a recomputed notional - the same number of contracts, so
        // no rounding residue can be left behind on the venue.
        Leg makerClose = crossFirstLeg
                ? crossLeg(maker, makerOpen.opposite(), makerLeg.filled(), tracker, true)
                : restUntilFilled(maker, makerOpen.opposite(), makerLeg.filled(),
                        tracker, chases, chaseFor, true);
        if (makerClose == null || makerClose.filled().signum() == 0) {
            System.out.printf("!!!! maker close never filled — %s still holds %s %s and %s holds the "
                    + "hedge. Both legs are still open.%n",
                    maker.name(), makerOpen, makerLeg.filled(), taker.name());
            return false;
        }
        System.out.printf("   maker closed %s %s @ %s%n",
                makerOpen.opposite(), makerClose.filled(), makerClose.price());

        Leg takerClose = crossToHedge(maker, taker, takerOpen.opposite(), makerClose.filled(),
                tracker, true);
        if (takerClose == null) {
            System.out.printf("!!!! %s closed but %s did NOT — that venue still holds %s %s.%n",
                    maker.name(), taker.name(), takerOpen, takerLeg.filled());
            return false;
        }
        System.out.printf("   taker closed %s %s @ %s%n",
                takerOpen.opposite(), takerClose.filled(), takerClose.price());

        if (live) {
            return reportPositions(maker, taker, "after close");
        }
        return true;
    }

    /**
     * Rests a post-only order at the venue's own touch, re-placing it while it goes unfilled.
     *
     * <p>Returns whatever filled, which may be less than requested: a partial fill is real exposure
     * and the caller must hedge it, so it is reported rather than treated as failure.
     */
    private static Leg restUntilFilled(Venue venue, Side side, BigDecimal targetQuantity,
                                       FillTracker tracker, int chases, Duration chaseFor,
                                       boolean reduceOnly) throws Exception {
        VenueGateway.SymbolRules rules = venue.gateway().rules(venue.symbol());
        BigDecimal totalFilled = BigDecimal.ZERO;
        BigDecimal lastPrice = BigDecimal.ZERO;

        for (int attempt = 1; attempt <= chases; attempt++) {
            VenueGateway.TopOfBook book = venue.gateway().topOfBook(venue.symbol());
            // Join the near side rather than crossing: a resting SELL sits at the ask, a BUY at the bid.
            BigDecimal price = book.touch(side);
            BigDecimal quantity = floorToStep(targetQuantity.subtract(totalFilled), rules.stepSize());
            if (quantity.signum() <= 0) {
                break;   // whatever is left rounds below one step; treat as complete
            }

            String clientId = "xvfrt-" + venue.name() + "-" + System.nanoTime();
            tracker.expect(clientId);
            SubmitResult submitted = venue.gateway().placePostOnly(
                    venue.symbol(), side, quantity, price, clientId, reduceOnly);
            if (submitted.outcome() == SubmitOutcome.UNKNOWN) {
                submitted = resolve(venue, clientId, submitted);
            }
            if (submitted.outcome() == SubmitOutcome.REJECTED) {
                // Post-only rejection means the touch moved and the order would have crossed. Not a
                // failure - re-read the book and try again.
                System.out.printf("   [%d/%d] post-only rejected (%s), re-pricing%n",
                        attempt, chases, submitted.detail());
                continue;
            }

            System.out.printf("   [%d/%d] resting %s %s @ %s%s%n", attempt, chases, side, quantity,
                    price, reduceOnly ? " (reduce-only)" : "");
            BigDecimal filledThisAttempt = tracker.awaitFill(clientId, quantity, chaseFor);
            totalFilled = totalFilled.add(filledThisAttempt);
            lastPrice = price;

            if (filledThisAttempt.signum() > 0) {
                System.out.printf("        filled %s%n", filledThisAttempt);
            }
            if (filledThisAttempt.compareTo(quantity) >= 0) {
                return new Leg(venue, side, totalFilled, price);
            }
            // Unfilled or partial: cancel the remainder. The cancel can race a late fill, so the
            // tracker is re-read afterwards rather than assuming the cancel won.
            venue.gateway().cancel(submitted.handle());
            BigDecimal afterCancel = tracker.cumulative(clientId);
            if (afterCancel.compareTo(filledThisAttempt) > 0) {
                BigDecimal extra = afterCancel.subtract(filledThisAttempt);
                System.out.printf("        +%s filled during cancel%n", extra);
                totalFilled = totalFilled.add(extra);
            }
        }
        return totalFilled.signum() > 0 ? new Leg(venue, side, totalFilled, lastPrice) : null;
    }

    /**
     * Crosses immediately on one venue, for the first leg rather than the hedge.
     *
     * <p>Exists because a post-only order cannot be made to fill on demand: every liquid perp measured
     * here quotes a one-tick spread, so a resting order can only join the back of a queue and may wait
     * indefinitely. That makes the maker path useless for proving the parts that actually carry risk -
     * that a fill event reaches the stream, that the hedge fires from it, and that reduce-only closes
     * both legs. Crossing the first leg fills in moments and exercises exactly that chain, at the cost
     * of a taker fee and one spread.
     */
    private static Leg crossLeg(Venue venue, Side side, BigDecimal quantity, FillTracker tracker,
                                boolean reduceOnly) throws Exception {
        VenueGateway.TopOfBook book = venue.gateway().topOfBook(venue.symbol());
        BigDecimal cross = side == Side.BUY ? book.ask() : book.bid();
        BigDecimal slip = cross.multiply(
                BigDecimal.valueOf(XvfConfig.MAX_TAKER_SLIPPAGE_BPS / 10_000.0));
        BigDecimal worst = side == Side.BUY ? cross.add(slip) : cross.subtract(slip);

        String clientId = "xvfrt-" + venue.name() + "-" + System.nanoTime();
        tracker.expect(clientId);
        SubmitResult result = venue.gateway().placeCappedIoc(
                venue.symbol(), side, quantity, worst, clientId, reduceOnly);
        if (result.outcome() == SubmitOutcome.UNKNOWN) {
            result = resolve(venue, clientId, result);
        }
        if (!result.accepted()) {
            System.out.printf("   IOC not accepted on %s: %s%n", venue.name(), result.detail());
            return null;
        }
        System.out.printf("   crossing %s %s @ cap %s%s%n", side, quantity, worst,
                reduceOnly ? " (reduce-only)" : "");
        BigDecimal filled = tracker.awaitFill(clientId, quantity, Duration.ofSeconds(10));
        if (filled.signum() == 0) {
            // Either it truly filled nothing, or the stream never delivered. Those are different
            // problems and the venue is the authority, so ask it directly rather than guess.
            var snapshot = venue.gateway().orderByClientId(venue.symbol(), clientId);
            BigDecimal viaRest = snapshot.map(OrderSnapshot::filledQuantity).orElse(BigDecimal.ZERO);
            if (viaRest.signum() > 0) {
                System.out.printf("!!!! STREAM GAP: %s reports %s filled but no stream event arrived. "
                        + "The hedge would not have fired.%n", venue.name(), viaRest);
                return new Leg(venue, side, viaRest, worst);
            }
            System.out.printf("   IOC filled nothing on %s%n", venue.name());
            return null;
        }
        return new Leg(venue, side, filled, worst);
    }

    /**
     * Crosses on the taker venue to offset {@code makerFilled}, converting units between venues.
     *
     * <p>The two venues may quote different contract sizes for the same asset, so the maker's native
     * filled quantity is converted through each venue's own price rather than sent as-is.
     */
    private static Leg crossToHedge(Venue maker, Venue taker, Side side, BigDecimal makerFilled,
                                    FillTracker tracker, boolean reduceOnly) throws Exception {
        VenueGateway.TopOfBook makerBook = maker.gateway().topOfBook(maker.symbol());
        VenueGateway.TopOfBook takerBook = taker.gateway().topOfBook(taker.symbol());
        BigDecimal makerMid = makerBook.bid().add(makerBook.ask())
                .divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);
        BigDecimal takerMid = takerBook.bid().add(takerBook.ask())
                .divide(BigDecimal.valueOf(2), 12, RoundingMode.HALF_UP);

        BigDecimal usd = makerFilled.multiply(makerMid);
        VenueGateway.SymbolRules rules = taker.gateway().rules(taker.symbol());
        BigDecimal quantity = floorToStep(usd.divide(takerMid, 12, RoundingMode.DOWN),
                rules.stepSize());
        if (quantity.signum() <= 0) {
            System.out.printf("!!!! hedge rounds to zero on %s (%.2f USD, step %s)%n",
                    taker.name(), usd, rules.stepSize());
            return null;
        }

        BigDecimal cross = side == Side.BUY ? takerBook.ask() : takerBook.bid();
        BigDecimal slip = cross.multiply(
                BigDecimal.valueOf(XvfConfig.MAX_TAKER_SLIPPAGE_BPS / 10_000.0));
        BigDecimal worst = side == Side.BUY ? cross.add(slip) : cross.subtract(slip);

        for (int attempt = 1; attempt <= 3; attempt++) {
            String clientId = "xvfrt-" + taker.name() + "-" + System.nanoTime();
            tracker.expect(clientId);
            SubmitResult result = taker.gateway().placeCappedIoc(
                    taker.symbol(), side, quantity, worst, clientId, reduceOnly);
            if (result.outcome() == SubmitOutcome.UNKNOWN) {
                result = resolve(taker, clientId, result);
            }
            if (result.accepted()) {
                // An IOC is terminal within moments either way, so this waits briefly rather than
                // for the full chase interval.
                BigDecimal filled = tracker.awaitFill(clientId, quantity, Duration.ofSeconds(10));
                if (filled.signum() > 0) {
                    return new Leg(taker, side, filled, worst);
                }
                // A silent stream and an unfilled order look identical from here, and retrying on
                // that guess is how one hedge becomes three. The venue is the authority: ask it
                // before assuming nothing happened.
                var snapshot = taker.gateway().orderByClientId(taker.symbol(), clientId);
                BigDecimal viaRest = snapshot.map(OrderSnapshot::filledQuantity).orElse(BigDecimal.ZERO);
                if (viaRest.signum() > 0) {
                    System.out.printf("!!!! STREAM GAP: %s reports %s filled but no stream event "
                            + "arrived — NOT retrying%n", taker.name(), viaRest);
                    return new Leg(taker, side, viaRest, worst);
                }
                System.out.printf("   IOC accepted, venue confirms nothing filled (attempt %d) — "
                        + "book moved past the %s cap%n", attempt, worst);
            } else {
                System.out.printf("   IOC not accepted (attempt %d): %s%n", attempt, result.detail());
            }
        }
        return null;
    }

    /**
     * Closes every open position on the named venues with reduce-only IOC, then verifies flat.
     *
     * <p>A recovery path, not part of any test. When a pair half-fills - one leg on, the hedge
     * rejected - the run stops and leaves the exposure deliberately rather than improvising, because
     * an automatic unwind that goes wrong turns one stranded leg into two. This closes it on demand.
     */
    private static void flatten(Iterable<Venue> venues, FillTracker tracker, boolean live)
            throws Exception {
        System.out.println("FLATTEN — closing every open position with reduce-only IOC\n");
        boolean any = false;
        for (Venue v : venues) {
            for (PositionSnapshot p : v.gateway().positions()) {
                // crossLeg trades the venue's configured symbol, so anything else on the account is
                // none of this harness's business - closing it would be trading a position the test
                // never opened.
                if (!p.venueSymbol().equals(v.symbol())) {
                    System.out.printf("   %s %s %s — not this test's symbol (%s), left alone%n",
                            v.name(), p.venueSymbol(), p.signedQuantity(), v.symbol());
                    continue;
                }
                any = true;
                // Opposite of whatever is held: a short closes by buying, a long by selling.
                Side closing = p.signedQuantity().signum() < 0 ? Side.BUY : Side.SELL;
                System.out.printf("   %s %s %s -> %s %s%n", v.name(), p.venueSymbol(),
                        p.signedQuantity(), closing, p.signedQuantity().abs());
                Leg closed = crossLeg(v, closing, p.signedQuantity().abs(), tracker, true);
                System.out.println(closed == null
                        ? "      !!!! close did not fill — still exposed"
                        : "      closed " + closed.filled());
            }
        }
        if (!any) {
            System.out.println("   nothing open on any venue.");
        }
        if (live) {
            System.out.println("\nPosition check after flatten:");
            boolean flat = true;
            for (Venue v : venues) {
                for (PositionSnapshot p : v.gateway().positions()) {
                    System.out.printf("  !! %s %s %s%n", p.venue(), p.venueSymbol(), p.signedQuantity());
                    flat = false;
                }
            }
            System.out.println(flat ? "  all venues flat." : "  STILL NOT FLAT.");
        }
    }

    /** Reads both venues and prints what they hold. Returns true when both are flat. */
    private static boolean reportPositions(Venue maker, Venue taker, String when) {
        boolean flat = true;
        System.out.printf("   positions %s:%n", when);
        for (Venue v : List.of(maker, taker)) {
            List<PositionSnapshot> open = v.gateway().positions();
            if (open.isEmpty()) {
                System.out.printf("      %-12s flat%n", v.name());
            }
            for (PositionSnapshot p : open) {
                System.out.printf("      %-12s %s %s @ %s%n",
                        p.venue(), p.venueSymbol(), p.signedQuantity(), p.entryPrice());
                flat = false;
            }
        }
        return flat;
    }

    /** Resolves an ambiguous submission by the caller's own id, exactly as the engine does. */
    private static SubmitResult resolve(Venue venue, String clientId, SubmitResult unknown) {
        try {
            var found = venue.gateway().orderByClientId(venue.symbol(), clientId);
            if (found.isEmpty()) {
                return new SubmitResult(SubmitOutcome.REJECTED, unknown.handle(),
                        "resolved: venue never saw " + clientId);
            }
            return new SubmitResult(SubmitOutcome.ACCEPTED, found.get().handle(),
                    "resolved: " + found.get().state());
        } catch (RuntimeException e) {
            System.out.printf("!!!! could not resolve %s on %s: %s — an order may be live and "
                    + "untracked%n", clientId, venue.name(), e.getMessage());
            return unknown;
        }
    }

    private static BigDecimal floorToStep(BigDecimal quantity, BigDecimal step) {
        if (step == null || step.signum() <= 0) {
            return quantity;
        }
        return quantity.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    /** Every ordered combination, or the explicit subset named in {@code -DrtPairs}. */
    private static List<String[]> pairsToRun(Iterable<String> names) {
        String requested = System.getProperty("rtPairs", "all");
        List<String[]> out = new ArrayList<>();
        if (!"all".equals(requested)) {
            for (String one : requested.split(",")) {
                String[] parts = one.trim().split(":");
                if (parts.length == 2) {
                    out.add(new String[] {parts[0], parts[1]});
                }
            }
            return out;
        }
        List<String> all = new ArrayList<>();
        names.forEach(all::add);
        for (String maker : all) {
            for (String taker : all) {
                if (!maker.equals(taker)) {
                    out.add(new String[] {maker, taker});
                }
            }
        }
        return out;
    }
}
