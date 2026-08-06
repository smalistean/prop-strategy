CREATE TABLE futures_funding_rate (
    symbol          VARCHAR(20)     NOT NULL,
    funding_time    TIMESTAMPTZ     NOT NULL,
    rate_type       VARCHAR(20)     NOT NULL,
    funding_rate    NUMERIC(30, 18) NOT NULL,
    mark_price      NUMERIC(30, 12) NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    PRIMARY KEY (symbol, funding_time, rate_type)
);
