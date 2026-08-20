package com.smalistean.propstrategy.xvf;

/**
 * Every tunable for XVF in one place, with the measurement that settled each one.
 *
 * <p>Nothing here is a guess. Where a value sits on a swept optimum that is said explicitly, because
 * a parameter resting on a peak is what an overfit looks like even when it was not fitted.
 *
 * <p>See {@code XVF_STRATEGY.md} for the full record and
 * {@code CROSS_VENUE_FUNDING_PREREGISTRATION.md} for the forward test these values are frozen for.
 */
public final class XvfConfig {

    private XvfConfig() {
    }

    /**
     * Venues XVF actually trades. OKX, Gate and Bitget serve only 1-3 months of funding history and
     * are excluded on that alone. dYdX cleared the history bar but not the venue-combination one -
     * XVF_V1_SCOPE.md measured all ten combinations of the four candidate venues with the liquidity
     * floor applied, and adding dYdX to binance+bybit+hyperliquid made the book worse (Score 16.7 to
     * 16.0): its tradeable universe under the $500k floor is too thin to be worth a fourth gateway
     * and a second on-chain auth model. It earns its place only in a book that is candidate-starved,
     * which this one is not.
     */
    public static final String[] VENUES = {"binance", "bybit", "hyperliquid"};

    /**
     * Collateral asset per venue: USDT on the CEXs, USDC on Hyperliquid.
     *
     * <p>Hyperliquid has no choice - its perps are bare coin names with one account-wide collateral
     * asset, and USDT exists there only as the USDT0 spot token. Binance and Bybit are pinned to USDT
     * deliberately so each venue has exactly one collateral asset and a leg's currency is never a
     * variable at execution time.
     *
     * <p>Binance also lists USDC contracts on 39 bases, and choosing the quote that suits the leg
     * direction is worth +3.32% annualised per pair. It is not taken: only 9.9% of selections have
     * both contracts, so the book-level gain is +0.33% of capital a year, which does not justify a
     * second collateral asset on a venue before the strategy has traded. See XVF_IMPLEMENTATION.md §7.
     */
    public static String collateral(String venue) {
        return switch (venue) {
            case "hyperliquid" -> "USDC";
            default -> "USDT";
        };
    }

    /**
     * Trailing window for realised funding on CEX-DEX pairs, in days, matching their 7-day hold.
     * Also what {@code requireFreshFunding} checks data freshness against: it is the wider of the
     * two lookback windows, so a leg clearing it clears {@link #LOOKBACK_DAYS_CEX_CEX} too.
     */
    public static final int LOOKBACK_DAYS = 7;

    /**
     * Trailing window for realised funding on CEX-CEX pairs, in days, matching their 3-day hold.
     * A shared 7-day window used to be applied here too; candidates it selected went on to realise
     * 50.4% annualised over the following 3 days against 61.3% for a 3-day-lookback selection
     * (2024-01 to 2026-08, n=2546 vs 2573 candidate-days). CEX-CEX gaps get arbed shut within days,
     * so the extra 4 days of history in the 7-day window were diluting the signal with a gap that
     * had already started closing.
     */
    public static final int LOOKBACK_DAYS_CEX_CEX = 3;

    /**
     * Minimum annualised spread to open. Swept: 12.0% net at a 0% threshold, 19.0% at 20%, 25.4% at
     * 40% but with only 6 positions. Sharpe peaks here at 5.12.
     */
    public static final double MIN_SPREAD_ANNUAL_PCT = 20.0;

    /**
     * Multiplier applied to a candidate's signal when it was already eligible the day before, in
     * {@code XvfSignalEngine.rankedCandidates}.
     *
     * <p>Measured 2024-01 to 2026-08: a candidate on its first eligible day realises close to what it
     * reads (99% for CEX-CEX, 90% for CEX-DEX), but a candidate that was also eligible the day before
     * realises roughly half (46% for CEX-CEX, 51% for CEX-DEX) - and that ratio does not decay further
     * with more days eligible, it is flat from the second day on. The trailing window is a sum, so a
     * gap that has been open for days keeps accumulating in it after the forward-looking opportunity
     * has already started closing. Confirmed live 2026-08-20: a book that read a 20-30% blended signal
     * at entry realised 9.6% annualised.
     */
    public static final double STALE_SIGNAL_DISCOUNT = 0.5;

    /**
     * Book size. Top 20 beat top 10 on Sharpe (5.12 vs 4.83) because ranks 11-20 realise as much
     * forward funding as 6-10 - 24.3% and 23.0% against 22.3%. Past rank 20 it halves to 13%.
     */
    public static final int POSITIONS = 20;

    /**
     * Rebalance interval. Swept 1/2/3/5/7/10/14 days: net 14.7 / 17.8 / 22.0 / 19.7 / 19.5 / 15.9 /
     * 13.4. Daily is the worst above weekly because basis is a per-round-trip cost, and churn
     * realises it (-22.6% daily against -10.4% here).
     *
     * <p>Read this as "2 to 5 days"; 3 is the sampled peak, not a known truth.
     */
    public static final int REBALANCE_DAYS = 3;

    /**
     * Equal weight. Spread-weighting was tested and rejected: it raised return one point and cost a
     * third of the Sharpe (4.83 -> 3.47), because the signal ranks candidates well enough to select
     * them and not well enough to size them - rank 1 realises 30% forward funding against rank 10's
     * 21%, while the signal reads 138% against 41%.
     */
    public static final boolean EQUAL_WEIGHT = true;

    /**
     * Leverage per leg. 1x means capital equals total notional. 2x/3x/5x were all worse once
     * liquidation friction was charged, and at 3x the result swings from +25% to -6% depending on
     * which measured slippage figure is used - a configuration whose sign depends on an assumption
     * that uncertain is a bet on the assumption.
     */
    public static final double LEG_LEVERAGE = 1.0;

    /**
     * Fraction of a series' own typical weekly payment count that must be present to use it.
     *
     * <p>A symbol whose trailing window is partial reads as a LOW rate rather than as missing data,
     * because the trailing figure is a sum. Filtering on completeness is what stops a half-collected
     * week from being ranked as a cheap leg.
     */
    public static final double COMPLETENESS_RATIO = 0.9;

    /** History used to establish what a "typical" week looks like for each series, in days. */
    public static final int TYPICAL_WINDOW_DAYS = 180;

    /**
     * Minimum symbols per venue that must survive {@link #COMPLETENESS_RATIO} before a book is
     * produced.
     *
     * <p>100 sits well under every venue's real universe (232 to 775) and well over the 16 that leak
     * through when an archive is stale.
     */
    public static final int MIN_USABLE_SYMBOLS = 100;

    /** Minimum weekly quote volume on the THINNER leg. Below this, prints are untradeable. */
    public static final double MIN_WEEKLY_QUOTE_VOLUME = 500_000;

    /** Participation cap per leg, as a fraction of the thin leg's weekly volume. */
    public static final double MAX_PARTICIPATION = 0.01;

    /**
     * Step-size guard. A leg needs roughly this many steps of notional for rounding error under 1%.
     * Excludes the tokenised-equity perps (LLY, META, TSM, IWM, ARM) whose steps are worth $3-12.
     */
    public static final int MIN_STEPS_PER_LEG = 100;

    /** Fees in basis points. Blended 3.3bp reflects 54% post-only fills and 46% crossing. */
    public static final double MAKER_BPS = 1.8;
    public static final double TAKER_BPS = 5.0;
    public static final double BLENDED_BPS = 3.3;

    /**
     * How long to leave a post-only order resting before crossing the spread on it.
     *
     * <p>Measured: crossing costs 3.2bp; fifteen minutes unhedged costs 92bp at the median and 273bp
     * at p90. There is no horizon at which waiting wins, so this is deliberately short.
     */
    public static final int SECOND_LEG_CROSS_AFTER_SECONDS = 60;

    /**
     * Worst price a crossing leg may print, in basis points past the touch.
     *
     * <p>Crossing is intended; crossing at any price is not. Measured cost to cross is 3.2bp, so 25bp
     * allows for a thin book and a moving market while still refusing the prints that make an
     * unbounded market order dangerous in exactly the dislocated coins this strategy selects. An IOC
     * that caps out leaves the remainder unfilled and visible rather than executed at any price.
     */
    public static final double MAX_TAKER_SLIPPAGE_BPS = 25.0;

    /**
     * Largest USD notional difference between the two legs of a pair, as a fraction.
     *
     * <p>Each leg rounds to its own venue's step size, so the two notionals never match exactly. The
     * residual is unhedged directional exposure in a coin selected for being dislocated, and the
     * measured tail of basis moves is severe - 1 hold in 100 moves 19% of notional, worst observed
     * 37.9%. 1% of a leg is small against that; anything larger means the steps are too coarse for
     * this capital and the pair should be skipped rather than opened crooked.
     */
    public static final double MAX_NOTIONAL_IMBALANCE = 0.01;

    /** Minimum capital for the book to size cleanly. p90 symbol needs $77/leg x 40 legs. */
    public static final double MIN_CAPITAL_USD = 3_089;

    /**
     * Multiplier-prefix normalisation, mirroring {@code normalise_perp_base} in migration V14.
     *
     * <p>The same asset trades as 1000PEPE, PEPE and kPEPE. Joining on the raw symbol split one asset
     * into three and matched none of them, silently dropping every meme coin - which is where funding
     * is most extreme. Prefixes are contract-size multipliers and funding is a rate, so normalising
     * the name is sufficient; a PRICE or QUANTITY would need the multiplier applied.
     */
    public static String normaliseBase(String venue, String venueSymbol) {
        String raw = switch (venue) {
            case "hyperliquid" -> venueSymbol;
            default -> venueSymbol.endsWith("USDT") || venueSymbol.endsWith("USDC")
                    ? venueSymbol.substring(0, venueSymbol.length() - 4) : venueSymbol;
        };
        // 1000000 before 1000, or "1000000MOG" becomes "000MOG".
        for (String prefix : new String[] {"1000000", "100000", "10000", "1000"}) {
            if (raw.startsWith(prefix) && raw.length() > prefix.length()
                    && Character.isUpperCase(raw.charAt(prefix.length()))) {
                return raw.substring(prefix.length());
            }
        }
        if (raw.startsWith("1M") && raw.length() > 2 && Character.isUpperCase(raw.charAt(2))) {
            return raw.substring(2);
        }
        // Lowercase k followed by uppercase is Hyperliquid's convention; a bare leading k would
        // corrupt any asset legitimately starting with one.
        if (raw.startsWith("k") && raw.length() > 1 && Character.isUpperCase(raw.charAt(1))) {
            return raw.substring(1);
        }
        return raw;
    }
}
