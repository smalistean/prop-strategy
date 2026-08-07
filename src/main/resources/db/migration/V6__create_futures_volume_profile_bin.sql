CREATE TABLE futures_volume_profile_bin (
    symbol VARCHAR(30) NOT NULL,
    bucket_time TIMESTAMPTZ NOT NULL,
    bucket_minutes SMALLINT NOT NULL CHECK (bucket_minutes > 0),
    price_step NUMERIC(30, 12) NOT NULL CHECK (price_step > 0),
    price_from NUMERIC(30, 12) NOT NULL,
    aggregate_trade_count BIGINT NOT NULL CHECK (aggregate_trade_count >= 0),
    base_volume NUMERIC(38, 18) NOT NULL,
    quote_notional NUMERIC(38, 8) NOT NULL,
    aggressive_buy_quote NUMERIC(38, 8) NOT NULL,
    aggressive_sell_quote NUMERIC(38, 8) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (symbol, bucket_time, bucket_minutes, price_step, price_from)
);

CREATE INDEX futures_volume_profile_bin_range_idx
    ON futures_volume_profile_bin (symbol, bucket_minutes, price_step, bucket_time);

CREATE TABLE futures_volume_profile_import (
    symbol VARCHAR(30) NOT NULL,
    archive_name TEXT NOT NULL,
    bucket_minutes SMALLINT NOT NULL,
    price_step NUMERIC(30, 12) NOT NULL,
    archive_sha256 CHAR(64) NOT NULL,
    source_rows BIGINT NOT NULL,
    bin_rows BIGINT NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (symbol, archive_name, bucket_minutes, price_step)
);
