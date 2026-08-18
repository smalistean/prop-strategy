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
        double capital = Double.parseDouble(System.getProperty("xvfCapital", "10000"));

        System.out.printf("XVF execution — %s — capital %,.0f USDT%n",
                live ? "*** LIVE ***" : "dry run", capital);
        if (!live) {
            System.out.println("  no orders will be sent. pass -DxvfDryRun=false to trade.");
        }
        if (capital < XvfConfig.MIN_CAPITAL_USD) {
            System.out.printf("  WARNING: below the %.0f minimum; step rounding will exceed 1%% on "
                    + "more than a tenth of candidates%n", XvfConfig.MIN_CAPITAL_USD);
        }

        Map<String, VenueGateway> gateways = new LinkedHashMap<>();
        gateways.put("binance", new BinanceGateway(!live));
        gateways.put("bybit", new BybitGateway(!live));
        gateways.put("hyperliquid", new HyperliquidGateway(!live));
        // dydx is deliberately absent rather than unwired: the venue measurement excluded it, so a
        // gateway will never be written. See XVF_V1_SCOPE.md.

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        XvfSignalEngine.requireFreshFunding(database, java.time.LocalDate.now());
        List<Target> book = XvfSignalEngine.topBook(database, java.time.LocalDate.now()).stream()
                .map(XvfExecutionApplication::toTarget).toList();
        double legNotional = capital * XvfConfig.LEG_LEVERAGE / (XvfConfig.POSITIONS * 2.0);

        try (PairedEntryEngine engine = new PairedEntryEngine(Duration.ofMinutes(30))) {
            // One listener per venue, wired before any order is placed. Placing first would open a
            // window in which a fill arrives with nothing listening for it.
            List<AutoCloseable> streams = new ArrayList<>();
            for (VenueGateway gateway : gateways.values()) {
                streams.add(gateway.streamOrderUpdates(engine::onOrderUpdate));
            }

            for (Target t : book) {
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

                Sizing sized = size(capped, makerPrice, makerGw.rules(makerSymbol),
                        takerPrice, takerGw.rules(takerSymbol));
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

                engine.open(t.base(),
                        new PairedEntryEngine.Leg(makerGw, makerSymbol, makerSide, makerQty),
                        new PairedEntryEngine.Leg(takerGw, takerSymbol, takerSide, takerQty),
                        makerPrice);
            }

            System.out.printf("%n%d pairs working. Outstanding (unhedged) positions are reported "
                    + "below; anything listed here needs manual attention.%n", book.size());
            Thread.sleep(2_000);
            engine.outstanding().forEach((base, state) ->
                    System.out.printf("  !! %s %s%n", base, state));

            for (AutoCloseable stream : streams) {
                stream.close();
            }
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
            case "dydx" -> 0;
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
        @Override public SubmitResult placePostOnly(String s, Side side, BigDecimal q, BigDecimal p,
                                                    String clientOrderId) {
            throw new UnsupportedOperationException(venue + " gateway not implemented — "
                    + "opening only one leg would leave the book directional");
        }
        @Override public SubmitResult placeCappedIoc(String s, Side side, BigDecimal q,
                                                     BigDecimal worst, String clientOrderId) {
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
