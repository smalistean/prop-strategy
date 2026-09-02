-- Pool-implied price of a yield-bearing wrapper (initially sUSDe) versus what it redeems for.
--
-- Kept SEPARATE from curve_pool_composition on purpose. A wrapper's pool share is unreadable as a peg
-- signal: 1 sUSDe = ~1.25 USDe and rising, the pool's invariant is already NAV-scaled via its rate
-- oracle, and a skew conflates peg doubt with people paying to skip the up-to-90-day cooldown.
-- What the same pool DOES tell us cleanly is whether the wrapper trades below its redemption value -
-- Ethena redemption/cooldown stress. Thresholds and admission rule: CURVE_MONITOR_PREREGISTRATION.md A3.

CREATE TABLE curve_wrapper_nav_discount (
    observed_at          timestamptz     NOT NULL,
    pool_address         varchar(42)     NOT NULL,
    pool_name            text            NOT NULL,
    wrapper_symbol       text            NOT NULL,
    wrapper_address      varchar(42)     NOT NULL,
    counter_symbol       text            NOT NULL,
    -- wrapper.convertToAssets(1e18): underlying per 1 wrapper (USDe per sUSDe)
    nav                  numeric(20, 10) NOT NULL,
    -- pool's own get_dy for a near-marginal probe, counter-asset per 1 wrapper
    pool_implied_price   numeric(20, 10) NOT NULL,
    -- (pool_implied_price / nav - 1) * 1e4; negative = wrapper trades below redemption value
    nav_discount_bp      numeric(12, 4)  NOT NULL,
    -- pool's rate oracle for the wrapper leg, for audit (should track nav)
    pool_stored_rate     numeric(20, 10),
    -- NAV-scaled pool value in USD
    pool_tvl_usd         numeric(20, 2)  NOT NULL,
    PRIMARY KEY (observed_at, pool_address)
);

CREATE INDEX curve_wrapper_nav_discount_wrapper_time_idx
    ON curve_wrapper_nav_discount (wrapper_symbol, observed_at);

COMMENT ON TABLE curve_wrapper_nav_discount IS
    'Wrapper (sUSDe) pool-implied price vs redemption NAV on Curve; Ethena redemption/cooldown stress signal. See CURVE_MONITOR_PREREGISTRATION.md A3.';
