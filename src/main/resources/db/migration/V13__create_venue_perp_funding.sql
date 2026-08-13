-- Funding-rate tables for the five additional perpetual venues.
--
-- One table per venue, matching the naming settled in V12, rather than a single table with a venue
-- column: the venue is the distinction the whole research direction turns on, and a shared table
-- would make every existing query silently wrong unless it remembered to filter.
--
-- <venue>_perp_kline tables are deliberately NOT created yet. Basis drift needs prices, but only
-- venues that clear the funding test need them, and creating seven price tables before knowing which
-- venues matter would be 100+ GB of data collected on speculation.
--
-- ## History depth, probed before writing this
--
--   Bybit    pages cleanly via endTime, reaches at least 2023-10   -> backtestable
--   dYdX     pages cleanly via effectiveBeforeOrAt, at least 2023-12 -> backtestable
--   OKX      stops at roughly 3 months (oldest reached 2026-05-11) -> forward only
--   Bitget   two pages of 100 then empty (oldest 2026-06-07)       -> forward only
--   Gate     90 records, about 30 days                             -> forward only
--
-- The shallow three still earn their tables: they contribute nothing to a backtest but everything to
-- forward collection, and forward evidence is what every strategy here is now waiting on.
--
-- ## base, and why it is stored rather than derived
--
-- Every venue names the same instrument differently - BTCUSDT, BTC-USDT-SWAP, BTC_USDT, BTC-USD -
-- so a cross-venue join needs one normalised key. Deriving it at query time would mean repeating the
-- parsing rules in every analysis, and a rule that is wrong for one venue would quietly drop that
-- venue's rows from the join rather than fail. It is computed once, at import, and stored.
--
-- Note dYdX quotes against USD rather than USDT. Treating them as the same base is deliberate: the
-- funding RATE is what is being compared, and it is quoted on the same underlying exposure.

CREATE TABLE bybit_perp_funding_rate (
    venue_symbol  VARCHAR(40)     NOT NULL,
    base          VARCHAR(30)     NOT NULL,
    funding_time  TIMESTAMPTZ     NOT NULL,
    funding_rate  NUMERIC(20,12)  NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT bybit_perp_funding_rate_pk PRIMARY KEY (venue_symbol, funding_time)
);
CREATE INDEX bybit_perp_funding_rate_base_idx ON bybit_perp_funding_rate (base, funding_time);

CREATE TABLE okx_perp_funding_rate (
    venue_symbol  VARCHAR(40)     NOT NULL,
    base          VARCHAR(30)     NOT NULL,
    funding_time  TIMESTAMPTZ     NOT NULL,
    funding_rate  NUMERIC(20,12)  NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT okx_perp_funding_rate_pk PRIMARY KEY (venue_symbol, funding_time)
);
CREATE INDEX okx_perp_funding_rate_base_idx ON okx_perp_funding_rate (base, funding_time);

CREATE TABLE gate_perp_funding_rate (
    venue_symbol  VARCHAR(40)     NOT NULL,
    base          VARCHAR(30)     NOT NULL,
    funding_time  TIMESTAMPTZ     NOT NULL,
    funding_rate  NUMERIC(20,12)  NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT gate_perp_funding_rate_pk PRIMARY KEY (venue_symbol, funding_time)
);
CREATE INDEX gate_perp_funding_rate_base_idx ON gate_perp_funding_rate (base, funding_time);

CREATE TABLE bitget_perp_funding_rate (
    venue_symbol  VARCHAR(40)     NOT NULL,
    base          VARCHAR(30)     NOT NULL,
    funding_time  TIMESTAMPTZ     NOT NULL,
    funding_rate  NUMERIC(20,12)  NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT bitget_perp_funding_rate_pk PRIMARY KEY (venue_symbol, funding_time)
);
CREATE INDEX bitget_perp_funding_rate_base_idx ON bitget_perp_funding_rate (base, funding_time);

CREATE TABLE dydx_perp_funding_rate (
    venue_symbol  VARCHAR(40)     NOT NULL,
    base          VARCHAR(30)     NOT NULL,
    funding_time  TIMESTAMPTZ     NOT NULL,
    funding_rate  NUMERIC(20,12)  NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT dydx_perp_funding_rate_pk PRIMARY KEY (venue_symbol, funding_time)
);
CREATE INDEX dydx_perp_funding_rate_base_idx ON dydx_perp_funding_rate (base, funding_time);

-- Single read surface for cross-venue analysis. Binance and Hyperliquid are folded in here so an
-- analysis never has to know that two of the seven predate this schema and use different column
-- names.
--
-- Binance is DEDUPLICATED per (symbol, funding_time): its table holds two overlapping rate_types and
-- a direct read double-counts 63,075 payments. That defect made cash-and-carry appear to pass at
-- Sharpe 2.16 when the true figure is 1.29, and it is fixed here so no future query can inherit it.
CREATE VIEW perp_funding_all AS
    SELECT 'binance'::text AS venue, symbol AS venue_symbol,
           CASE WHEN symbol LIKE '%USDT' THEN left(symbol, length(symbol)-4)
                WHEN symbol LIKE '%USDC' THEN left(symbol, length(symbol)-4)
                ELSE symbol END AS base,
           funding_time, rate AS funding_rate
    FROM (SELECT symbol, funding_time, max(funding_rate) AS rate
          FROM binance_perp_funding_rate GROUP BY symbol, funding_time) binance_deduplicated
UNION ALL
    SELECT 'hyperliquid', coin, coin, funding_time, funding_rate FROM hyperliquid_perp_funding_rate
UNION ALL SELECT 'bybit',  venue_symbol, base, funding_time, funding_rate FROM bybit_perp_funding_rate
UNION ALL SELECT 'okx',    venue_symbol, base, funding_time, funding_rate FROM okx_perp_funding_rate
UNION ALL SELECT 'gate',   venue_symbol, base, funding_time, funding_rate FROM gate_perp_funding_rate
UNION ALL SELECT 'bitget', venue_symbol, base, funding_time, funding_rate FROM bitget_perp_funding_rate
UNION ALL SELECT 'dydx',   venue_symbol, base, funding_time, funding_rate FROM dydx_perp_funding_rate;
