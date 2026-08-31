-- Collapses binance_perp_funding_rate to one row per funding print (symbol, funding_time).
--
-- The old primary key was (symbol, funding_time, rate_type), and rate_type is really a SOURCE label:
-- three importers write the same Binance funding prints under three different labels, each upserting
-- only within its own label, so the same payment is stored once per source that covers it.
--
--   ARCHIVE  FundingArchiveImportApplication  monthly data.binance.vision archives, no mark_price
--   Regular  BtcFundingRateImportApplication  REST fundingRate endpoint, carries mark_price
--   REST     VenueFundingImportApplication    REST top-up for the months the archive has not
--                                             published yet, no mark_price
--
-- Verified against the live database at 2026-08-30T11:49:56Z: 2,278,734 prints stored once,
-- 366,529 twice, 6,885 three times, and ZERO prints with disagreeing funding_rate values across
-- sources. Every plain SUM over the table double-counts funding wherever coverage overlaps;
-- perp_funding_all, CarryHarvestApplication and CrossSectionalMomentumApplication each rediscovered
-- this independently and dedupe with GROUP BY (symbol, funding_time) before aggregating. Making the
-- print unique makes the straightforward query correct instead of a trap.
--
-- rate_type survives as a provenance column - it says which source supplied the row - it just no
-- longer multiplies rows. The importers upsert on (symbol, funding_time) from now on.

-- The sources agreed on every overlapping rate when this was written. If they ever disagree, picking
-- one silently would discard a real discrepancy, so the migration refuses and leaves the data for a
-- human. Flyway runs this file in one transaction; a failure here applies nothing.
DO $$
DECLARE
    conflicting bigint;
BEGIN
    SELECT COUNT(*) INTO conflicting FROM (
        SELECT 1 FROM binance_perp_funding_rate
        GROUP BY symbol, funding_time
        HAVING COUNT(DISTINCT funding_rate) > 1
    ) prints;
    IF conflicting > 0 THEN
        RAISE EXCEPTION 'binance_perp_funding_rate: % prints carry conflicting funding_rate values '
                'across rate_types; resolve them before deduplicating', conflicting;
    END IF;
END $$;

-- Only 'Regular' rows carry mark_price (48,513 of them; ARCHIVE and REST store none), and at most
-- one 'Regular' row exists per print under the old key. Where an ARCHIVE row will be kept over a
-- Regular duplicate, copy the mark_price across first so deleting the duplicate loses nothing.
UPDATE binance_perp_funding_rate keeper
SET mark_price = duplicate.mark_price
FROM binance_perp_funding_rate duplicate
WHERE keeper.symbol = duplicate.symbol
  AND keeper.funding_time = duplicate.funding_time
  AND keeper.mark_price IS NULL
  AND duplicate.mark_price IS NOT NULL
  AND keeper.rate_type = 'ARCHIVE';

-- Keep one row per print, preferring ARCHIVE > Regular > REST. ARCHIVE wins because
-- FundingArchiveImportApplication skips every month that already contains ARCHIVE rows for the
-- symbol; keeping those labels is what stops it re-downloading years of archives on every run.
-- The trailing rate_type comparison makes the ordering total for any label outside the known three.
DELETE FROM binance_perp_funding_rate victim
USING binance_perp_funding_rate keeper
WHERE keeper.symbol = victim.symbol
  AND keeper.funding_time = victim.funding_time
  AND (CASE keeper.rate_type WHEN 'ARCHIVE' THEN 0 WHEN 'Regular' THEN 1 WHEN 'REST' THEN 2 ELSE 3 END,
       keeper.rate_type)
    < (CASE victim.rate_type WHEN 'ARCHIVE' THEN 0 WHEN 'Regular' THEN 1 WHEN 'REST' THEN 2 ELSE 3 END,
       victim.rate_type);

-- The V2 primary key was unnamed, so it holds the default name to this day; V12 renamed the table
-- but not this constraint. The new name follows the <table>_pk convention of the venue tables.
ALTER TABLE binance_perp_funding_rate DROP CONSTRAINT futures_funding_rate_pkey;
ALTER TABLE binance_perp_funding_rate
    ADD CONSTRAINT binance_perp_funding_rate_pk PRIMARY KEY (symbol, funding_time);
