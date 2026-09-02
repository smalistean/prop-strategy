-- Curve stablecoin-pool composition, one row per (observation, pool, coin).
--
-- Written by scripts/curve-composition-monitor.py (read-only eth_call against each pool's own
-- state). Composition is the LEADING indicator of a stablecoin depeg: the StableSwap curve is flat to
-- roughly 80% imbalance and vertical beyond it, so a coin's pool share drifts days before any price
-- chart moves (CURVE_STABLESWAP_DD.md, STABLECOIN_DEPEG_DOSSIER.md). Thresholds and the discovery
-- rule are frozen in CURVE_MONITOR_PREREGISTRATION.md.
--
-- Aggregates are NOT stored - they are a query over these rows (see curve_coin_aggregate below), so
-- the raw observation stays the single source of truth and the universe of pools observed on any
-- date is recoverable as the distinct pool_address values at that observed_at.

CREATE TABLE curve_pool_composition (
    observed_at         timestamptz     NOT NULL,
    pool_address        varchar(42)     NOT NULL,
    pool_name           text            NOT NULL,
    coin_symbol         text            NOT NULL,
    coin_address        varchar(42)     NOT NULL,
    n_coins             smallint        NOT NULL,
    -- coin units; for the nominal-$1 stables this table admits, also ~USD
    balance             numeric(38, 6)  NOT NULL,
    -- balance / pool total
    share               numeric(9, 6)   NOT NULL,
    -- share - 1/n_coins: 0 = balanced, positive = this coin is being sold into the pool
    excess              numeric(9, 6)   NOT NULL,
    -- the pool's own get_dy for a near-marginal probe (0.1% of this coin's balance), in bp;
    -- negative = this coin is the cheap side. NULL when the pool exposes no get_dy we can call.
    marginal_impact_bp  numeric(12, 4),
    pool_tvl_usd        numeric(20, 2)  NOT NULL,
    pool_a              bigint,
    -- Curve API's usdPrice at discovery, context only, never used as the measurement
    api_usd_price       numeric(12, 6),
    PRIMARY KEY (observed_at, pool_address, coin_symbol)
);

CREATE INDEX curve_pool_composition_coin_time_idx
    ON curve_pool_composition (coin_symbol, observed_at);

COMMENT ON TABLE curve_pool_composition IS
    'Curve stable-pool composition per observation/pool/coin; leading depeg indicator. See CURVE_MONITOR_PREREGISTRATION.md.';

-- TVL-weighted excess per coin per observation: the "is this coin being dumped everywhere" number.
-- Same thresholds as a single pool (0.32 / 0.42 / 0.52) per pre-registration amendment A2.
CREATE VIEW curve_coin_aggregate AS
SELECT observed_at,
       coin_symbol,
       count(*)                                            AS pools,
       sum(pool_tvl_usd)                                   AS total_tvl_usd,
       sum(pool_tvl_usd * excess) / sum(pool_tvl_usd)      AS aggregate_excess
FROM curve_pool_composition
GROUP BY observed_at, coin_symbol;

COMMENT ON VIEW curve_coin_aggregate IS
    'TVL-weighted composition excess per coin across all admitted Curve pools at each observation.';
