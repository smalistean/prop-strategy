-- Binance futures "metrics" daily archives: open interest and positioning, 5-minute resolution.
--
-- These columns already exist in futures_open_interest_statistic and futures_trader_ratio, but those
-- are fed from the REST API, which only serves roughly the last 30 days - the database holds exactly
-- one month of them. The daily archives at data.binance.vision reach back to 2021-10 for BTCUSDT and
-- 2021-12 for the rest, so this is a separate table rather than a backfill: different source,
-- different guarantees, and mixing a 5-year archive series with a rolling 30-day API series in one
-- table would make provenance impossible to reason about later.
--
-- sum_taker_long_short_vol_ratio is the reason this import exists. It is a market-wide aggressor
-- imbalance, which makes it the market-level counterpart to the per-zone aggressor delta in
-- futures_volume_profile_bin. Neither the interaction between them nor open interest has ever been
-- available to any Apollo version.
CREATE TABLE futures_metric_snapshot (
    symbol                            VARCHAR(30)    NOT NULL,
    snapshot_time                     TIMESTAMPTZ    NOT NULL,
    sum_open_interest                 NUMERIC(38,12),
    sum_open_interest_value           NUMERIC(38,8),
    count_toptrader_long_short_ratio  NUMERIC(20,8),
    sum_toptrader_long_short_ratio    NUMERIC(20,8),
    count_long_short_ratio            NUMERIC(20,8),
    sum_taker_long_short_vol_ratio    NUMERIC(20,8),
    created_at                        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT futures_metric_snapshot_pk PRIMARY KEY (symbol, snapshot_time)
);

-- Every consumer scans one symbol over a time range, which the primary key already serves. This
-- index supports the cross-symbol regime query - "what was market-wide positioning at time T" -
-- which reads all symbols at a given instant instead.
CREATE INDEX futures_metric_snapshot_time_idx ON futures_metric_snapshot (snapshot_time);

-- Archives are occasionally missing a day, and a partially imported day must be resumable without
-- creating duplicates. Import progress is tracked per symbol-day so a re-run is idempotent.
CREATE TABLE futures_metric_import (
    symbol        VARCHAR(30) NOT NULL,
    archive_day   DATE        NOT NULL,
    row_count     INTEGER     NOT NULL,
    status        VARCHAR(20) NOT NULL,
    imported_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT futures_metric_import_pk PRIMARY KEY (symbol, archive_day)
);
