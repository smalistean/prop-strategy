-- A6 (CURVE_MONITOR_PREREGISTRATION.md): the wrapper NAV reading restated in dollars through the counter-asset's
-- own dollar price, because the counter (DOLA) has no working par path and enters the sUSDe reading one for one.
ALTER TABLE curve_wrapper_nav_discount
    ADD COLUMN counter_usd_price         numeric(20, 10),          -- counter-asset in USD via its route (DOLA -> sUSDS x NAV)
    ADD COLUMN nav_discount_usd_bp       numeric(12, 4),           -- (implied x counter_usd / nav - 1) x 1e4; drives the level
    ADD COLUMN counter_par_capacity_usd  numeric(20, 2),           -- USD actually redeemable from the counter's PSM
    ADD COLUMN counter_unreliable        boolean NOT NULL DEFAULT false;  -- |counter - $1| > 100 bp: reading cannot raise the level
COMMENT ON COLUMN curve_wrapper_nav_discount.nav_discount_usd_bp IS 'A6 dollar-restated discount; the level uses this when present.';
