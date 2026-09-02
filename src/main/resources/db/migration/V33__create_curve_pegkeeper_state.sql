-- crvUSD PegKeeper state per Regulator-registered PegKeeper pool (CURVE_MONITOR_PREREGISTRATION.md A5).
-- A PegKeeper mints/burns crvUSD into its pool and so rebalances the composition we read from two admitted
-- pools; the Regulator's relative-gap test (this pool's crvUSD oracle price vs the highest other PegKeeper
-- pool) is its own "this pool's counter-coin is being sold" detector and is stored here as gap_bp.
CREATE TABLE curve_pegkeeper_state (
    observed_at        timestamptz     NOT NULL,
    peg_keeper         varchar(42)     NOT NULL,
    pool_address       varchar(42)     NOT NULL,
    pool_name          text            NOT NULL,
    counter_symbol     text            NOT NULL,
    counter_share      numeric(8, 6)   NOT NULL,   -- counter-stable balance / pool total
    pool_tvl_usd       numeric(20, 2)  NOT NULL,
    debt               numeric(24, 6)  NOT NULL,   -- crvUSD the PegKeeper has provided and not withdrawn
    debt_ceiling       numeric(24, 6)  NOT NULL,
    idle_crvusd        numeric(24, 6)  NOT NULL,   -- pre-minted crvUSD still held by the PegKeeper
    lp_share           numeric(8, 6),              -- PegKeeper LP tokens / pool LP supply
    oracle_price       numeric(20, 12),            -- pool crvUSD oracle price in the counter-stable
    gap_bp             numeric(12, 4),             -- oracle_price - max(other PegKeeper pools), bp
    provide_allowed    numeric(24, 6),             -- crvUSD; NULL = unlimited
    withdraw_allowed   numeric(24, 6),
    aggregate_price    numeric(20, 12),
    alert_level        smallint        NOT NULL,
    PRIMARY KEY (observed_at, peg_keeper)
);
CREATE INDEX curve_pegkeeper_state_pool_time_idx ON curve_pegkeeper_state (pool_address, observed_at);
COMMENT ON TABLE curve_pegkeeper_state IS 'crvUSD PegKeeper debt, capacity and the Regulator relative-gap test per pool; see CURVE_MONITOR_PREREGISTRATION.md A5.';
