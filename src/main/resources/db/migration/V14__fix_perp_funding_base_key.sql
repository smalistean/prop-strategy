-- Fixes the cross-venue join key in perp_funding_all.
--
-- V13 stored `base` as the venue's own symbol with only the quote currency removed. That leaves the
-- same asset under three different keys:
--
--     1000PEPE   binance, bybit
--     PEPE       bitget, dydx, gate, okx
--     kPEPE      hyperliquid
--
-- A join on base therefore splits one asset into three and matches none of them. Nothing fails; the
-- venues simply never meet, and the assets that vanish are the meme coins - exactly where funding is
-- most extreme and the cross-venue spread most likely to be worth trading.
--
-- Prefixes actually present, surveyed rather than assumed:
--
--     1000     18 bases across 3 venues   1000PEPE, 1000BONK, 1000SATS, ...
--     k         7 bases on hyperliquid    kPEPE, kSHIB, kBONK, ...
--     1000000   3 bases across 3 venues   1000000BABYDOGE, 1000000BOB, 1000000MOG
--     10000     2 bases across 2 venues   10000SATS, 10000NEX
--     1M        2 bases across 2 venues   1MBABYDOGE, 1MCHEEMS
--
-- Order matters: 1000000 must be stripped before 1000, or "1000000MOG" becomes "000MOG".
--
-- These prefixes are contract-size multipliers - 1000PEPE is one contract over 1000 PEPE. The
-- FUNDING RATE is a percentage of notional and is therefore unaffected by the multiplier, so
-- normalising the name is sufficient and no rate scaling is applied. That would NOT be true of a
-- price or a quantity, and any future price table must handle the multiplier explicitly.
--
-- The 'k' rule requires a lowercase k followed by an uppercase letter, which is Hyperliquid's
-- convention. Matching a bare leading "k" would corrupt any asset legitimately starting with one.

CREATE OR REPLACE FUNCTION normalise_perp_base(raw TEXT) RETURNS TEXT AS $$
    SELECT CASE
        WHEN raw ~ '^1000000[A-Z]' THEN substring(raw from 8)
        WHEN raw ~ '^100000[A-Z]'  THEN substring(raw from 7)
        WHEN raw ~ '^10000[A-Z]'   THEN substring(raw from 6)
        WHEN raw ~ '^1000[A-Z]'    THEN substring(raw from 5)
        WHEN raw ~ '^1M[A-Z]'      THEN substring(raw from 3)
        WHEN raw ~ '^k[A-Z]'       THEN substring(raw from 2)
        ELSE raw
    END;
$$ LANGUAGE SQL IMMUTABLE;

DROP VIEW IF EXISTS perp_funding_all;

CREATE VIEW perp_funding_all AS
    SELECT 'binance'::text AS venue, symbol AS venue_symbol,
           normalise_perp_base(
               CASE WHEN symbol LIKE '%USDT' OR symbol LIKE '%USDC'
                    THEN left(symbol, length(symbol) - 4) ELSE symbol END) AS base,
           funding_time, rate AS funding_rate
    FROM (SELECT symbol, funding_time, max(funding_rate) AS rate
          FROM binance_perp_funding_rate GROUP BY symbol, funding_time) binance_deduplicated
UNION ALL
    SELECT 'hyperliquid', coin, normalise_perp_base(coin), funding_time, funding_rate
    FROM hyperliquid_perp_funding_rate
UNION ALL SELECT 'bybit',  venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM bybit_perp_funding_rate
UNION ALL SELECT 'okx',    venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM okx_perp_funding_rate
UNION ALL SELECT 'gate',   venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM gate_perp_funding_rate
UNION ALL SELECT 'bitget', venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM bitget_perp_funding_rate
UNION ALL SELECT 'dydx',   venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM dydx_perp_funding_rate;
