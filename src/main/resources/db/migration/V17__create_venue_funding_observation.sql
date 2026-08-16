-- Hourly OBSERVATIONS of pending funding rates, from the AWS recorder.
--
-- Deliberately a separate table from the <venue>_perp_funding_rate history, and deliberately NOT in
-- the perp_funding_all view. These rows are not the same kind of fact.
--
-- The settled tables record what was actually paid at a stamp. This table records what each venue
-- SAID the next stamp would pay, sampled once an hour. The two disagree: a pending rate moves until
-- its stamp lands, so an observation taken at 03:50 for an 08:00 stamp is an estimate, not a payment.
--
-- Mixing them is the exact shape of the bug that once made cash-and-carry read Sharpe 2.16 when the
-- truth was 1.29 - two overlapping sources for one payment, summed, with no error anywhere. That is
-- why observed_hour is part of the key: several rows legitimately describe the same target stamp, and
-- collapsing them into one payment is a decision the reader has to make explicitly.
--
-- Intended use is freshness, not backtesting. XVF's guard needs to know each venue is currently
-- reporting a full universe; the settled history remains the authority for what anything earned.
CREATE TABLE venue_funding_observation (
    venue           VARCHAR(20)  NOT NULL,
    venue_symbol    VARCHAR(120) NOT NULL,
    observed_hour   TIMESTAMPTZ  NOT NULL,
    observed_at     TIMESTAMPTZ  NOT NULL,
    -- The stamp this rate was pending for. NULL where a venue publishes no stamp field.
    target_stamp    TIMESTAMPTZ,
    funding_rate    NUMERIC(30,12) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT venue_funding_observation_pk
        PRIMARY KEY (venue, venue_symbol, observed_hour)
);

-- Serves the freshness guard: "how many symbols did this venue report in the last N hours".
CREATE INDEX venue_funding_observation_venue_hour_idx
    ON venue_funding_observation (venue, observed_hour DESC);

-- Serves collapsing observations to one row per target stamp, when that is wanted.
CREATE INDEX venue_funding_observation_target_idx
    ON venue_funding_observation (venue, venue_symbol, target_stamp);

COMMENT ON TABLE venue_funding_observation IS
    'Hourly samples of PENDING funding rates. Not settled payments; not in perp_funding_all. '
    'See V17 migration header before joining against <venue>_perp_funding_rate.';
