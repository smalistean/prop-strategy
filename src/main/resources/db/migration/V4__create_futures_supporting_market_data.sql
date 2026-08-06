CREATE TABLE futures_open_interest_statistic (
    symbol                       VARCHAR(20)     NOT NULL,
    period                       VARCHAR(10)     NOT NULL,
    statistic_time               TIMESTAMPTZ     NOT NULL,
    sum_open_interest            NUMERIC(38, 12) NOT NULL,
    sum_open_interest_value      NUMERIC(38, 12) NOT NULL,
    circulating_supply           NUMERIC(38, 12),
    created_at                   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (symbol, period, statistic_time)
);

CREATE TABLE futures_trader_ratio (
    symbol                       VARCHAR(20)     NOT NULL,
    period                       VARCHAR(10)     NOT NULL,
    ratio_type                   VARCHAR(30)     NOT NULL,
    statistic_time               TIMESTAMPTZ     NOT NULL,
    long_short_ratio             NUMERIC(30, 18) NOT NULL,
    long_share                   NUMERIC(30, 18) NOT NULL,
    short_share                  NUMERIC(30, 18) NOT NULL,
    created_at                   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at                   TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (symbol, period, ratio_type, statistic_time),
    CHECK (ratio_type IN ('GLOBAL_ACCOUNT', 'TOP_ACCOUNT', 'TOP_POSITION')),
    CHECK (long_share >= 0),
    CHECK (short_share >= 0)
);
