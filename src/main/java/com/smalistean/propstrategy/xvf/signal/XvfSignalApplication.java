package com.smalistean.propstrategy.xvf.signal;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Candidate;

import java.time.LocalDate;
import java.util.List;

/**
 * Prints the XVF target book. Reporting only — it never sends an order.
 *
 * <p>Ranking lives in {@link XvfSignalEngine} so this and the execution application cannot disagree
 * about what the book is.
 *
 * <pre>
 *   -DxvfCapital=10000        capital in USDT (minimum ~3089, see XvfConfig)
 *   -DxvfAsOf=2026-08-14      signal date, defaults to today
 * </pre>
 */
public final class XvfSignalApplication {

    private XvfSignalApplication() {
    }

    public static void main(String[] args) throws Exception {
        double capital = Double.parseDouble(System.getProperty("xvfCapital", "10000"));
        LocalDate asOf = LocalDate.parse(System.getProperty("xvfAsOf", LocalDate.now().toString()));
        DatabaseConfig database = DatabaseConfig.fromEnvironment();

        if (capital < XvfConfig.MIN_CAPITAL_USD) {
            System.out.printf("WARNING: capital %.0f is below the %.0f minimum; step-size rounding "
                    + "will exceed 1%% on more than a tenth of candidates.%n",
                    capital, XvfConfig.MIN_CAPITAL_USD);
        }

        XvfSignalEngine.requireFreshFunding(database, asOf);
        List<Candidate> book = XvfSignalEngine.topBook(database, asOf);

        double legNotional = capital * XvfConfig.LEG_LEVERAGE / (XvfConfig.POSITIONS * 2.0);
        System.out.printf("%nXVF %s  capital %,.0f  lookback %dd cex-cex / %dd cex-dex  entry >%.0f%%  top %d%n%n",
                asOf, capital, XvfConfig.LOOKBACK_DAYS_CEX_CEX, XvfConfig.LOOKBACK_DAYS,
                XvfConfig.MIN_SPREAD_ANNUAL_PCT, XvfConfig.POSITIONS);
        System.out.printf("%-10s %-24s %-24s %9s %10s %14s%n",
                "base", "SHORT (pays more)", "LONG (pays less)", "spread%", "leg USD", "thin leg vol");
        System.out.println("-".repeat(96));

        double deployed = 0;
        for (Candidate c : book) {
            double capped = Math.min(legNotional, c.thinLegWeeklyVolume() * XvfConfig.MAX_PARTICIPATION);
            if (capped < legNotional * 0.5) {
                System.out.printf("%-10s SKIP: thin leg supports only %.0f of %.0f USD%n",
                        c.base(), capped, legNotional);
                continue;
            }
            deployed += capped * 2;
            System.out.printf("%-10s %-24s %-24s %8.1f%% %10.2f %14s%n",
                    c.base(),
                    c.shortLeg().venue() + " " + c.shortLeg().venueSymbol(),
                    c.longLeg().venue() + " " + c.longLeg().venueSymbol(),
                    c.spreadAnnualPct(), capped,
                    String.format("%,.0f", c.thinLegWeeklyVolume()));
        }
        System.out.printf("%n%,.0f USD notional deployed of %,.0f capital%n", deployed, capital);
        System.out.printf("entry cost ~%.2f USD (4 fills per pair at %.1fbp blended)%n",
                deployed * 2 * XvfConfig.BLENDED_BPS / 10_000.0, XvfConfig.BLENDED_BPS);
        System.out.println("\nNo orders placed. Use XvfExecutionApplication with -DxvfDryRun=false.");
    }
}
