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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Ranks XVF candidates. The single source of truth for what the book should be.
 *
 * <p>Both the reporting application and the execution application call this. Duplicating the ranking
 * would let the book you look at and the book you trade drift apart, which is the same class of
 * mistake as the join key that split one asset into three - it produces no error, just two answers.
 */
public final class XvfSignalEngine {

    /**
     * One venue's trailing funding for one asset, summed over both lookback windows since which one
     * applies depends on the venue it ends up paired against, not on this leg alone.
     */
    public record Leg(String venue, String venueSymbol, double rateCexDex, double rateCexCex,
                      double weeklyQuoteVolume, double price) {
        double annualPct(boolean cexDex) {
            return cexDex
                    ? rateCexDex * (365.0 / XvfConfig.LOOKBACK_DAYS) * 100
                    : rateCexCex * (365.0 / XvfConfig.LOOKBACK_DAYS_CEX_CEX) * 100;
        }
    }

    /** A tradeable pair: short the venue paying more, long the one paying less. */
    public record Candidate(String base, Leg shortLeg, Leg longLeg, double spreadAnnualPct,
                            double thinLegWeeklyVolume) { }

    /** Venue combination used to choose the applicable trailing-funding window. */
    public enum PairType { CEX_CEX, CEX_DEX, DEX_DEX }

    /**
     * One unique cross-venue pairing before any spread, volume, or freshness gate is applied.
     *
     * <p>This diagnostic model deliberately sits beside {@link Candidate}, rather than replacing it:
     * production still trades the widest pair per base with the exact legacy selection semantics.
     */
    public record PairAlternative(
            String base,
            Leg shortLeg,
            Leg longLeg,
            PairType pairType,
            double rawSpreadAnnualPct,
            double thinLegWeeklyVolume) {

        public PairAlternative {
            Objects.requireNonNull(base, "base");
            Objects.requireNonNull(shortLeg, "shortLeg");
            Objects.requireNonNull(longLeg, "longLeg");
            Objects.requireNonNull(pairType, "pairType");
            if (shortLeg.venue().equals(longLeg.venue())) {
                throw new IllegalArgumentException("A pair alternative must cross venues");
            }
        }
    }

    /**
     * The raw and adjusted inputs, gates, and legacy-book membership for one pair alternative.
     *
     * <p>{@code eligibleYesterday} intentionally retains the current base-level definition: it is
     * true when yesterday's widest pair for this base cleared the raw spread and volume gates. It is
     * not pair-identity freshness. Changing that definition would change the production book.
     */
    public record EvaluatedPair(
            int grossRank,
            PairAlternative alternative,
            boolean eligibleYesterday,
            double staleDiscountFactor,
            double adjustedSpreadAnnualPct,
            boolean widestForBase,
            boolean rawSpreadPass,
            boolean volumePass,
            boolean adjustedSpreadPass,
            Integer baselineBookRank) {

        public EvaluatedPair {
            if (grossRank <= 0) {
                throw new IllegalArgumentException("grossRank must be positive");
            }
            Objects.requireNonNull(alternative, "alternative");
            if (baselineBookRank != null && baselineBookRank <= 0) {
                throw new IllegalArgumentException("baselineBookRank must be positive when present");
            }
        }
    }

    /**
     * A report-only evaluation containing every complete-input cross-venue alternative and the
     * unchanged production full book projected from the same inputs.
     */
    public record SignalEvaluation(
            LocalDate asOf,
            List<EvaluatedPair> alternatives,
            List<Candidate> baselineFullBook) {

        public SignalEvaluation {
            Objects.requireNonNull(asOf, "asOf");
            alternatives = List.copyOf(Objects.requireNonNull(alternatives, "alternatives"));
            baselineFullBook = List.copyOf(
                    Objects.requireNonNull(baselineFullBook, "baselineFullBook"));
        }
    }

    /** Hyperliquid is the DEX leg; a pair touching it is CEX-DEX, otherwise CEX-CEX. */
    private static final Set<String> DEX_VENUES = Set.of("hyperliquid");

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
     * Evaluates every cross-venue alternative while retaining the exact production book projection.
     *
     * <p>The same live-volume snapshot is used for today and yesterday, matching the pre-existing
     * freshness calculation. Callers that persist diagnostics should use this one result rather than
     * calling {@link #fullBook} separately and taking a second live-volume snapshot.
     */
    public static SignalEvaluation evaluate(DatabaseConfig database, LocalDate asOf) throws Exception {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(asOf, "asOf");
        LiveVolume.Snapshot snapshot = LiveVolume.fetchSnapshot();
        return evaluateLoadedLegs(
                asOf,
                loadCompleteLegs(database, asOf, snapshot),
                loadCompleteLegs(database, asOf.minusDays(1), snapshot));
    }

    /**
     * {@link #rankedCandidatesRaw}, with the signal discounted for every candidate that was already
     * eligible yesterday.
     *
     * <p>Measured 2024-01 to 2026-08 by comparing what a candidate's trailing signal read against what
     * it went on to actually realise over the following hold, with the forward window starting the
     * day after the signal (an earlier version shared day 1 between the two windows, letting a large
     * print count on both sides - see XvfConfig.STALE_SIGNAL_DISCOUNT's javadoc): a candidate on its
     * FIRST eligible day reads 43% of realised for CEX-CEX, 66% for CEX-DEX; a candidate ALSO eligible
     * the day before reads roughly 29-31%/43-56% of realised, flat across every later streak length
     * tested. The gap between fresh and stale is real but smaller than first measured; the larger,
     * separate finding is that even a fresh signal over-reads its own forward realisation by more than
     * 2x, which this discount does not address at all.
     *
     * <p>Applied as a flat discount rather than a smooth decay because the ratio does not decay further
     * past the first extra day - streak 2, streak 3-5 and streak 6+ all measured within a few points of
     * each other, so a single step from "first day" to "not first day" captures the effect.
     */
    private static List<Candidate> rankedCandidates(DatabaseConfig database, LocalDate asOf) throws Exception {
        // Preserve the mutable-list shape returned before SignalEvaluation became available.
        return new ArrayList<>(evaluate(database, asOf).baselineFullBook());
    }

    /**
     * Trailing funding per venue and asset, paired into the widest spread, and ranked. Every candidate
     * that clears the spread and volume floors, in rank order - callers decide how many they can use.
     *
     * <p>Weekly quote volume comes from each venue's live ticker through {@link LiveVolume}. A missing
     * ticker becomes zero volume and therefore fails the liquidity gate - REN paid 507% annualised on
     * $289 of weekly volume, and an optimistic default would have let it through.
     *
     * <p>Each leg is summed over BOTH lookback windows: which one is the right one is not known until
     * pairing decides whether the pair is CEX-CEX or CEX-DEX. The CEX-CEX completeness check is
     * scaled by {@code LOOKBACK_DAYS_CEX_CEX / 7.0} against the same weekly median, since 90% of a
     * week's payments inside a 3-day window is not the bar a real symbol clears.
     *
     * <p>Undiscounted - {@link #rankedCandidates} is what applies the freshness discount, calling this
     * twice (today and yesterday) to see which candidates are new. {@code snapshot} is a parameter
     * rather than fetched here so that second call does not hit every venue's live ticker again for
     * data that has not changed between the two calls.
     *
     * <p>Binance and Bybit legs are restricted to USDT-quoted symbols. {@link XvfConfig#collateral}
     * documents both venues as USDT-only by design, but nothing enforced that here - the query pulled
     * whatever symbol existed in {@code perp_funding_all} for a base with no preference for the quote
     * asset. Binance also lists USDC-margined contracts on some bases (BOMEUSDC alongside BOMEUSDT),
     * quote-agnostic to a spread comparison but drawing on a SEPARATE USDC collateral wallet the
     * account does not fund. Measured live 2026-08-22: BOMEUSDC won the day's widest spread against
     * Hyperliquid, its Hyperliquid maker leg filled, and its Binance hedge was rejected five times with
     * "Margin is insufficient" - real USDT sat free the whole time, because the order needed USDC. The
     * pair went UNHEDGED_ALERT with a naked $113 Hyperliquid short. {@code bestCrossVenuePair}'s own
     * javadoc already covers the SAME-venue USDT/USDC collision (KAITOUSDT vs KAITOUSDC on Binance
     * alone); this is the cross-venue case that guard never touched, since a Binance leg racing a
     * Hyperliquid or Bybit leg never trips the same-venue check at all.
     */
    private static List<Leg> loadCompleteLegs(DatabaseConfig database, LocalDate asOf,
                                              LiveVolume.Snapshot snapshot) throws Exception {
        String sql = """
                WITH trail_cex_dex AS (
                  SELECT venue, venue_symbol, sum(funding_rate) AS rate, count(*) AS payments
                  FROM perp_funding_all
                  WHERE venue = ANY (?)
                    AND funding_time >  ?::date - ?::int
                    AND funding_time <= ?::date
                  GROUP BY 1, 2),
                trail_cex_cex AS (
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
                SELECT d.venue, d.venue_symbol, d.rate, c.rate
                FROM trail_cex_dex d
                JOIN trail_cex_cex c USING (venue, venue_symbol)
                JOIN typical y USING (venue, venue_symbol)
                WHERE d.payments >= ? * y.med
                  AND c.payments >= ? * y.med * ? / 7.0
                  AND (d.venue <> ALL (ARRAY['binance','bybit']) OR d.venue_symbol LIKE '%USDT')
                """;
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
            statement.setInt(7, XvfConfig.LOOKBACK_DAYS_CEX_CEX);
            statement.setObject(8, asOf);
            statement.setArray(9, venues);
            statement.setObject(10, asOf);
            statement.setInt(11, XvfConfig.TYPICAL_WINDOW_DAYS);
            statement.setDouble(12, XvfConfig.COMPLETENESS_RATIO);
            statement.setDouble(13, XvfConfig.COMPLETENESS_RATIO);
            statement.setInt(14, XvfConfig.LOOKBACK_DAYS_CEX_CEX);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    String venue = results.getString(1);
                    String symbol = results.getString(2);
                    // x7 converts a 24h figure to the weekly basis the floor is expressed in.
                    double weekly = snapshot.volume().getOrDefault(venue + "|" + symbol, 0.0) * 7;
                    double price = snapshot.price().getOrDefault(venue + "|" + symbol, 0.0);
                    legs.add(new Leg(venue, symbol, results.getDouble(3), results.getDouble(4),
                            weekly, price));
                }
            }
        }

        return legs;
    }

    /** Pure seam used by the shadow diagnostic and its characterization tests. */
    static SignalEvaluation evaluateLoadedLegs(LocalDate asOf, List<Leg> todayLegs,
                                                List<Leg> yesterdayLegs) {
        Objects.requireNonNull(asOf, "asOf");
        RawDay today = rankedCandidatesRaw(todayLegs);
        RawDay yesterday = rankedCandidatesRaw(yesterdayLegs);

        Set<String> eligibleYesterday = new HashSet<>();
        for (Candidate candidate : yesterday.baselineEligible()) {
            eligibleYesterday.add(candidate.base());
        }

        // This is the old rankedCandidates loop verbatim in its choice, arithmetic, gates, and sort.
        List<Candidate> baseline = new ArrayList<>();
        for (Candidate candidate : today.baselineEligible()) {
            boolean fresh = !eligibleYesterday.contains(candidate.base());
            double adjusted = fresh
                    ? candidate.spreadAnnualPct()
                    : candidate.spreadAnnualPct() * XvfConfig.STALE_SIGNAL_DISCOUNT;
            if (adjusted > XvfConfig.MIN_SPREAD_ANNUAL_PCT) {
                baseline.add(new Candidate(
                        candidate.base(), candidate.shortLeg(), candidate.longLeg(), adjusted,
                        candidate.thinLegWeeklyVolume()));
            }
        }
        baseline.sort(Comparator.comparingDouble(Candidate::spreadAnnualPct).reversed());

        Map<PairKey, Integer> baselineRanks = new HashMap<>();
        for (int index = 0; index < baseline.size(); index++) {
            baselineRanks.put(PairKey.of(baseline.get(index)), index + 1);
        }

        List<EvaluatedPairDraft> drafts = new ArrayList<>();
        for (RawAlternative raw : today.alternatives()) {
            PairAlternative alternative = raw.alternative();
            boolean wasEligibleYesterday = eligibleYesterday.contains(alternative.base());
            double discount = wasEligibleYesterday ? XvfConfig.STALE_SIGNAL_DISCOUNT : 1.0;
            double adjusted = wasEligibleYesterday
                    ? alternative.rawSpreadAnnualPct() * XvfConfig.STALE_SIGNAL_DISCOUNT
                    : alternative.rawSpreadAnnualPct();
            drafts.add(new EvaluatedPairDraft(
                    alternative,
                    wasEligibleYesterday,
                    discount,
                    adjusted,
                    raw.widestForBase(),
                    alternative.rawSpreadAnnualPct() > XvfConfig.MIN_SPREAD_ANNUAL_PCT,
                    alternative.thinLegWeeklyVolume() >= XvfConfig.MIN_WEEKLY_QUOTE_VOLUME,
                    adjusted > XvfConfig.MIN_SPREAD_ANNUAL_PCT,
                    baselineRanks.get(PairKey.of(alternative))));
        }
        drafts.sort(Comparator
                .comparingDouble((EvaluatedPairDraft draft) ->
                        draft.alternative().rawSpreadAnnualPct()).reversed()
                .thenComparing(draft -> draft.alternative().base())
                .thenComparing(draft -> draft.alternative().shortLeg().venue())
                .thenComparing(draft -> draft.alternative().shortLeg().venueSymbol())
                .thenComparing(draft -> draft.alternative().longLeg().venue())
                .thenComparing(draft -> draft.alternative().longLeg().venueSymbol()));

        List<EvaluatedPair> evaluated = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            EvaluatedPairDraft draft = drafts.get(index);
            evaluated.add(new EvaluatedPair(
                    index + 1,
                    draft.alternative(),
                    draft.eligibleYesterday(),
                    draft.staleDiscountFactor(),
                    draft.adjustedSpreadAnnualPct(),
                    draft.widestForBase(),
                    draft.rawSpreadPass(),
                    draft.volumePass(),
                    draft.adjustedSpreadPass(),
                    draft.baselineBookRank()));
        }
        return new SignalEvaluation(asOf, evaluated, baseline);
    }

    private static RawDay rankedCandidatesRaw(List<Leg> legs) {
        Map<String, List<Leg>> byBase = new HashMap<>();
        for (Leg leg : legs) {
            byBase.computeIfAbsent(XvfConfig.normaliseBase(leg.venue(), leg.venueSymbol()),
                    k -> new ArrayList<>()).add(leg);
        }

        List<Candidate> out = new ArrayList<>();
        List<RawAlternative> alternatives = new ArrayList<>();
        for (var entry : byBase.entrySet()) {
            List<Leg> venueLegs = entry.getValue();
            if (venueLegs.size() < 2) {
                continue;
            }
            Candidate best = bestCrossVenuePair(entry.getKey(), venueLegs);
            if (best == null) {
                continue;   // every leg sits on one venue
            }
            for (PairAlternative alternative : allCrossVenuePairs(entry.getKey(), venueLegs)) {
                alternatives.add(new RawAlternative(
                        alternative, PairKey.of(alternative).equals(PairKey.of(best))));
            }
            if (best.spreadAnnualPct() > XvfConfig.MIN_SPREAD_ANNUAL_PCT
                    && best.thinLegWeeklyVolume() >= XvfConfig.MIN_WEEKLY_QUOTE_VOLUME
                    && !failsAdverseBasis(best)) {
                out.add(best);
            }
        }
        out.sort(Comparator.comparingDouble(Candidate::spreadAnnualPct).reversed());
        return new RawDay(List.copyOf(out), List.copyOf(alternatives));
    }

    /**
     * True when a candidate's live entry basis is adverse enough to reject - see
     * {@link XvfConfig#ADVERSE_ENTRY_BASIS_FLOOR_BPS} for the measurement behind the floor.
     *
     * <p>NaN (either leg's live price was unavailable) never fails this: "not measured" and "flat"
     * are different things, and treating a missing price as adverse would reject candidates for a
     * ticker outage rather than a real signal.
     */
    private static boolean failsAdverseBasis(Candidate candidate) {
        double basisBps = entryBasisBps(candidate.shortLeg(), candidate.longLeg());
        return !Double.isNaN(basisBps) && basisBps < XvfConfig.ADVERSE_ENTRY_BASIS_FLOOR_BPS;
    }

    /**
     * {@code ln(shortPrice / longPrice) * 10000} - the venue about to be shorted, priced against the
     * venue going long. Negative means the short venue is already cheap against the long venue before
     * the position is even opened. NaN when either leg's live price is unavailable.
     */
    private static double entryBasisBps(Leg shortLeg, Leg longLeg) {
        if (shortLeg.price() <= 0 || longLeg.price() <= 0) {
            return Double.NaN;
        }
        return Math.log(shortLeg.price() / longLeg.price()) * 10_000;
    }

    private static List<PairAlternative> allCrossVenuePairs(String base, List<Leg> legs) {
        List<PairAlternative> alternatives = new ArrayList<>();
        for (int leftIndex = 0; leftIndex < legs.size(); leftIndex++) {
            Leg left = legs.get(leftIndex);
            for (int rightIndex = leftIndex + 1; rightIndex < legs.size(); rightIndex++) {
                Leg right = legs.get(rightIndex);
                if (left.venue().equals(right.venue())) {
                    continue;
                }
                PairType pairType = pairType(left, right);
                boolean cexDex = pairType != PairType.CEX_CEX;
                double leftAnnual = left.annualPct(cexDex);
                double rightAnnual = right.annualPct(cexDex);
                Leg shortLeg = leftAnnual >= rightAnnual ? left : right;
                Leg longLeg = leftAnnual >= rightAnnual ? right : left;
                double spread = leftAnnual >= rightAnnual
                        ? leftAnnual - rightAnnual
                        : rightAnnual - leftAnnual;
                alternatives.add(new PairAlternative(
                        base,
                        shortLeg,
                        longLeg,
                        pairType,
                        spread,
                        Math.min(shortLeg.weeklyQuoteVolume(), longLeg.weeklyQuoteVolume())));
            }
        }
        return alternatives;
    }

    private static PairType pairType(Leg left, Leg right) {
        boolean leftDex = DEX_VENUES.contains(left.venue());
        boolean rightDex = DEX_VENUES.contains(right.venue());
        if (leftDex && rightDex) {
            return PairType.DEX_DEX;
        }
        return leftDex || rightDex ? PairType.CEX_DEX : PairType.CEX_CEX;
    }

    private record RawDay(List<Candidate> baselineEligible, List<RawAlternative> alternatives) { }

    private record RawAlternative(PairAlternative alternative, boolean widestForBase) { }

    private record EvaluatedPairDraft(
            PairAlternative alternative,
            boolean eligibleYesterday,
            double staleDiscountFactor,
            double adjustedSpreadAnnualPct,
            boolean widestForBase,
            boolean rawSpreadPass,
            boolean volumePass,
            boolean adjustedSpreadPass,
            Integer baselineBookRank) { }

    private record PairKey(
            String base,
            String shortVenue,
            String shortVenueSymbol,
            String longVenue,
            String longVenueSymbol) {

        private static PairKey of(Candidate candidate) {
            return new PairKey(
                    candidate.base(),
                    candidate.shortLeg().venue(),
                    candidate.shortLeg().venueSymbol(),
                    candidate.longLeg().venue(),
                    candidate.longLeg().venueSymbol());
        }

        private static PairKey of(PairAlternative alternative) {
            return new PairKey(
                    alternative.base(),
                    alternative.shortLeg().venue(),
                    alternative.shortLeg().venueSymbol(),
                    alternative.longLeg().venue(),
                    alternative.longLeg().venueSymbol());
        }
    }

    /**
     * The widest legitimate cross-venue spread for one base, or null if every leg sits on one venue.
     *
     * <p>Evaluated as every ordered (short, long) pair of legs on different venues, because which
     * lookback window applies - and so the annualised rate itself - depends on which two venues end
     * up paired: hyperliquid on either side makes it CEX-DEX ({@link XvfConfig#LOOKBACK_DAYS}),
     * anything else is CEX-CEX ({@link XvfConfig#LOOKBACK_DAYS_CEX_CEX}). A base has at most three
     * legs, so the full scan costs nothing.
     *
     * <p>Same-venue combinations are excluded by the venue check in the inner loop. Binance lists
     * KAITOUSDC and KAITOUSDT, both normalising to KAITO, and on 2026-08-15 they were the widest
     * "spread" for that base at 38.3% — a pair the engine would have placed, because nothing
     * downstream objects: {@code isThinner("binance","binance")} is true on the {@code <=}, so maker
     * and taker resolve to the same gateway. A USDT/USDC funding spread on one venue is a real trade,
     * but it is cross-margined with no withdrawal latency and no second-venue risk, so none of the
     * measurement behind XVF describes it.
     *
     * <p>Skipping such a base outright would be wrong too: one with two Binance contracts AND a Bybit
     * leg still has a valid cross-venue pair. So the widest legitimate combination is chosen instead.
     */
    private static Candidate bestCrossVenuePair(String base, List<Leg> legs) {
        Leg bestShort = null;
        Leg bestLong = null;
        double bestSpread = Double.NEGATIVE_INFINITY;
        for (Leg a : legs) {
            for (Leg b : legs) {
                if (a.venue().equals(b.venue())) {
                    continue;
                }
                boolean cexDex = DEX_VENUES.contains(a.venue()) || DEX_VENUES.contains(b.venue());
                double spread = a.annualPct(cexDex) - b.annualPct(cexDex);
                if (spread > bestSpread) {
                    bestSpread = spread;
                    bestShort = a;
                    bestLong = b;
                }
            }
        }
        if (bestShort == null) {
            return null;
        }
        double thin = Math.min(bestShort.weeklyQuoteVolume(), bestLong.weeklyQuoteVolume());
        return new Candidate(base, bestShort, bestLong, bestSpread, thin);
    }
}
