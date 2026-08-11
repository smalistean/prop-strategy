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
 * These values are the RAW stored bin width, deliberately finer than any single POC/zone
 * computation needs - {@code VolumeProfileFeatureAssemblerV5.aggregationStep} rounds this up to a
 * whole multiple sized off ATR at compute time (2026-08-10), so the effective analysis resolution
 * tracks current volatility instead of being fixed at import. BNBUSDT and SOLUSDT were re-imported
 * a second time at $0.02 and $0.01 specifically because their first revisit ($1 and $0.5) turned
 * out to already be at or coarser than the ATR-scaled target (avg 15m bar range ~$1.86 and ~$0.85,
 * so a 0.1x-ATR analysis bin wants ~$0.19 and ~$0.09) - with raw that coarse the aggregation logic
 * has no room to operate and silently becomes a no-op, reproducing the same regression a flat finer
 * step already showed. BTCUSDT's existing $10 raw already leaves the aggregation genuine room (avg
 * 15m range ~$212 needs an analysis step of ~$21, i.e. ~2x raw) so it was left as-is; ETHUSDT's $10
 * raw is coarser than its own ATR-scaled target (~$1.12), so aggregation is currently a no-op for
 * ETH too, same open question as BNB/SOL but not yet acted on given the 25 GB reprocessing cost and
 * no evidence of a problem there.
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
            Map.entry("BNBUSDT", new BigDecimal("0.02")),
            Map.entry("BCHUSDT", new BigDecimal("5")),
            Map.entry("AAVEUSDT", new BigDecimal("1")),
            Map.entry("SOLUSDT", new BigDecimal("0.01")),
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
     * Declared, per-symbol {@code pocBinAtrFraction} override (2026-08-10). The strategy config
     * default (0.1) sets BTCUSDT's typical aggregation multiple - how many raw bins get merged into
     * one analysis bin at average ATR - to ~2x. Applying that same flat fraction to BNBUSDT/SOLUSDT
     * produced a ~9x typical multiple instead, because their raw steps (above) were deliberately
     * picked far finer than their ATR-scaled target when the aggregation-only design was still being
     * tested; once minimumPocShare/minimumZoneShare were normalized by dividing out the aggregation
     * multiple (correcting for coarser bins mechanically inflating concentration), that much larger
     * multiple made the threshold far harder to clear for BNB/SOL than for BTC, collapsing their
     * trade counts (79->3 and 85->13 respectively). Rather than re-importing raw data again, these
     * two fractions were solved to reproduce BTC's own ~2x typical multiple against BNB/SOL's
     * existing raw steps and real average ATR (avg 15m bar range ~$1.86 and ~$0.85): fraction =
     * targetMultiple * rawStep / avgAtr. Symbols not listed here fall back to the strategy config's
     * declared value rather than throwing, since an un-normalized default is a reasonable starting
     * point, not a silently-degenerate one the way an unlisted price step would be.
     */
    private static final Map<String, BigDecimal> ATR_FRACTION_OVERRIDES = Map.of(
            "BNBUSDT", new BigDecimal("0.02"),
            "SOLUSDT", new BigDecimal("0.025"));

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
