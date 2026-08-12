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
 * Cash-and-carry funding harvest: long spot, short perpetual, on the same asset.
 *
 * <p>Configuration and the pass/fail bar are fixed in {@code CARRY_PREREGISTRATION.md}, written
 * before the spot import.
 *
 * <h2>Why the accounting here is deliberately unflattering</h2>
 * <ul>
 *   <li><b>Capital is both legs.</b> A hedged position ties up notional in spot <em>and</em> margin
 *       plus exposure in the perp. Returns are divided by the full two-leg capital, not netted down
 *       to the margin, which would inflate them by roughly double.</li>
 *   <li><b>Basis drift is charged, not assumed away.</b> PnL carries {@code spotReturn - perpReturn}
 *       explicitly. The hedge cancels direction, not the spread between the two legs, and that spread
 *       is where a "market-neutral" book actually loses money.</li>
 *   <li><b>Funding is the realised per-symbol sum</b> over the hold at whatever interval Binance
 *       used - three, six or twenty-four payments a day. A short perp receives positive funding and
 *       pays negative funding.</li>
 *   <li><b>Symbols are excluded for their first 30 days.</b> Newly listed perps are funded hourly and
 *       average -456% annualised on those days: Binance raises the frequency when the perp
 *       dislocates, which is exactly when a short bleeds. Their median funding matches mature
 *       symbols but their mean is negative, so the tail consumes the carry.</li>
 * </ul>
 */
public final class CarryHarvestApplication {

    private record Bar(double close, double quoteVolume) { }

    public static void main(String[] args) throws Exception {
        int lookbackDays = Integer.getInteger("carryLookbackDays", 7);
        int holdDays = Integer.getInteger("carryHoldDays", 7);
        int positions = Integer.getInteger("carryPositions", 10);
        double minimumPerpVolume = Double.parseDouble(System.getProperty("carryMinPerpVolume", "10000000"));
        double minimumSpotVolume = Double.parseDouble(System.getProperty("carryMinSpotVolume", "2000000"));
        int minimumAgeDays = Integer.getInteger("carryMinAgeDays", 30);
        double costBpsPerSidePerLeg = Double.parseDouble(System.getProperty("carryCostBps", "6.5"));
        LocalDate from = LocalDate.parse(System.getProperty("carryFrom", "2021-01-01"));
        LocalDate to = LocalDate.parse(System.getProperty("carryTo", "2026-12-31"));

        DatabaseConfig database = DatabaseConfig.fromEnvironment();
        TreeMap<LocalDate, Map<String, Bar>> perp = loadPanel(database, "futures_kline", from);
        TreeMap<LocalDate, Map<String, Bar>> spot = loadPanel(database, "spot_kline", from);
        Map<String, TreeMap<LocalDate, Double>> funding = loadFunding(database, from);
        List<LocalDate> days = new ArrayList<>(perp.keySet());
        System.out.printf("perp days %d, spot days %d, funding symbols %d%n",
                perp.size(), spot.size(), funding.size());

        Map<String, LocalDate> firstSeen = new HashMap<>();
        for (LocalDate day : days) {
            for (String symbol : perp.get(day).keySet()) firstSeen.putIfAbsent(symbol, day);
        }

        List<Double> returns = new ArrayList<>();
        List<Double> fundingLeg = new ArrayList<>();
        List<Double> basisLeg = new ArrayList<>();
        Set<String> held = new HashSet<>();
        int rebalances = 0;
        int eligibleSum = 0;

        java.util.List<LocalDate> periodEnd = new ArrayList<>();
        for (int i = lookbackDays; i + holdDays < days.size(); i += holdDays) {
            if (days.get(i).isAfter(to)) break;
            LocalDate rankStart = days.get(i - lookbackDays);
            LocalDate entry = days.get(i);
            LocalDate exit = days.get(i + holdDays);
            Map<String, Bar> perpEntry = perp.get(entry);
            Map<String, Bar> perpExit = perp.get(exit);
            Map<String, Bar> spotEntry = spot.getOrDefault(entry, Map.of());
            Map<String, Bar> spotExit = spot.getOrDefault(exit, Map.of());

            record Candidate(String symbol, double trailingFunding) { }
            List<Candidate> eligible = new ArrayList<>();
            for (String symbol : perpEntry.keySet()) {
                if (!spotEntry.containsKey(symbol) || !spotExit.containsKey(symbol)
                        || !perpExit.containsKey(symbol)) {
                    continue; // both legs must be tradeable at entry and exit
                }
                if (java.time.temporal.ChronoUnit.DAYS.between(firstSeen.get(symbol), entry) < minimumAgeDays) {
                    continue;
                }
                if (median(perp, days, i, symbol) < minimumPerpVolume) continue;
                if (median(spot, days, i, symbol) < minimumSpotVolume) continue;
                TreeMap<LocalDate, Double> rates = funding.get(symbol);
                if (rates == null) continue;
                double trailing = rates.subMap(rankStart, true, entry, true).values()
                        .stream().mapToDouble(Double::doubleValue).sum();
                eligible.add(new Candidate(symbol, trailing));
            }
            if (eligible.size() < positions) continue;
            eligible.sort(Comparator.comparingDouble(Candidate::trailingFunding).reversed());
            List<String> chosen = eligible.subList(0, positions).stream().map(Candidate::symbol).toList();

            double basisTotal = 0;
            double fundingTotal = 0;
            for (String symbol : chosen) {
                double spotReturn = spotExit.get(symbol).close() / spotEntry.get(symbol).close() - 1;
                double perpReturn = perpExit.get(symbol).close() / perpEntry.get(symbol).close() - 1;
                double received = funding.get(symbol)
                        .subMap(entry, false, exit, true).values()
                        .stream().mapToDouble(Double::doubleValue).sum();
                basisTotal += spotReturn - perpReturn;   // long spot, short perp
                fundingTotal += received;                 // a short receives positive funding
            }
            double basis = basisTotal / positions;
            double received = fundingTotal / positions;

            Set<String> chosenSet = new HashSet<>(chosen);
            long turned = chosen.stream().filter(s -> !held.contains(s)).count();
            // Two legs, entered and exited, on the fraction of the book that actually changed.
            double cost = (turned / (double) positions) * 2 * 2 * costBpsPerSidePerLeg / 10_000.0;

            // Capital is both legs, so the per-unit-notional result is halved.
            returns.add((basis + received - cost) / 2.0);
            periodEnd.add(exit);
            fundingLeg.add(received / 2.0);
            basisLeg.add(basis / 2.0);
            held.clear();
            held.addAll(chosenSet);
            eligibleSum += eligible.size();
            rebalances++;
        }
        report(returns, fundingLeg, basisLeg, holdDays, rebalances, eligibleSum, positions);
        // Per-year, because a single favourable stretch carrying an entire result is the failure
        // mode that invalidated the Apollo work; it has to be visible rather than inferred.
        System.out.println("  by year (net, annualised):");
        java.util.TreeMap<Integer, List<Double>> byYear = new java.util.TreeMap<>();
        for (int k = 0; k < returns.size(); k++) {
            byYear.computeIfAbsent(periodEnd.get(k).getYear(), y -> new ArrayList<>()).add(returns.get(k));
        }
        for (var e : byYear.entrySet()) {
            List<Double> r = e.getValue();
            double m = r.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double s2 = Math.sqrt(r.stream().mapToDouble(x -> (x - m) * (x - m)).sum() / r.size());
            double pv = 365.0 / holdDays;
            System.out.printf("    %d  %+6.1f%%   Sharpe %5.2f   (%d periods)%n",
                    e.getKey(), m * pv * 100, s2 == 0 ? 0 : (m * pv) / (s2 * Math.sqrt(pv)), r.size());
        }
    }

    private static double median(TreeMap<LocalDate, Map<String, Bar>> panel, List<LocalDate> days,
                                 int index, String symbol) {
        List<Double> volumes = new ArrayList<>();
        for (int j = Math.max(0, index - 30); j < index; j++) {
            Map<String, Bar> day = panel.get(days.get(j));
            if (day == null) continue;
            Bar bar = day.get(symbol);
            if (bar != null) volumes.add(bar.quoteVolume());
        }
        if (volumes.size() < 15) return 0;
        volumes.sort(null);
        return volumes.get(volumes.size() / 2);
    }

    private static TreeMap<LocalDate, Map<String, Bar>> loadPanel(
            DatabaseConfig database, String table, LocalDate from) throws Exception {
        TreeMap<LocalDate, Map<String, Bar>> panel = new TreeMap<>();
        String sql = ("SELECT (open_time AT TIME ZONE 'UTC')::date AS d, symbol, "
                + "(ARRAY_AGG(close_price ORDER BY open_time DESC))[1], SUM(quote_asset_volume) "
                + "FROM " + table + " WHERE interval='1h' AND open_time >= ? GROUP BY 1,2");
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, from);
            statement.setFetchSize(100_000);
            connection.setAutoCommit(false);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    panel.computeIfAbsent(results.getObject(1, LocalDate.class), k -> new HashMap<>())
                            .put(results.getString(2), new Bar(results.getDouble(3), results.getDouble(4)));
                }
            }
        }
        return panel;
    }

    private static Map<String, TreeMap<LocalDate, Double>> loadFunding(
            DatabaseConfig database, LocalDate from) throws Exception {
        Map<String, TreeMap<LocalDate, Double>> funding = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(
                database.url(), database.user(), database.password());
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT symbol, (funding_time AT TIME ZONE 'UTC')::date, SUM(funding_rate) "
                             + "FROM futures_funding_rate WHERE funding_time >= ? GROUP BY 1,2")) {
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

    private static void report(List<Double> returns, List<Double> fundingLeg, List<Double> basisLeg,
                               int holdDays, int rebalances, int eligibleSum, int positions) {
        if (returns.isEmpty()) {
            System.out.println("no rebalances produced");
            return;
        }
        double periodsPerYear = 365.0 / holdDays;
        double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sd = Math.sqrt(returns.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / returns.size());
        double annual = mean * periodsPerYear;
        double annualSd = sd * Math.sqrt(periodsPerYear);
        double t = mean / (sd / Math.sqrt(returns.size()));
        double equity = 1, peak = 1, maxDrawdown = 0;
        for (double r : returns) {
            equity *= (1 + r);
            peak = Math.max(peak, equity);
            maxDrawdown = Math.max(maxDrawdown, 1 - equity / peak);
        }
        double fundingAnnual = fundingLeg.stream().mapToDouble(Double::doubleValue).average().orElse(0) * periodsPerYear;
        double basisAnnual = basisLeg.stream().mapToDouble(Double::doubleValue).average().orElse(0) * periodsPerYear;
        long losers = returns.stream().filter(r -> r < 0).count();
        System.out.printf("%nhold %dd, %d positions, mean eligible %.0f, %d rebalances%n",
                holdDays, positions, eligibleSum / (double) rebalances, rebalances);
        System.out.printf("  decomposition (annualised, on total two-leg capital):%n");
        System.out.printf("    funding received %+.1f%%   basis drift %+.1f%%%n", fundingAnnual * 100, basisAnnual * 100);
        System.out.printf("  NET  annual %+.1f%%   vol %.1f%%   Sharpe %.2f   t %.2f   maxDD %.1f%%   "
                        + "losing periods %.0f%%   cumulative %+.1f%%%n",
                annual * 100, annualSd * 100, annual / annualSd, t, maxDrawdown * 100,
                100.0 * losers / returns.size(), (equity - 1) * 100);
    }
}
