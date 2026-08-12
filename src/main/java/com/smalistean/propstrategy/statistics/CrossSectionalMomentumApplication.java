package com.smalistean.propstrategy.statistics;

import com.smalistean.propstrategy.database.DatabaseConfig;

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
import java.util.Set;
import java.util.TreeMap;

/**
 * Cross-sectional momentum over the full USDT perpetual universe.
 *
 * <p>Parameters and the pass/fail bar are fixed in {@code XSMOM_PREREGISTRATION.md}, written before
 * the data was imported. This is a portfolio-level backtest, so it does not use
 * {@code BacktestEngine}: that engine models one instrument with a stop and a target, whereas this
 * holds tens of positions on both sides and rebalances on a schedule with no stops at all.
 *
 * <h2>Why this is structured as a long/short book</h2>
 * Every strategy tested before this one asked whether a single instrument would rise. Fifteen
 * correlated instruments then produced fifteen versions of the same bet: 590 trades collapsed to 26
 * independent blocks, and one favourable half-year accounted for an entire apparent edge. Ranking
 * within a cross-section and holding both sides cancels the common factor instead of repeating it.
 *
 * <h2>Survivorship</h2>
 * The panel is built from whatever klines exist on each date, so a symbol enters when it began
 * trading and leaves when it stopped. Coins that went to zero are present until they died - LUNAUSDT
 * ends 2022-05-13. Selecting the universe from currently-listed symbols would define it by which
 * coins survived, which is unknowable in advance and would corrupt both sides of the book.
 */
public final class CrossSectionalMomentumApplication {

    private record Bar(double close, double quoteVolume) { }

    public static void main(String[] args) throws Exception {
        int lookbackDays = Integer.getInteger("xsLookbackDays", 7);
        int holdDays = Integer.getInteger("xsHoldDays", 7);
        double fraction = Double.parseDouble(System.getProperty("xsFraction", "0.20"));
        double minimumDailyVolume = Double.parseDouble(System.getProperty("xsMinVolume", "10000000"));
        int minimumAgeDays = Integer.getInteger("xsMinAgeDays", 30);
        double costBpsPerSide = Double.parseDouble(System.getProperty("xsCostBps", "6.5"));
        boolean applyFunding = !"false".equals(System.getProperty("xsFunding", "true"));
        LocalDate from = LocalDate.parse(System.getProperty("xsFrom", "2021-01-01"));

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        TreeMap<LocalDate, Map<String, Bar>> panel = loadPanel(database, from);
        Map<String, TreeMap<LocalDate, Double>> funding = applyFunding
                ? loadFunding(database, from) : Map.of();
        List<LocalDate> days = new ArrayList<>(panel.keySet());
        System.out.printf("panel: %d days, %d symbols, %s .. %s%n",
                days.size(),
                panel.values().stream().flatMap(m -> m.keySet().stream()).collect(java.util.stream.Collectors.toSet()).size(),
                days.getFirst(), days.getLast());

        // Trailing 30-day median dollar volume and listing age, computed only from prior days.
        Map<String, LocalDate> firstSeen = new HashMap<>();
        for (LocalDate day : days) {
            for (String symbol : panel.get(day).keySet()) {
                firstSeen.putIfAbsent(symbol, day);
            }
        }

        List<Double> periodReturns = new ArrayList<>();
        List<LocalDate> periodDates = new ArrayList<>();
        Set<String> previousLongs = new HashSet<>();
        Set<String> previousShorts = new HashSet<>();
        int totalEligible = 0;
        int rebalances = 0;
        double turnoverSum = 0;

        for (int i = lookbackDays; i + holdDays < days.size(); i += holdDays) {
            LocalDate rankStart = days.get(i - lookbackDays);
            LocalDate rankEnd = days.get(i);
            LocalDate holdEnd = days.get(i + holdDays);
            Map<String, Bar> atEnd = panel.get(rankEnd);
            Map<String, Bar> atStart = panel.get(rankStart);
            Map<String, Bar> atExit = panel.get(holdEnd);

            record Ranked(String symbol, double past) { }
            List<Ranked> eligible = new ArrayList<>();
            for (String symbol : atEnd.keySet()) {
                Bar start = atStart.get(symbol);
                Bar end = atEnd.get(symbol);
                if (start == null || end == null || start.close() <= 0) continue;
                if (!atExit.containsKey(symbol)) {
                    // Delisted during the hold. Handled below rather than skipped, since dropping
                    // them would silently remove exactly the names a short book profits from.
                }
                if (java.time.temporal.ChronoUnit.DAYS.between(firstSeen.get(symbol), rankEnd) < minimumAgeDays) {
                    continue;
                }
                if (medianVolume(panel, days, i, symbol) < minimumDailyVolume) continue;
                eligible.add(new Ranked(symbol, end.close() / start.close() - 1.0));
            }
            if (eligible.size() < 20) continue;
            eligible.sort(Comparator.comparingDouble(Ranked::past));
            int k = Math.max(1, (int) Math.round(eligible.size() * fraction));
            List<String> shorts = eligible.subList(0, k).stream().map(Ranked::symbol).toList();
            List<String> longs = eligible.subList(eligible.size() - k, eligible.size())
                    .stream().map(Ranked::symbol).toList();

            double longReturn = basketReturn(longs, atEnd, atExit, panel, days, i, holdDays, funding, +1);
            double shortReturn = basketReturn(shorts, atEnd, atExit, panel, days, i, holdDays, funding, -1);
            double gross = (longReturn - shortReturn) / 2.0; // half capital each side

            Set<String> newLongs = new HashSet<>(longs);
            Set<String> newShorts = new HashSet<>(shorts);
            double turnover = turnover(previousLongs, newLongs) + turnover(previousShorts, newShorts);
            double cost = turnover * (costBpsPerSide / 10_000.0);
            periodReturns.add(gross - cost);
            periodDates.add(holdEnd);
            previousLongs = newLongs;
            previousShorts = newShorts;
            totalEligible += eligible.size();
            turnoverSum += turnover;
            rebalances++;
        }

        report(periodReturns, periodDates, holdDays, totalEligible, rebalances, turnoverSum,
                lookbackDays, fraction, applyFunding);
    }

    /** Fraction of the book replaced, counting both sides of each swap as capital traded. */
    private static double turnover(Set<String> previous, Set<String> current) {
        if (current.isEmpty()) return 0;
        long kept = current.stream().filter(previous::contains).count();
        double changed = (current.size() - kept) / (double) current.size();
        return changed * 0.5; // this side holds half the capital
    }

    /**
     * Basket return over the hold. A symbol that stops trading mid-hold is marked to its last
     * available close and then treated as fully liquidated - the honest treatment of a delisting,
     * and the reason the panel keeps dead coins at all.
     */
    private static double basketReturn(List<String> symbols, Map<String, Bar> atEnd,
                                       Map<String, Bar> atExit, TreeMap<LocalDate, Map<String, Bar>> panel,
                                       List<LocalDate> days, int index, int holdDays,
                                       Map<String, TreeMap<LocalDate, Double>> funding, int side) {
        double total = 0;
        for (String symbol : symbols) {
            double entry = atEnd.get(symbol).close();
            Double exit = atExit.containsKey(symbol) ? atExit.get(symbol).close() : null;
            if (exit == null) {
                for (int j = index + holdDays; j > index; j--) {
                    Bar bar = panel.get(days.get(j)).get(symbol);
                    if (bar != null) { exit = bar.close(); break; }
                }
            }
            double raw = exit == null ? 0 : exit / entry - 1.0;
            // Funding is paid by longs when positive. A long pays it; a short receives it.
            double paid = 0;
            TreeMap<LocalDate, Double> rates = funding.get(symbol);
            if (rates != null) {
                for (var e : rates.subMap(days.get(index), false, days.get(index + holdDays), true).entrySet()) {
                    paid += e.getValue();
                }
            }
            total += raw - side * paid;
        }
        return symbols.isEmpty() ? 0 : total / symbols.size();
    }

    private static double medianVolume(TreeMap<LocalDate, Map<String, Bar>> panel, List<LocalDate> days,
                                       int index, String symbol) {
        List<Double> volumes = new ArrayList<>();
        for (int j = Math.max(0, index - 30); j < index; j++) {
            Bar bar = panel.get(days.get(j)).get(symbol);
            if (bar != null) volumes.add(bar.quoteVolume());
        }
        if (volumes.size() < 15) return 0;
        volumes.sort(null);
        return volumes.get(volumes.size() / 2);
    }

    private static TreeMap<LocalDate, Map<String, Bar>> loadPanel(DatabaseConfig database, LocalDate from)
            throws Exception {
        TreeMap<LocalDate, Map<String, Bar>> panel = new TreeMap<>();
        String sql = """
                SELECT (open_time AT TIME ZONE 'UTC')::date AS d, symbol,
                       (ARRAY_AGG(close_price ORDER BY open_time DESC))[1] AS close,
                       SUM(quote_asset_volume) AS vol
                FROM futures_kline
                WHERE interval='1h' AND open_time >= ?
                GROUP BY 1, 2
                """;
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, from);
            statement.setFetchSize(100_000);
            connection.setAutoCommit(false);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    panel.computeIfAbsent(results.getObject(1, LocalDate.class), k -> new HashMap<>())
                            .put(results.getString(2),
                                    new Bar(results.getDouble(3), results.getDouble(4)));
                }
            }
        }
        return panel;
    }

    private static Map<String, TreeMap<LocalDate, Double>> loadFunding(DatabaseConfig database, LocalDate from)
            throws Exception {
        Map<String, TreeMap<LocalDate, Double>> funding = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT symbol, (funding_time AT TIME ZONE 'UTC')::date AS d, SUM(funding_rate)
                     FROM futures_funding_rate WHERE funding_time >= ? GROUP BY 1,2
                     """)) {
            statement.setObject(1, from);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    funding.computeIfAbsent(results.getString(1), k -> new TreeMap<>())
                            .merge(results.getObject(2, LocalDate.class), results.getDouble(3), Double::sum);
                }
            }
        }
        return funding;
    }

    private static void report(List<Double> returns, List<LocalDate> dates, int holdDays,
                               int totalEligible, int rebalances, double turnoverSum,
                               int lookbackDays, double fraction, boolean funding) {
        if (returns.isEmpty()) {
            System.out.println("no rebalances produced");
            return;
        }
        double periodsPerYear = 365.0 / holdDays;
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = returns.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / returns.size();
        double sd = Math.sqrt(variance);
        double annual = mean * periodsPerYear;
        double annualSd = sd * Math.sqrt(periodsPerYear);
        double t = mean / (sd / Math.sqrt(returns.size()));
        double equity = 1;
        double peak = 1;
        double maxDrawdown = 0;
        for (double r : returns) {
            equity *= (1 + r);
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, 1 - equity / peak);
        }
        System.out.printf("%nlookback %dd, hold %dd, top/bottom %.0f%%, funding=%s%n",
                lookbackDays, holdDays, fraction * 100, funding);
        System.out.printf("  rebalances %d   mean eligible universe %.0f   mean turnover %.1f%%%n",
                rebalances, totalEligible / (double) rebalances, 100 * turnoverSum / rebalances);
        System.out.printf("  NET  annual %+.1f%%   vol %.1f%%   Sharpe %.2f   t %.2f   maxDD %.1f%%   "
                        + "cumulative %+.1f%%%n",
                annual * 100, annualSd * 100, annual / annualSd, t, maxDrawdown * 100, (equity - 1) * 100);
    }
}
