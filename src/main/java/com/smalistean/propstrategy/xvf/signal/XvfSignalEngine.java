package com.smalistean.propstrategy.xvf.signal;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.xvf.XvfConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks XVF candidates. The single source of truth for what the book should be.
 *
 * <p>Both the reporting application and the execution application call this. Duplicating the ranking
 * would let the book you look at and the book you trade drift apart, which is the same class of
 * mistake as the join key that split one asset into three - it produces no error, just two answers.
 */
public final class XvfSignalEngine {

    /** One venue's trailing funding for one asset. */
    public record Leg(String venue, String venueSymbol, double trailingRate, double weeklyQuoteVolume) { }

    /** A tradeable pair: short the venue paying more, long the one paying less. */
    public record Candidate(String base, Leg shortLeg, Leg longLeg, double spreadAnnualPct,
                            double thinLegWeeklyVolume) { }

    private XvfSignalEngine() {
    }

    /**
     * Refuses to return a book unless every venue contributes enough USABLE symbols.
     *
     * <p>Counts symbols that survive {@link XvfConfig#COMPLETENESS_RATIO} - the same filter
     * {@link #topBook} ranks with - rather than symbols that merely have a row. Those are wildly
     * different numbers, and the difference is the whole failure. Measured 2026-08-15:
     *
     * <pre>
     *   venue         present   usable    latest
     *   binance           730       33    08-14
     *   bybit             775       40    08-12
     *   dydx              296        0    08-13
     *   hyperliquid       232        0    08-12
     * </pre>
     *
     * <p>Two of four venues contributed nothing at all, and a presence check waved all four through
     * on 296 and 232 symbols. The book came out at 4 names instead of 20 with no warning. A symbol
     * whose trailing window is partial is not a missing symbol - it is a symbol reporting a low rate,
     * because the trailing figure is a sum - so counting presence measures the wrong thing entirely.
     *
     * <p>Symbol count is still the quantity checked, not the latest timestamp: Binance's sixteen
     * old-universe symbols carry live rows while the other 800 depend on a monthly archive, so a
     * timestamp check reports the venue current when 98% of it is missing.
     */
    public static void requireFreshFunding(DatabaseConfig database, LocalDate asOf) throws Exception {
        String sql = """
                WITH cur AS (
                  SELECT venue, venue_symbol, count(*) AS n
                  FROM perp_funding_all
                  WHERE venue = ANY (?)
                    AND funding_time >  ?::date - ?::int
                    AND funding_time <= ?::date
                  GROUP BY 1, 2),
                typical AS (
                  SELECT venue, venue_symbol, percentile_cont(0.5) WITHIN GROUP (ORDER BY n) AS med
                  FROM (SELECT venue, venue_symbol, date_trunc('week', funding_time) w, count(*) n
                        FROM perp_funding_all
                        WHERE venue = ANY (?) AND funding_time >= ?::date - ?::int
                        GROUP BY 1,2,3) x
                  GROUP BY 1, 2),
                latest AS (
                  SELECT venue, max(funding_time) AS at FROM perp_funding_all
                  WHERE venue = ANY (?) GROUP BY 1)
                SELECT c.venue, count(*) AS present,
                       count(*) FILTER (WHERE c.n >= ? * t.med) AS usable,
                       max(l.at)::date AS latest
                FROM cur c
                JOIN typical t USING (venue, venue_symbol)
                JOIN latest l ON l.venue = c.venue
                GROUP BY 1
                """;
        List<String> problems = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            java.sql.Array venues = connection.createArrayOf("text", XvfConfig.VENUES);
            statement.setArray(1, venues);
            statement.setObject(2, asOf);
            statement.setInt(3, XvfConfig.LOOKBACK_DAYS);
            statement.setObject(4, asOf);
            statement.setArray(5, venues);
            statement.setObject(6, asOf);
            statement.setInt(7, XvfConfig.TYPICAL_WINDOW_DAYS);
            statement.setArray(8, venues);
            statement.setDouble(9, XvfConfig.COMPLETENESS_RATIO);

            Map<String, Integer> usableByVenue = new HashMap<>();
            System.out.printf("  %-12s %8s %8s  %s%n", "venue", "present", "usable", "latest");
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    String venue = results.getString(1);
                    int usable = results.getInt(3);
                    usableByVenue.put(venue, usable);
                    System.out.printf("  %-12s %8d %8d  %s%n",
                            venue, results.getInt(2), usable, results.getString(4));
                }
            }
            for (String venue : XvfConfig.VENUES) {
                int usable = usableByVenue.getOrDefault(venue, 0);
                if (usable < XvfConfig.MIN_USABLE_SYMBOLS) {
                    problems.add("%s has only %d symbols with a complete %d-day window"
                            .formatted(venue, usable, XvfConfig.LOOKBACK_DAYS));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("STALE FUNDING DATA, refusing to produce a book: "
                    + String.join("; ", problems)
                    + ". Run scripts/xvf-refresh.sh first.");
        }
    }

    /**
     * {@link #rankedCandidates}, capped at {@link XvfConfig#POSITIONS}. What the reporting
     * application shows as "the book."
     */
    public static List<Candidate> topBook(DatabaseConfig database, LocalDate asOf) throws Exception {
        List<Candidate> all = rankedCandidates(database, asOf);
        return all.size() > XvfConfig.POSITIONS ? all.subList(0, XvfConfig.POSITIONS) : all;
    }

    /**
     * {@link #rankedCandidates}, uncapped. What the execution application walks, so a candidate that
     * ranks inside the top {@link XvfConfig#POSITIONS} by spread but can never actually be opened -
     * CAT's step size, ON's ticker collision - costs one wasted slot rather than one permanently empty
     * one. {@code topBook} cannot do this backfill itself: it has no venue gateway and so no way to
     * know a candidate is untradeable until the execution application tries it.
     */
    public static List<Candidate> fullBook(DatabaseConfig database, LocalDate asOf) throws Exception {
        return rankedCandidates(database, asOf);
    }

    /**
     * Trailing funding per venue and asset, paired into the widest spread, and ranked. Every candidate
     * that clears the spread and volume floors, in rank order - callers decide how many they can use.
     *
     * <p>Weekly quote volume comes from the venue kline tables so the participation cap is applied to
     * a real figure. Where a venue has no price row the leg is dropped rather than defaulted -
     * REN paid 507% annualised on $289 of weekly volume, and a default would have let it through.
     */
    private static List<Candidate> rankedCandidates(DatabaseConfig database, LocalDate asOf) throws Exception {
        String sql = """
                WITH trail AS (
                  SELECT venue, venue_symbol, sum(funding_rate) AS rate, count(*) AS payments
                  FROM perp_funding_all
                  WHERE venue = ANY (?)
                    AND funding_time >  ?::date - ?::int
                    AND funding_time <= ?::date
                  GROUP BY 1, 2),
                typical AS (
                  SELECT venue, venue_symbol, percentile_cont(0.5) WITHIN GROUP (ORDER BY n) AS med
                  FROM (SELECT venue, venue_symbol, date_trunc('week', funding_time) w, count(*) n
                        FROM perp_funding_all
                        WHERE venue = ANY (?) AND funding_time >= ?::date - ?::int
                        GROUP BY 1,2,3) x
                  GROUP BY 1,2)
                SELECT t.venue, t.venue_symbol, t.rate
                FROM trail t
                JOIN typical y USING (venue, venue_symbol)
                WHERE t.payments >= ? * y.med
                """;
        // Liquidity comes from the venues, not from the kline backfill. See LiveVolume for why.
        Map<String, Double> volume24h = LiveVolume.fetch();
        List<Leg> legs = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            java.sql.Array venues = connection.createArrayOf("text", XvfConfig.VENUES);
            statement.setArray(1, venues);
            statement.setObject(2, asOf);
            statement.setInt(3, XvfConfig.LOOKBACK_DAYS);
            statement.setObject(4, asOf);
            statement.setArray(5, venues);
            statement.setObject(6, asOf);
            statement.setInt(7, XvfConfig.TYPICAL_WINDOW_DAYS);
            statement.setDouble(8, XvfConfig.COMPLETENESS_RATIO);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    String venue = results.getString(1);
                    String symbol = results.getString(2);
                    // x7 converts a 24h figure to the weekly basis the floor is expressed in.
                    double weekly = volume24h.getOrDefault(venue + "|" + symbol, 0.0) * 7;
                    legs.add(new Leg(venue, symbol, results.getDouble(3), weekly));
                }
            }
        }

        Map<String, List<Leg>> byBase = new HashMap<>();
        for (Leg leg : legs) {
            byBase.computeIfAbsent(XvfConfig.normaliseBase(leg.venue(), leg.venueSymbol()),
                    k -> new ArrayList<>()).add(leg);
        }

        List<Candidate> out = new ArrayList<>();
        for (var entry : byBase.entrySet()) {
            List<Leg> venueLegs = entry.getValue();
            if (venueLegs.size() < 2) {
                continue;
            }
            Leg expensive = bestCrossVenuePair(venueLegs);
            if (expensive == null) {
                continue;   // every leg sits on one venue
            }
            Leg cheap = venueLegs.stream()
                    .filter(leg -> !leg.venue().equals(expensive.venue()))
                    .min(Comparator.comparingDouble(Leg::trailingRate)).orElseThrow();
            double spread = (expensive.trailingRate() - cheap.trailingRate())
                    * (365.0 / XvfConfig.LOOKBACK_DAYS) * 100;
            double thin = Math.min(expensive.weeklyQuoteVolume(), cheap.weeklyQuoteVolume());
            if (spread > XvfConfig.MIN_SPREAD_ANNUAL_PCT && thin >= XvfConfig.MIN_WEEKLY_QUOTE_VOLUME) {
                out.add(new Candidate(entry.getKey(), expensive, cheap, spread, thin));
            }
        }
        out.sort(Comparator.comparingDouble(Candidate::spreadAnnualPct).reversed());
        return out;
    }

    /**
     * The short leg of the widest spread whose two legs sit on DIFFERENT venues, or null if they
     * cannot.
     *
     * <p>Taking a plain max and min lets both legs land on one exchange. Binance lists KAITOUSDC and
     * KAITOUSDT, both normalising to KAITO, and on 2026-08-15 they were the widest "spread" for that
     * base at 38.3% — a pair the engine would have placed, because nothing downstream objects:
     * {@code isThinner("binance","binance")} is true on the {@code <=}, so maker and taker resolve to
     * the same gateway. A USDT/USDC funding spread on one venue is a real trade, but it is
     * cross-margined with no withdrawal latency and no second-venue risk, so none of the measurement
     * behind XVF describes it.
     *
     * <p>Skipping such a base outright would be wrong too: one with two Binance contracts AND a Bybit
     * leg still has a valid cross-venue pair. So the widest legitimate combination is chosen instead,
     * evaluated from both ends because the best short and the best long need not be the extremes of
     * the whole set once a venue is excluded.
     */
    private static Leg bestCrossVenuePair(List<Leg> legs) {
        Leg best = null;
        double bestSpread = Double.NEGATIVE_INFINITY;
        for (Leg candidate : legs) {
            double cheapest = legs.stream()
                    .filter(leg -> !leg.venue().equals(candidate.venue()))
                    .mapToDouble(Leg::trailingRate).min().orElse(Double.NaN);
            if (Double.isNaN(cheapest)) {
                continue;
            }
            double spread = candidate.trailingRate() - cheapest;
            if (spread > bestSpread) {
                bestSpread = spread;
                best = candidate;
            }
        }
        return best;
    }
}
