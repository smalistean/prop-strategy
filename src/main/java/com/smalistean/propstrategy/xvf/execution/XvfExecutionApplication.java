package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Candidate;
import com.smalistean.propstrategy.xvf.venue.BinanceGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.Side;

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
 *   -DbinanceApiKey=... -DbinanceApiSecret=...
 * </pre>
 *
 * <h2>What is wired and what is not</h2>
 * Binance is implemented. Bybit, Hyperliquid and dYdX resolve to {@link UnwiredGateway}, which
 * refuses loudly rather than silently skipping — a book that quietly opens only its Binance legs is
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
        for (String venue : new String[] {"bybit", "hyperliquid", "dydx"}) {
            gateways.put(venue, new UnwiredGateway(venue));
        }

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

                BigDecimal price = referencePrice(makerGw, makerSymbol);
                BigDecimal quantity = BigDecimal.valueOf(capped)
                        .divide(price, 8, RoundingMode.DOWN);

                System.out.printf("%-10s maker %-12s %-4s %-16s | taker %-12s %-4s %-16s | %6.0f USD%n",
                        t.base(), makerGw.name(), makerSide, makerSymbol,
                        takerGw.name(), takerSide, takerSymbol, capped);

                engine.open(t.base(),
                        new PairedEntryEngine.Leg(makerGw, makerSymbol, makerSide, quantity),
                        new PairedEntryEngine.Leg(takerGw, takerSymbol, takerSide, quantity),
                        price);
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

    private static BigDecimal referencePrice(VenueGateway gateway, String symbol) {
        // Placeholder: the live implementation reads the best bid/ask from the venue's book so the
        // post-only order rests at the touch. A mid or last price can cross and be rejected, which
        // is safe but wastes the entry.
        return BigDecimal.ONE;
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
        @Override public OrderHandle placePostOnly(String s, Side side, BigDecimal q, BigDecimal p) {
            throw new UnsupportedOperationException(venue + " gateway not implemented — "
                    + "opening only one leg would leave the book directional");
        }
        @Override public OrderHandle placeMarket(String s, Side side, BigDecimal q) {
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
