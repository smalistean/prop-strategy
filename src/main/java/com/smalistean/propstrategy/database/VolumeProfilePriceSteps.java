package com.smalistean.propstrategy.database;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Declared, per-symbol default price-bin width for volume-profile bins. A single global step
 * (previously a hardcoded $10 everywhere) works for BTCUSDT (avg ~$66,575, ~11,000 distinct $10
 * levels) and is passable for ETHUSDT (avg ~$2,555, ~375 levels), but silently degenerates for any
 * symbol whose entire price history fits inside one $10 bin: XRPUSDT, ADAUSDT, DOGEUSDT, and
 * TRXUSDT all trade under $10 for the whole training window, so every bucket's volume collapsed
 * into a single bin at price_from=0, making the resulting zone boundary [0, 10) - beyond which a
 * long/short reclaim can never mathematically occur (2026-08-10 finding, see
 * APOLLO_V5_DESIGN.md "Family B" section). LINKUSDT (avg ~$12.9) was not fully degenerate but only
 * spanned 4 possible bins across its whole range, far coarser than its real volatility.
 * <p>
 * Each step below was chosen from real training-window price data (min/max/avg close, 2023-01-01
 * through 2026-08-10) targeting roughly the same relative resolution BTC/ETH already demonstrated
 * (hundreds to low thousands of distinct levels across the full range), rounded to a plain
 * 1-2-5-style number rather than computed algorithmically at runtime - this project's convention is
 * to declare thresholds explicitly, not derive "magic" values silently.
 * <p>
 * BNBUSDT and SOLUSDT were revisited on 2026-08-10: both were still using the original hardcoded
 * $10 default, giving only 118 and 30 distinct levels respectively (1.8% and 8.8% of average price
 * per bin) - the same under-resolution failure mode as the symbols above, just short of fully
 * degenerate. BTCUSDT and ETHUSDT were left at $10 - both already matched real course-labelled
 * examples to within a few dollars (APOLLO_LABELLED_EXAMPLES.md), and re-testing them means
 * reprocessing 22-25 GB of already-imported local archives each with no concrete evidence either
 * is wrong, unlike BNB/SOL where the coarseness was directly measurable.
 * <p>
 * Consistency pass (2026-08-11). Between 2026-08-10 and 2026-08-11 these values were set by three
 * different methodologies: (1) "a few hundred distinct levels across the full price range" for most
 * symbols, (2) the untouched original $10 for BTCUSDT/ETHUSDT, and (3) roughly ten times finer than
 * an ATR-scaled target for BNBUSDT/SOLUSDT, to give
 * {@code VolumeProfileFeatureAssemblerV5.aggregationStep} headroom to operate. Methodology (3) was
 * abandoned: it never outperformed, and the evidence ran the other way - ETHUSDT re-imported at 1
 * instead of 10 went from 92 trades / +$4,408 / completed to 24 trades / -$8,882 / MAX_DRAWDOWN, and
 * BNBUSDT was negative in every configuration tried while XRPUSDT, on methodology (1), is the
 * strongest symbol measured.
 * <p>
 * BNBUSDT and SOLUSDT are therefore back on methodology (1) at 1 and 0.5, giving 1,174 and 573
 * distinct levels - in line with XRPUSDT (674), ADAUSDT (596) and LINKUSDT (526). Those bins already
 * existed in the database from the earlier revisit, so no re-import of the main range was needed;
 * only the 2022-10-01..2023-01-01 lookback runway had to be backfilled at the restored steps. The
 * finer step=0.02 / step=0.01 rows are retained for reference but are no longer selected.
 * <p>
 * The single property that actually matters, and the reason the original flat $10 was a genuine bug,
 * is that no symbol's price range may collapse into one bin. Every value below yields hundreds of
 * distinct levels across its own range, so that failure mode is closed.
 */
public final class VolumeProfilePriceSteps {
    private static final Map<String, BigDecimal> DEFAULTS = Map.ofEntries(
            Map.entry("BTCUSDT", new BigDecimal("10")),
            // Re-imported at 1 on 2026-08-10 to test finer granularity (1,564,627 rows vs 260,050),
            // then reverted after an isolated same-code comparison: step=10 gives 92 trades /
            // +$4,408 / PF 1.13 / completed, step=1 gives 24 trades / -$8,882 / PF 0.21 /
            // MAX_DRAWDOWN termination. The finer representation is account-destroying here, not
            // merely less profitable. The step=1 rows are retained in the database for reference.
            Map.entry("ETHUSDT", new BigDecimal("10")),
            Map.entry("BNBUSDT", new BigDecimal("1")),
            Map.entry("BCHUSDT", new BigDecimal("5")),
            Map.entry("AAVEUSDT", new BigDecimal("1")),
            Map.entry("SOLUSDT", new BigDecimal("0.5")),
            Map.entry("LTCUSDT", new BigDecimal("0.5")),
            Map.entry("AVAXUSDT", new BigDecimal("0.1")),
            Map.entry("ETCUSDT", new BigDecimal("0.1")),
            Map.entry("LINKUSDT", new BigDecimal("0.05")),
            Map.entry("DOTUSDT", new BigDecimal("0.02")),
            Map.entry("XRPUSDT", new BigDecimal("0.005")),
            Map.entry("ADAUSDT", new BigDecimal("0.002")),
            Map.entry("TRXUSDT", new BigDecimal("0.001")),
            Map.entry("DOGEUSDT", new BigDecimal("0.001")));

    /**
     * Per-symbol {@code pocBinAtrFraction} overrides. Now empty: the BNBUSDT/SOLUSDT entries existed
     * only to tame the ~9x aggregation multiple created by their ultra-fine methodology-(3) steps,
     * and became redundant when those steps were restored to 1 and 0.5 on 2026-08-11. At those steps
     * the ATR-scaled target is finer than one raw bin, so aggregation is a no-op and the
     * concentration normalization divides by 1 - the same behaviour as the other thirteen symbols.
     * Kept as an extension point; symbols not listed fall back to the strategy config's value.
     */
    private static final Map<String, BigDecimal> ATR_FRACTION_OVERRIDES = Map.of();

    private VolumeProfilePriceSteps() {}

    /** Throws for an undeclared symbol rather than silently falling back to a default that may degenerate. */
    public static BigDecimal defaultFor(String symbol) {
        BigDecimal step = DEFAULTS.get(symbol.trim().toUpperCase());
        if (step == null) {
            throw new IllegalArgumentException("No declared volume-profile price step for '" + symbol
                    + "'. Add one to VolumeProfilePriceSteps based on its real training-window price "
                    + "range rather than assuming a shared default.");
        }
        return step;
    }

    /** Falls back to {@code configured} (the strategy's declared default) for any symbol not listed above. */
    public static BigDecimal pocBinAtrFractionFor(String symbol, BigDecimal configured) {
        return ATR_FRACTION_OVERRIDES.getOrDefault(symbol.trim().toUpperCase(), configured);
    }
}
