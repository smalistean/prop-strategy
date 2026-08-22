package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Candidate;
import com.smalistean.propstrategy.xvf.venue.BinanceGateway;
import com.smalistean.propstrategy.xvf.venue.BybitGateway;
import com.smalistean.propstrategy.xvf.venue.HyperliquidGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.OrderSnapshot;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.Side;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitResult;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.TopOfBook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point that turns an XVF target book into live orders.
 *
 * <h2>Dry run is the default and must stay that way</h2>
 * Live trading requires {@code -DxvfDryRun=false} explicitly. Every other switch has a safe default; a
 * program that places real orders because someone forgot a flag is not a safe default, and the cost
 * of the mistake is asymmetric — a missed run costs one period of funding, an unintended run costs
 * real money on twenty positions.
 *
 * <h2>Usage</h2>
 * <pre>
 *   -DxvfCapital=10000
 *   -DxvfDryRun=true              default; set false to actually trade
 *   BINANCE_API_KEY / BINANCE_SECRET_KEY, BYBIT_API_KEY / BYBIT_SECRET_KEY,
 *   HL_ACCOUNT_ADDRESS / HL_API_WALLET_ADDRESS / HL_API_PRIVATE_KEY
 *   (environment, never -D: ps aux shows those)
 * </pre>
 *
 * <h2>What is wired and what is not</h2>
 * Binance, Bybit and Hyperliquid are implemented. Any future venue resolves to
 * {@link UnwiredGateway}, which refuses loudly rather than silently skipping — a book that quietly opens only its Binance legs is
 * a directional position, not a hedged one, and that failure must be impossible to miss.
 */
public final class XvfExecutionApplication {

    /** One line of the target book, as produced by the signal. */
    private record Target(String base, String shortVenue, String shortSymbol,
                          String longVenue, String longSymbol,
                          double spreadPct, double thinLegWeeklyVolume) { }

    private XvfExecutionApplication() {
    }

    public static void main(String[] args) throws Exception {
        boolean live = !Boolean.parseBoolean(System.getProperty("xvfDryRun", "true"));

        // Read before the header prints: closeall/closeallmaker/closepair close whatever the accounts
        // actually hold, not anything sized from capital, so a "capital" figure next to them is not
        // erroneous data - it is a number this run never looks at, printed as if it mattered.
        String earlyMode = System.getProperty("xvfMode", "enter");
        boolean closeOnlyMode = java.util.Set.of("closeall", "closeallmaker", "closepair").contains(earlyMode);

        double capital = Double.parseDouble(System.getProperty("xvfCapital", "10000"));
        if (closeOnlyMode) {
            System.out.printf("XVF execution — %s — %s%n", live ? "*** LIVE ***" : "dry run", earlyMode);
        } else {
            System.out.printf("XVF execution — %s — capital %,.0f USDT%n",
                    live ? "*** LIVE ***" : "dry run", capital);
        }
        if (!live) {
            System.out.println("  no orders will be sent. pass -DxvfDryRun=false to trade.");
        }
        if (!closeOnlyMode && capital < XvfConfig.MIN_CAPITAL_USD) {
            System.out.printf("  WARNING: below the %.0f minimum; step rounding will exceed 1%% on "
                    + "more than a tenth of candidates%n", XvfConfig.MIN_CAPITAL_USD);
        }

        Map<String, VenueGateway> gateways = new LinkedHashMap<>();
        gateways.put("binance", new BinanceGateway(!live));
        gateways.put("bybit", new BybitGateway(!live));
        gateways.put("hyperliquid", new HyperliquidGateway(!live));
        // dYdX has no entry here, and none is coming: it is not in XvfConfig.VENUES, so no candidate
        // can name it and there is nothing for a gateway to serve. See XVF_V1_SCOPE.md.

        // Checked before the signal pipeline runs, and before it, deliberately: closing every open leg
        // must not depend on perp_funding_all being fresh. An operator reaching for closeall is either
        // starting over deliberately or reacting to something wrong - either way it must still work if
        // requireFreshFunding would otherwise refuse to produce a book.
        if ("closeall".equals(earlyMode)) {
            closeAll(gateways, live);
            return;
        }
        if ("closeallmaker".equals(earlyMode)) {
            closeAllMaker(gateways, live);
            return;
        }
        if ("closepair".equals(earlyMode)) {
            String basesProperty = System.getProperty("xvfCloseBases", "");
            java.util.Set<String> bases = new java.util.LinkedHashSet<>();
            for (String base : basesProperty.split(",")) {
                if (!base.isBlank()) {
                    bases.add(base.strip().toUpperCase(java.util.Locale.ROOT));
                }
            }
            if (bases.isEmpty()) {
                throw new IllegalArgumentException(
                        "xvfMode=closepair needs -DxvfCloseBases=BASE[,BASE...]");
            }
            closeBases(gateways, live, bases);
            return;
        }

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        XvfSignalEngine.requireFreshFunding(database, java.time.LocalDate.now());
        // Uncapped: a candidate that ranks in the top POSITIONS by spread but cannot actually be
        // opened - CAT's step size, ON's ticker collision - must not waste that slot forever. Both
        // the entry loop and reconcile() walk past a candidate like that to the next one instead of
        // stopping at exactly POSITIONS candidates considered.
        List<Target> book = XvfSignalEngine.fullBook(database, java.time.LocalDate.now()).stream()
                .map(XvfExecutionApplication::toTarget).toList();
        double legNotional = capital * XvfConfig.LEG_LEVERAGE / (XvfConfig.POSITIONS * 2.0);

        String mode = System.getProperty("xvfMode", "enter");
        if ("reconcile".equals(mode)) {
            reconcile(gateways, book, legNotional, live);
            return;
        }
        if ("brackets".equals(mode)) {
            brackets(gateways, live);
            return;
        }

        Duration abandonAfter = Duration.ofMinutes(30);
        Duration chaseEvery = Duration.ofSeconds(Integer.getInteger("xvfChaseSeconds", 30));
        try (PairedEntryEngine engine = new PairedEntryEngine(abandonAfter, chaseEvery)) {
            // One listener per venue, wired before any order is placed. Placing first would open a
            // window in which a fill arrives with nothing listening for it.
            List<AutoCloseable> streams = new ArrayList<>();
            for (VenueGateway gateway : gateways.values()) {
                streams.add(gateway.streamOrderUpdates(engine::onOrderUpdate));
            }

            // One positions() call per venue, not one per candidate: this run is resumable by
            // construction rather than by tracking what a PREVIOUS process did, since that process
            // may have crashed, been interrupted, or simply be a different invocation entirely. The
            // venues are the only durable record of what already opened.
            Map<String, java.util.Set<String>> alreadyOpenSymbols = new LinkedHashMap<>();
            // Distinct bases held anywhere, independent of whether that base still ranks in today's
            // book. A retained pair is kept even after its signal decays below the entry threshold, so
            // it can easily be absent from `book` entirely - measured live on 2026-08-22, 11 of 19 held
            // bases had fallen out of that day's candidates. Counting occupied slots by walking the
            // ranked list would make all 11 invisible and try to open 11 unwanted new pairs to reach
            // POSITIONS; counting from live positions up front cannot miss a base this way.
            java.util.Set<String> heldBases = new java.util.HashSet<>();
            for (var entry : gateways.entrySet()) {
                java.util.Set<String> symbols = new java.util.HashSet<>();
                for (VenueGateway.PositionSnapshot p : entry.getValue().positions()) {
                    if (p.signedQuantity().signum() != 0) {
                        symbols.add(p.venueSymbol());
                        heldBases.add(XvfConfig.normaliseBase(entry.getKey(), p.venueSymbol()));
                    }
                }
                alreadyOpenSymbols.put(entry.getKey(), symbols);
            }

            // Fetched once, then decremented locally as this run opens pairs - a single run can open
            // several, and each one commits real margin on both its venues before the next candidate
            // is considered. Measured live 2026-08-22: two candidates in the same run both needed
            // Bybit, and nothing checked whether Bybit's margin could actually support a second one
            // after the first had already used most of what was free - the second's maker filled
            // anyway and its hedge failed for insufficient margin, leaving a naked leg.
            Map<String, BigDecimal> freeCapital = new LinkedHashMap<>();
            for (VenueGateway gateway : gateways.values()) {
                freeCapital.put(gateway.name(), gateway.availableCapital());
            }

            // Two counters over the same walk: slotsFilled starts at the true occupied-slot count and
            // counts every rank-ordered candidate this run itself fills, stopping once the book is
            // full; openedThisRun is only what THIS run actually sent, for the summary line. book is
            // uncapped (see fullBook), so a candidate that can never be opened - CAT's step size, ON's
            // ticker collision - costs one skipped rank rather than one permanently empty slot; the
            // walk continues to whatever ranks below POSITIONS until it finds one that works.
            int slotsFilled = heldBases.size();
            int openedThisRun = 0;
            for (Target t : book) {
                if (slotsFilled >= XvfConfig.POSITIONS) {
                    break;
                }
                VenueGateway shortGw = gateways.get(t.shortVenue());
                VenueGateway longGw = gateways.get(t.longVenue());
                if (shortGw == null || longGw == null) {
                    System.out.printf("  skip %s: no gateway for %s/%s%n",
                            t.base(), t.shortVenue(), t.longVenue());
                    continue;
                }
                // Skip the PAIR, not the run. An unimplemented venue costs one position out of
                // twenty; aborting would cost all of them.
                if (!shortGw.wired() || !longGw.wired()) {
                    System.out.printf("  skip %s: %s not implemented%n", t.base(),
                            !shortGw.wired() ? shortGw.name() : longGw.name());
                    continue;
                }

                // Participation cap: funding is a percentage and says nothing about whether the
                // notional is reachable. REN paid 507% annualised on $289 of weekly volume.
                double capped = Math.min(legNotional,
                        t.thinLegWeeklyVolume() * XvfConfig.MAX_PARTICIPATION);
                if (capped < legNotional * 0.5) {
                    System.out.printf("  skip %s: thin leg supports only %.0f of %.0f USD%n",
                            t.base(), capped, legNotional);
                    continue;
                }

                // The THINNER venue rests. That is where crossing costs most, so the maker order is
                // worth more there and the market order lands where liquidity is deepest.
                boolean shortIsThinner = isThinner(t.shortVenue(), t.longVenue());
                VenueGateway makerGw = shortIsThinner ? shortGw : longGw;
                VenueGateway takerGw = shortIsThinner ? longGw : shortGw;
                String makerSymbol = shortIsThinner ? t.shortSymbol() : t.longSymbol();
                String takerSymbol = shortIsThinner ? t.longSymbol() : t.shortSymbol();
                Side makerSide = shortIsThinner ? Side.SELL : Side.BUY;
                Side takerSide = shortIsThinner ? Side.BUY : Side.SELL;

                // Checked before any pricing work, so a re-run of the same command does not try to
                // add to a leg a previous run already opened. Sizing here assumes starting from
                // flat; a second post-only order on a symbol that already has a live position would
                // resize it in a way nothing downstream accounts for.
                if (alreadyOpenSymbols.getOrDefault(makerGw.name(), java.util.Set.of()).contains(makerSymbol)
                        || alreadyOpenSymbols.getOrDefault(takerGw.name(), java.util.Set.of()).contains(takerSymbol)) {
                    // No slotsFilled++ here: this base is already counted in the seed above. Counting
                    // it again would double-count every held base that still happens to rank today,
                    // making the walk stop early and under-fill the book instead of over-filling it.
                    System.out.printf("  skip %s: already open on %s or %s - resuming, not reopening%n",
                            t.base(), makerGw.name(), takerGw.name());
                    continue;
                }

                // Checked before pricing, not discovered from a rejected order after the maker leg
                // already filled. A required margin figure is not available up front - the actual
                // requirement depends on price and leverage, both resolved below - so this compares
                // against the full target notional, which is conservative (LEG_LEVERAGE=1.0 makes the
                // two figures equal anyway) rather than optimistic.
                //
                // Live only: every gateway's availableCapital() returns ZERO in dry run (same reason
                // positions() returns empty there), so this would refuse every single candidate and
                // make a dry run useless for previewing pricing and sizing rather than just silent
                // about what is already open.
                BigDecimal required = BigDecimal.valueOf(capped);
                if (live) {
                    BigDecimal makerFree = freeCapital.getOrDefault(makerGw.name(), BigDecimal.ZERO);
                    BigDecimal takerFree = freeCapital.getOrDefault(takerGw.name(), BigDecimal.ZERO);
                    if (makerFree.compareTo(required) < 0 || takerFree.compareTo(required) < 0) {
                        System.out.printf("  skip %s: %s free on %s, %s free on %s - not enough for a "
                                + "%.0f USD leg%n", t.base(), makerFree, makerGw.name(), takerFree,
                                takerGw.name(), capped);
                        continue;
                    }
                }

                // Each leg is priced and sized on its OWN venue. Using one price for both was wrong
                // whenever the venues quote different contract units - 1000PEPE against PEPE, kPEPE
                // against PEPE - which is 3.6% of historical selections. On those the hedge was out
                // by 1000x: short $250 against long $250,000, or the reverse.
                //
                // No explicit multiplier lookup is needed, because the multiplier is already in the
                // price. A 1000PEPE contract is quoted per 1000 PEPE, so dividing the same target USD
                // by each venue's own price yields matched UNDERLYING exposure by construction.
                BigDecimal makerPrice = referencePrice(makerGw, makerSymbol, makerSide);
                BigDecimal takerPrice = referencePrice(takerGw, takerSymbol, takerSide);
                if (makerPrice.signum() <= 0 || takerPrice.signum() <= 0) {
                    System.out.printf("  skip %s: no price on %s/%s%n",
                            t.base(), makerGw.name(), takerGw.name());
                    continue;
                }

                // rules() can refuse a symbol outright - a stock-perpetual ticker collision,
                // confirmed live on Bybit's ON (ON Semiconductor, not the Orochi Network crypto
                // token Binance lists under the same three letters). Skip the PAIR, not the run,
                // for exactly the same reason an unimplemented venue does: one bad candidate must
                // not cost the other nineteen.
                Sizing sized;
                try {
                    sized = size(capped, makerPrice, makerGw.rules(makerSymbol),
                            takerPrice, takerGw.rules(takerSymbol));
                } catch (IllegalStateException e) {
                    System.out.printf("  skip %s: %s%n", t.base(), e.getMessage());
                    continue;
                }
                if (sized.rejection() != null) {
                    System.out.printf("  skip %s: %s%n", t.base(), sized.rejection());
                    continue;
                }
                BigDecimal makerQty = sized.makerQty();
                BigDecimal takerQty = sized.takerQty();
                double imbalance = sized.imbalance();

                System.out.printf("%-10s maker %-12s %-4s %-16s | taker %-12s %-4s %-16s | "
                        + "%6.0f USD | imbalance %.3f%%%n",
                        t.base(), makerGw.name(), makerSide, makerSymbol,
                        takerGw.name(), takerSide, takerSymbol, capped, imbalance * 100);

                if (live && !setLegLeverage(makerGw, makerSymbol, takerGw, takerSymbol)) {
                    // Skip the PAIR, not the run - same reasoning as an unwired venue above. Opening
                    // at whatever leverage a symbol happened to have left over from earlier manual
                    // use is not a smaller version of this bug, it is the bug: measured live on ACE,
                    // 20x on one leg against 3x on the other, neither matching LEG_LEVERAGE.
                    System.out.printf("  skip %s: leverage could not be confirmed at %.0fx on both "
                            + "legs%n", t.base(), XvfConfig.LEG_LEVERAGE);
                    continue;
                }

                boolean resting = engine.open(t.base(),
                        new PairedEntryEngine.Leg(makerGw, makerSymbol, makerSide, makerQty),
                        new PairedEntryEngine.Leg(takerGw, takerSymbol, takerSide, takerQty),
                        makerPrice);
                if (!resting) {
                    // The venue rejected the order outright. The free-capital check above catches the
                    // common case in advance now, but it compares against target notional, not the
                    // exact margin the venue itself computes - a venue-side rejection is still possible
                    // at the margin. Caught synchronously inside engine.open() itself (placePostOnly's
                    // HTTP response, not a later stream event), so it is safe to keep walking the
                    // ranked list here rather than counting this base as having filled a slot - the
                    // same reasoning as every other skip above, just discovered one step later.
                    System.out.printf("  skip %s: venue rejected the maker order - trying the next "
                            + "candidate rather than leaving the slot empty%n", t.base());
                    continue;
                }
                // Committed against the local tally immediately, not re-fetched from the venue: the
                // margin this order just reserved will not show up in a fresh availableCapital() call
                // until the venue's own accounting catches up, and the next candidate in this same
                // walk must not be sized as if it were still free.
                freeCapital.merge(makerGw.name(), required.negate(), BigDecimal::add);
                freeCapital.merge(takerGw.name(), required.negate(), BigDecimal::add);
                slotsFilled++;
                openedThisRun++;
            }

            System.out.printf("%n%d pairs opened this run (%d of %d slots filled).%n",
                    openedThisRun, slotsFilled, XvfConfig.POSITIONS);
            if (live) {
                // A dry run never opens a stream and never delivers a fill event, so every pair
                // would sit WORKING forever - waiting for resolution here would just be waiting out
                // the clock on nothing. Only a live run has a real fill to wait for.
                System.out.println("  waiting for every pair to resolve (hedged, abandoned, or "
                        + "given up on) before closing the venue streams - a maker fill that "
                        + "arrives after that point has nothing listening for it.");
                waitForResolution(engine, abandonAfter);
            }

            engine.outstanding().forEach((base, state) ->
                    System.out.printf("  !! %s %s — needs manual attention%n", base, state));

            for (AutoCloseable stream : streams) {
                stream.close();
            }
        }
    }

    /**
     * Blocks until every pair is done or {@code ceiling} has passed, whichever comes first, printing
     * progress along the way so a run does not look hung during what can legitimately be up to 30
     * minutes of silence while post-only orders rest.
     *
     * <p>The ceiling matches the engine's own {@code abandonAfter}: that is when its last internal
     * timer fires, so waiting any less would race the engine's own cleanup, and any more only delays
     * an already-abandoned run for no benefit.
     */
    private static void waitForResolution(PairedEntryEngine engine, Duration ceiling)
            throws InterruptedException {
        long deadline = System.nanoTime() + ceiling.toNanos() + Duration.ofSeconds(5).toNanos();
        long lastReport = 0;
        while (!engine.allResolved() && System.nanoTime() < deadline) {
            Thread.sleep(2_000);
            long elapsedSeconds = (ceiling.toNanos() - (deadline - System.nanoTime())) / 1_000_000_000;
            if (elapsedSeconds - lastReport >= 30) {
                lastReport = elapsedSeconds;
                System.out.printf("  ... %d still working after %ds%n",
                        engine.unresolvedCount(), elapsedSeconds);
            }
        }
    }

    /**
     * Closes every open leg on every venue, regardless of what any book wants.
     *
     * <p>Reuses {@link XvfReconciler} with an empty desired book rather than a separate close path:
     * reconciling against nothing makes every held leg {@link XvfReconciler.Reason#NOT_IN_BOOK}, which
     * is exactly the ordinary rebalance-exit case {@link #reconcile} already sends through the same
     * tested close logic. There is nothing "close everything" needs that closing a normal drift does
     * not already do correctly.
     */
    private static void closeAll(Map<String, VenueGateway> gateways, boolean live) {
        System.out.printf("%nclosing every open leg on every venue (xvfMode=closeall)%n");
        List<XvfReconciler.Drift> drifts = XvfReconciler.plan(gateways, List.of());
        if (drifts.isEmpty()) {
            System.out.println("  nothing open; nothing to do.");
            return;
        }
        for (XvfReconciler.Drift d : drifts) {
            System.out.printf("  %-12s %-10s %-12s held %s%n", d.base(), d.venue(), d.venueSymbol(), d.actual());
        }
        boolean done = XvfReconciler.apply(gateways, drifts, live);
        System.out.println(done
                ? "\nclosed."
                : "\n!!!! NOT fully closed — see above. The account may still hold naked legs.");
    }

    /**
     * Closes every open pair the same way {@link PairedEntryEngine#close} was built to: resting on the
     * thinner venue, chasing until it fills, crossing only once {@code abandonAfter} passes. Unlike
     * {@link #closeAll}, which always crosses immediately - all-taker costs roughly 34bp for an
     * open-and-close pair against roughly 21bp of funding per 3-day cycle, so an exit that always
     * crosses spends more than the position earned. This is the exit brackets and the ordinary
     * rebalance both use; {@link #closeAll} exists only for when certainty matters more than the
     * basis points.
     */
    private static void closeAllMaker(Map<String, VenueGateway> gateways, boolean live) throws Exception {
        System.out.printf("%nclosing every open pair with a resting maker order (xvfMode=closeallmaker)%n");

        Map<String, List<VenueGateway.PositionSnapshot>> byBase = new LinkedHashMap<>();
        Map<String, VenueGateway> ownerOf = new LinkedHashMap<>();
        for (Map.Entry<String, VenueGateway> entry : gateways.entrySet()) {
            for (VenueGateway.PositionSnapshot p : entry.getValue().positions()) {
                if (p.signedQuantity().signum() == 0) {
                    continue;
                }
                String base = XvfConfig.normaliseBase(entry.getKey(), p.venueSymbol());
                byBase.computeIfAbsent(base, k -> new ArrayList<>()).add(p);
                ownerOf.put(entry.getKey() + "|" + p.venueSymbol(), entry.getValue());
            }
        }
        if (byBase.isEmpty()) {
            System.out.println("  nothing open; nothing to do.");
            return;
        }

        Duration abandonAfter = Duration.ofMinutes(30);
        Duration chaseEvery = Duration.ofSeconds(Integer.getInteger("xvfChaseSeconds", 30));
        try (PairedEntryEngine engine = new PairedEntryEngine(abandonAfter, chaseEvery)) {
            List<AutoCloseable> streams = new ArrayList<>();
            for (VenueGateway gateway : gateways.values()) {
                streams.add(gateway.streamOrderUpdates(engine::onOrderUpdate));
            }

            int started = 0;
            for (var e : byBase.entrySet()) {
                List<VenueGateway.PositionSnapshot> legs = e.getValue();
                if (legs.size() != 2) {
                    System.out.printf("  !! %s has %d leg(s), not a pair — skipped, close it by hand%n",
                            e.getKey(), legs.size());
                    continue;
                }
                VenueGateway.PositionSnapshot pA = legs.get(0);
                VenueGateway.PositionSnapshot pB = legs.get(1);
                boolean aIsThinner = isThinner(pA.venue(), pB.venue());
                VenueGateway.PositionSnapshot makerPos = aIsThinner ? pA : pB;
                VenueGateway.PositionSnapshot takerPos = aIsThinner ? pB : pA;
                VenueGateway makerGw = ownerOf.get(makerPos.venue() + "|" + makerPos.venueSymbol());
                VenueGateway takerGw = ownerOf.get(takerPos.venue() + "|" + takerPos.venueSymbol());

                // Reducing a long means selling; reducing a short means buying - the opposite of the
                // sign the position is already held at.
                Side makerSide = makerPos.signedQuantity().signum() > 0 ? Side.SELL : Side.BUY;
                Side takerSide = takerPos.signedQuantity().signum() > 0 ? Side.SELL : Side.BUY;
                BigDecimal makerPrice = referencePrice(makerGw, makerPos.venueSymbol(), makerSide);
                if (makerPrice.signum() <= 0) {
                    System.out.printf("  skip %s: no price on %s%n", e.getKey(), makerGw.name());
                    continue;
                }

                engine.close(e.getKey(),
                        new PairedEntryEngine.Leg(makerGw, makerPos.venueSymbol(), makerSide,
                                makerPos.signedQuantity().abs()),
                        new PairedEntryEngine.Leg(takerGw, takerPos.venueSymbol(), takerSide,
                                takerPos.signedQuantity().abs()),
                        makerPrice);
                started++;
            }

            System.out.printf("%n%d pair(s) closing.%n", started);
            if (live) {
                System.out.println("  waiting for every pair to resolve before closing the venue "
                        + "streams - a maker fill that arrives after that point has nothing "
                        + "listening for it.");
                waitForResolution(engine, abandonAfter);
            }
            engine.outstanding().forEach((base, state) ->
                    System.out.printf("  !! %s %s — needs manual attention%n", base, state));
            for (AutoCloseable stream : streams) {
                stream.close();
            }
        }
    }

    /**
     * Closes specific bases only, resting the maker leg on Bybit whenever Bybit is one of the two legs
     * - a deliberate override of the usual thinner-venue heuristic in {@link #closeAllMaker}, for a
     * case where the ordinary rest venue just proved unreliable (Hyperliquid ADL on CASHCAT, 2026-08-22)
     * and the operator wants a specific venue chosen rather than measured. Falls back to the thinner
     * venue when Bybit is not involved.
     *
     * <p>Unbalanced legs - a naked position left over from a partial hedge or an exchange-forced close
     * on one side - are not a special case: each leg closes at its own held quantity, so a naked base
     * ends up flat on both venues exactly the same as a balanced one.
     */
    private static void closeBases(Map<String, VenueGateway> gateways, boolean live,
                                   java.util.Set<String> bases) throws Exception {
        System.out.printf("%nclosing %s with a resting maker order on bybit where present "
                + "(xvfMode=closepair)%n", String.join(", ", bases));

        Map<String, List<VenueGateway.PositionSnapshot>> byBase = new LinkedHashMap<>();
        Map<String, VenueGateway> ownerOf = new LinkedHashMap<>();
        for (Map.Entry<String, VenueGateway> entry : gateways.entrySet()) {
            for (VenueGateway.PositionSnapshot p : entry.getValue().positions()) {
                if (p.signedQuantity().signum() == 0) {
                    continue;
                }
                String base = XvfConfig.normaliseBase(entry.getKey(), p.venueSymbol());
                if (!bases.contains(base)) {
                    continue;
                }
                byBase.computeIfAbsent(base, k -> new ArrayList<>()).add(p);
                ownerOf.put(entry.getKey() + "|" + p.venueSymbol(), entry.getValue());
            }
        }
        for (String base : bases) {
            if (!byBase.containsKey(base)) {
                System.out.printf("  !! %s: nothing open on any venue - nothing to close%n", base);
            }
        }
        if (byBase.isEmpty()) {
            System.out.println("  nothing open on the requested bases; nothing to do.");
            return;
        }

        Duration abandonAfter = Duration.ofMinutes(30);
        Duration chaseEvery = Duration.ofSeconds(Integer.getInteger("xvfChaseSeconds", 30));
        try (PairedEntryEngine engine = new PairedEntryEngine(abandonAfter, chaseEvery)) {
            List<AutoCloseable> streams = new ArrayList<>();
            for (VenueGateway gateway : gateways.values()) {
                streams.add(gateway.streamOrderUpdates(engine::onOrderUpdate));
            }

            int started = 0;
            for (var e : byBase.entrySet()) {
                List<VenueGateway.PositionSnapshot> legs = e.getValue();
                if (legs.size() != 2) {
                    System.out.printf("  !! %s has %d leg(s), not two venues - skipped, close it by "
                            + "hand%n", e.getKey(), legs.size());
                    continue;
                }
                VenueGateway.PositionSnapshot pA = legs.get(0);
                VenueGateway.PositionSnapshot pB = legs.get(1);
                boolean aIsBybit = "bybit".equals(pA.venue());
                boolean bIsBybit = "bybit".equals(pB.venue());
                boolean aRests = (aIsBybit || bIsBybit) ? aIsBybit : isThinner(pA.venue(), pB.venue());
                VenueGateway.PositionSnapshot makerPos = aRests ? pA : pB;
                VenueGateway.PositionSnapshot takerPos = aRests ? pB : pA;
                VenueGateway makerGw = ownerOf.get(makerPos.venue() + "|" + makerPos.venueSymbol());
                VenueGateway takerGw = ownerOf.get(takerPos.venue() + "|" + takerPos.venueSymbol());

                // Reducing a long means selling; reducing a short means buying - the opposite of the
                // sign the position is already held at.
                Side makerSide = makerPos.signedQuantity().signum() > 0 ? Side.SELL : Side.BUY;
                Side takerSide = takerPos.signedQuantity().signum() > 0 ? Side.SELL : Side.BUY;
                BigDecimal makerPrice = referencePrice(makerGw, makerPos.venueSymbol(), makerSide);
                if (makerPrice.signum() <= 0) {
                    System.out.printf("  skip %s: no price on %s%n", e.getKey(), makerGw.name());
                    continue;
                }

                System.out.printf("%-10s maker %-12s %-4s %-16s %s | taker %-12s %-4s %-16s %s%n",
                        e.getKey(), makerGw.name(), makerSide, makerPos.venueSymbol(),
                        makerPos.signedQuantity().abs(), takerGw.name(), takerSide,
                        takerPos.venueSymbol(), takerPos.signedQuantity().abs());

                engine.close(e.getKey(),
                        new PairedEntryEngine.Leg(makerGw, makerPos.venueSymbol(), makerSide,
                                makerPos.signedQuantity().abs()),
                        new PairedEntryEngine.Leg(takerGw, takerPos.venueSymbol(), takerSide,
                                takerPos.signedQuantity().abs()),
                        makerPrice);
                started++;
            }

            System.out.printf("%n%d pair(s) closing.%n", started);
            if (live) {
                System.out.println("  waiting for every pair to resolve before closing the venue "
                        + "streams - a maker fill that arrives after that point has nothing "
                        + "listening for it.");
                waitForResolution(engine, abandonAfter);
            }
            engine.outstanding().forEach((base, state) ->
                    System.out.printf("  !! %s %s — needs manual attention%n", base, state));
            for (AutoCloseable stream : streams) {
                stream.close();
            }
        }
    }

    /**
     * Closes whatever the account holds that the current book does not want.
     *
     * <p>This is the rebalance exit. It does not need to know that a rebalance happened, or which pairs
     * the previous run opened, or whether that run finished - a book arrives, the account is compared
     * against it, and the difference is closed. A pair stranded by a crash three days ago is
     * indistinguishable from one the latest signal simply dropped, and both are handled the same way.
     */
    private static void reconcile(Map<String, VenueGateway> gateways, List<Target> book,
                                  double legNotional, boolean live) {
        List<XvfReconciler.DesiredLeg> desired = new ArrayList<>();
        // book is uncapped (see fullBook) and walked in rank order the same way the entry loop walks
        // it, so "wanted" here means the same POSITIONS candidates the entry loop would fill,
        // including the backfill past a permanently untradeable one like CAT or ON. Capped here too,
        // rather than adding every candidate that happens to size - a reconciler that wants more than
        // POSITIONS pairs would report every candidate below the cap as MISSING for no reason.
        int wanted = 0;
        for (Target t : book) {
            if (wanted >= XvfConfig.POSITIONS) {
                break;
            }
            VenueGateway shortGw = gateways.get(t.shortVenue());
            VenueGateway longGw = gateways.get(t.longVenue());
            if (shortGw == null || longGw == null) {
                continue;
            }
            // Sized from each venue's own price, exactly as the entry does, so a position opened by
            // the entry path is not mistaken for an oversized one here. rules() can refuse a
            // symbol outright (a stock-perpetual ticker collision) - skip that ONE candidate's
            // desired legs rather than aborting reconciliation for the whole book, which is the
            // one mode where aborting is worst: it exists specifically to close what should not
            // be open.
            Sizing sizing;
            try {
                sizing = size(legNotional,
                        referencePrice(shortGw, t.shortSymbol(), Side.SELL), shortGw.rules(t.shortSymbol()),
                        referencePrice(longGw, t.longSymbol(), Side.BUY), longGw.rules(t.longSymbol()));
            } catch (IllegalStateException e) {
                System.out.printf("  skip %s from reconciliation: %s%n", t.base(), e.getMessage());
                continue;
            }
            if (sizing.rejection() != null) {
                continue;
            }
            desired.add(new XvfReconciler.DesiredLeg(t.base(), t.shortVenue(), t.shortSymbol(),
                    sizing.makerQty().negate()));
            desired.add(new XvfReconciler.DesiredLeg(t.base(), t.longVenue(), t.longSymbol(),
                    sizing.takerQty()));
            wanted++;
        }

        System.out.printf("%nreconciling %d booked legs against the accounts%n", desired.size());
        List<XvfReconciler.Drift> drifts = XvfReconciler.plan(gateways, desired);
        if (drifts.isEmpty()) {
            System.out.println("  accounts match the book; nothing to do.");
            return;
        }
        boolean done = XvfReconciler.apply(gateways, drifts, live);
        System.out.println(done
                ? "\nreconciled."
                : "\n!!!! NOT fully reconciled — see above. The account is directional until resolved.");
    }

    /**
     * Puts resting exit triggers around every open leg, so the book survives not being watched.
     *
     * <p>Run after entry, and again after anything changes the size of a leg. The triggers are the
     * only part of this system that keeps working when the process does not, which is precisely why
     * they are placed by an explicit command rather than left as a side effect of entry.
     */
    private static void brackets(Map<String, VenueGateway> gateways, boolean live) {
        System.out.printf("%nplacing brackets around every open pair%n");

        // Grouped by base, because a band is a property of the pair rather than of either leg. A leg
        // bracketed on its own margin fires at a different price from its partner, which closes one
        // side of the hedge and leaves the other outright.
        Map<String, List<VenueGateway.PositionSnapshot>> byBase = new LinkedHashMap<>();
        Map<String, VenueGateway> ownerOf = new LinkedHashMap<>();
        for (Map.Entry<String, VenueGateway> entry : gateways.entrySet()) {
            for (VenueGateway.PositionSnapshot p : entry.getValue().positions()) {
                String base = XvfConfig.normaliseBase(entry.getKey(), p.venueSymbol());
                byBase.computeIfAbsent(base, k -> new ArrayList<>()).add(p);
                ownerOf.put(entry.getKey() + "|" + p.venueSymbol(), entry.getValue());
            }
        }

        int placed = 0;
        int failed = 0;
        for (Map.Entry<String, List<VenueGateway.PositionSnapshot>> e : byBase.entrySet()) {
            List<VenueGateway.PositionSnapshot> legs = e.getValue();
            if (legs.size() != 2) {
                // One leg, or three. Either way this is not a hedged pair and bracketing it as one
                // would be a guess about which position protects which.
                System.out.printf("  !! %s has %d leg(s), not a pair — not bracketed, and if it is "
                        + "genuinely unpaired it is already directional%n", e.getKey(), legs.size());
                failed += legs.size();
                continue;
            }
            VenueGateway gwA = ownerOf.get(legs.get(0).venue() + "|" + legs.get(0).venueSymbol());
            VenueGateway gwB = ownerOf.get(legs.get(1).venue() + "|" + legs.get(1).venueSymbol());
            BigDecimal markA = referencePrice(gwA, legs.get(0).venueSymbol(),
                    legs.get(0).signedQuantity().signum() > 0 ? Side.SELL : Side.BUY);
            BigDecimal markB = referencePrice(gwB, legs.get(1).venueSymbol(),
                    legs.get(1).signedQuantity().signum() > 0 ? Side.SELL : Side.BUY);

            List<XvfBrackets.Band> bands = XvfBrackets.pair(legs.get(0), markA, legs.get(1), markB);
            System.out.printf("  %s%n", e.getKey());
            for (int i = 0; i < bands.size(); i++) {
                XvfBrackets.Band band = bands.get(i);
                VenueGateway gateway = i == 0 ? gwA : gwB;
                if (!live) {
                    System.out.printf("   [dry-run] %-12s %-10s %s %s brackets %s .. %s (%s)%n",
                            band.venue(), band.venueSymbol(), band.closingSide(), band.quantity(),
                            band.lower().setScale(6, RoundingMode.HALF_UP),
                            band.upper().setScale(6, RoundingMode.HALF_UP), band.derivedFrom());
                    continue;
                }
                if (XvfBrackets.place(gateway, band,
                        "xvfbr-" + band.venueSymbol() + "-" + System.nanoTime())) {
                    placed++;
                } else {
                    failed++;
                }
            }
        }
        System.out.printf("%n%d legs bracketed, %d failed.%n", placed, failed);
        if (failed > 0) {
            System.out.println("A leg without brackets is unprotected while nothing is watching it.");
        }
    }

    /**
     * dYdX and Hyperliquid are materially thinner than the two large CEXs, so they take the resting
     * order. Where both sides are the same class, the short leg rests arbitrarily but consistently.
     */
    private static boolean isThinner(String shortVenue, String longVenue) {
        int shortRank = venueDepthRank(shortVenue);
        int longRank = venueDepthRank(longVenue);
        return shortRank <= longRank;
    }

    private static int venueDepthRank(String venue) {
        return switch (venue) {
            case "hyperliquid" -> 1;
            case "bybit" -> 2;
            default -> 3;   // binance deepest
        };
    }

    /**
     * The price a post-only order should rest at: the near side of the book for that side.
     *
     * <p>A resting SELL joins the ask and a resting BUY joins the bid. Resting at mid would cross and
     * be rejected under post-only - safe, but it wastes the entry - and a last-traded price is not
     * related to where the book currently is.
     */
    private static BigDecimal referencePrice(VenueGateway gateway, String symbol, Side side) {
        return gateway.topOfBook(symbol).touch(side);
    }

    /**
     * Sets both legs to {@code XvfConfig.LEG_LEVERAGE} before either is opened.
     *
     * <p>Called for every pair, not once at startup, because leverage is per symbol per venue - the
     * account's leftover setting for one base tells you nothing about the next. Returns false rather
     * than propagating the exception: a leverage call that failed is exactly as disqualifying as an
     * unwired venue, and the caller already knows how to turn that into "skip this pair, keep the
     * run going" rather than aborting twenty positions over one.
     */
    private static boolean setLegLeverage(VenueGateway makerGw, String makerSymbol,
                                          VenueGateway takerGw, String takerSymbol) {
        int leverage = (int) Math.round(XvfConfig.LEG_LEVERAGE);
        try {
            makerGw.setLeverage(makerSymbol, leverage);
            takerGw.setLeverage(takerSymbol, leverage);
            return true;
        } catch (RuntimeException e) {
            System.out.printf("  leverage call failed: %s%n", e.getMessage());
            return false;
        }
    }

    /**
     * Both legs' native quantities, and how far apart their USD notionals end up.
     *
     * @param rejection null when the pair is safe to open; otherwise why it is not
     */
    record Sizing(BigDecimal makerQty, BigDecimal takerQty, double imbalance, String rejection) { }

    /**
     * Sizes a pair to equal USD notional on both venues.
     *
     * <p>Each leg divides the target by ITS OWN venue price. That is what makes differing contract
     * units correct without an explicit multiplier: a 1000PEPE contract is quoted per 1000 PEPE, so
     * the same USD divided by each venue's price yields matched underlying exposure by construction.
     * Sizing both legs from one price - which this did until 2026-08-18 - is wrong by the multiple
     * whenever the venues disagree, and 3.6% of historical selections disagree.
     *
     * <p>After each leg rounds to its own step size the notionals no longer match exactly, and the
     * residual is naked exposure. It is returned rather than assumed small, and rejected past
     * {@link XvfConfig#MAX_NOTIONAL_IMBALANCE}.
     */
    static Sizing size(double targetUsd, BigDecimal makerPrice, VenueGateway.SymbolRules makerRules,
                       BigDecimal takerPrice, VenueGateway.SymbolRules takerRules) {
        if (makerPrice.signum() <= 0 || takerPrice.signum() <= 0) {
            return new Sizing(null, null, 0, "no price on one of the venues");
        }
        BigDecimal target = BigDecimal.valueOf(targetUsd);
        BigDecimal makerQty = floorToStep(target.divide(makerPrice, 12, RoundingMode.DOWN),
                makerRules.stepSize());
        BigDecimal takerQty = floorToStep(target.divide(takerPrice, 12, RoundingMode.DOWN),
                takerRules.stepSize());
        if (makerQty.signum() <= 0 || takerQty.signum() <= 0) {
            return new Sizing(null, null, 0,
                    "step size exceeds the %.0f USD leg".formatted(targetUsd));
        }
        BigDecimal makerNotional = makerQty.multiply(makerPrice);
        BigDecimal takerNotional = takerQty.multiply(takerPrice);
        double imbalance = makerNotional.subtract(takerNotional).abs()
                .divide(makerNotional.max(takerNotional), 8, RoundingMode.HALF_UP).doubleValue();
        if (imbalance > XvfConfig.MAX_NOTIONAL_IMBALANCE) {
            return new Sizing(makerQty, takerQty, imbalance,
                    "legs differ by %.2f%% after step rounding (%.2f vs %.2f USD), over the %.1f%% tolerance"
                            .formatted(imbalance * 100, makerNotional, takerNotional,
                                    XvfConfig.MAX_NOTIONAL_IMBALANCE * 100));
        }
        if (makerNotional.compareTo(makerRules.minNotionalUsd()) < 0
                || takerNotional.compareTo(takerRules.minNotionalUsd()) < 0) {
            return new Sizing(makerQty, takerQty, imbalance, "below a venue minimum notional");
        }
        return new Sizing(makerQty, takerQty, imbalance, null);
    }

    /** Largest multiple of {@code step} not exceeding {@code quantity}. */
    private static BigDecimal floorToStep(BigDecimal quantity, BigDecimal step) {
        if (step == null || step.signum() <= 0) {
            return quantity;
        }
        return quantity.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    /** Same ranking the reporting application prints, so the two cannot disagree. */
    private static Target toTarget(Candidate c) {
        return new Target(c.base(), c.shortLeg().venue(), c.shortLeg().venueSymbol(),
                c.longLeg().venue(), c.longLeg().venueSymbol(),
                c.spreadAnnualPct(), c.thinLegWeeklyVolume());
    }

    /** Refuses rather than skipping. A half-opened pair is a directional position. */
    private record UnwiredGateway(String venue) implements VenueGateway {
        @Override public String name() {
            return venue;
        }
        @Override public boolean wired() {
            return false;
        }
        @Override public java.util.List<VenueGateway.PositionSnapshot> positions() {
            throw new UnsupportedOperationException(venue + " gateway not implemented");
        }
        @Override public BigDecimal availableCapital() {
            throw new UnsupportedOperationException(venue + " gateway not implemented");
        }
        @Override public void setLeverage(String venueSymbol, int leverage) {
            throw new UnsupportedOperationException(venue + " gateway not implemented");
        }
        @Override public SubmitResult placePostOnly(String s, Side side, BigDecimal q, BigDecimal p,
                                                    String clientOrderId, boolean reduceOnly) {
            throw new UnsupportedOperationException(venue + " gateway not implemented — "
                    + "opening only one leg would leave the book directional");
        }
        @Override public SubmitResult placeCappedIoc(String s, Side side, BigDecimal q,
                                                     BigDecimal worst, String clientOrderId,
                                                     boolean reduceOnly) {
            throw new UnsupportedOperationException(venue + " gateway not implemented");
        }
        @Override public java.util.Optional<OrderSnapshot> orderByClientId(String s, String c) {
            throw new UnsupportedOperationException(venue + " gateway not implemented");
        }
        @Override public TopOfBook topOfBook(String venueSymbol) {
            throw new UnsupportedOperationException(venue + " gateway not implemented");
        }
        @Override public void cancel(OrderHandle handle) {
            throw new UnsupportedOperationException(venue + " gateway not implemented");
        }
        @Override public AutoCloseable streamOrderUpdates(java.util.function.Consumer<OrderUpdate> l) {
            System.out.printf("  [unwired] %s user stream not opened%n", venue);
            return () -> { };
        }
        @Override public SymbolRules rules(String venueSymbol) {
            return new SymbolRules(BigDecimal.ONE, new BigDecimal("5"), BigDecimal.ONE);
        }
    }
}
