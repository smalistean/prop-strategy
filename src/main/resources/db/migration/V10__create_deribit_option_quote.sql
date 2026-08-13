-- Deribit option chain snapshots, recorded forward from 2026-08-12.
--
-- Purpose: item 4 in RESEARCH_OPTIONS.md compares the options-implied forward rate against
-- perpetual funding. Put-call parity needs QUOTES, and Deribit's public history serves trades only -
-- get_last_trades_by_instrument returns nothing for the strikes that matter, because most strikes do
-- not trade on a given day. Free historical quotes do not exist, so the only route without paid data
-- is to start recording and wait. Nothing can be backtested from this table until months have
-- accumulated; that is understood and is the reason it is being started now rather than later.
--
-- Two settlement conventions are stored side by side and MUST NOT be mixed:
--   * BTC and ETH options are inverse ("reversed"): prices are quoted in the base coin, so a USD
--     price is mark_price * underlying_price.
--   * The USDC-settled chains (BTC_USDC, ETH_USDC, SOL_USDC, XRP_USDC, TRX_USDC, HYPE_USDC,
--     AVAX_USDC) are linear: prices are already in USDC.
-- quote_currency is what distinguishes them and is therefore NOT NULL. A parity calculation that
-- ignores it produces a forward wrong by a factor of the spot price.
--
-- bid_price and ask_price are nullable on purpose. An empty side is real information here: the first
-- measurement of this trade found the executable forward at a 3.6-day expiry to be -288% annualised
-- against +8.3% at the mark, entirely from bid-ask width. Recording only marks would hide the single
-- largest cost in the strategy.
--
-- index_price repeats per row rather than living in its own table. It is identical across all
-- instruments of one currency at one snapshot, so this is redundant, and it is kept anyway so a
-- parity calculation needs no join - that calculation is the entire purpose of the table.
CREATE TABLE deribit_option_quote (
    snapshot_time     TIMESTAMPTZ     NOT NULL,
    instrument_name   VARCHAR(40)     NOT NULL,
    underlying        VARCHAR(20)     NOT NULL,
    quote_currency    VARCHAR(10)     NOT NULL,
    expiry_time       TIMESTAMPTZ     NOT NULL,
    strike            NUMERIC(30,8)   NOT NULL,
    option_type       CHAR(1)         NOT NULL,
    bid_price         NUMERIC(30,12),
    ask_price         NUMERIC(30,12),
    mark_price        NUMERIC(30,12)  NOT NULL,
    mark_iv           NUMERIC(12,4),
    underlying_price  NUMERIC(30,8),
    index_price       NUMERIC(30,8),
    open_interest     NUMERIC(30,8),
    volume_24h        NUMERIC(30,8),
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT deribit_option_quote_pk PRIMARY KEY (snapshot_time, instrument_name),
    CONSTRAINT deribit_option_quote_type_ck CHECK (option_type IN ('C', 'P'))
);

-- Parity pairs a call with a put at the same underlying, expiry and strike, then walks that forward
-- through time. The primary key orders by snapshot first, which does not serve that access pattern.
CREATE INDEX deribit_option_quote_chain_idx
    ON deribit_option_quote (underlying, expiry_time, strike, snapshot_time);
