-- Checks whether the freshness check in XvfSignalEngine.rankedCandidates needs to look back more
-- than 1 day - i.e. should "fresh" require not being eligible for the past 2 or 3 days, not just
-- yesterday. Follow-up to analysis-freshness-discount.sql; run after it in the same session (reuses
-- the `elig` temp table) or re-run that script's setup first.
--
-- Method: same gaps-and-islands construction, but with the streak-reset threshold varied (gap > 1,
-- > 2, > 3 days) instead of fixed at 1, and calibration (realized/signal) compared across N.
--
-- Result, rerun 2026-08-21 against the corrected (non-overlapping signal/outcome window) `elig`
-- table from analysis-freshness-discount.sql - see that script's header for what changed and why:
--   N=1 (current): CEX-CEX fresh 43% / stale 29%,  CEX-DEX fresh 66% / stale 46%
--   N=2:           CEX-CEX fresh 44% / stale 29%,  CEX-DEX fresh 66% / stale 47%
--   N=3:           CEX-CEX fresh 43% / stale 30%,  CEX-DEX fresh 66% / stale 47%
-- The absolute numbers changed with the correction upstream, but this script's own conclusion did
-- not: calibration is still flat across N=1/2/3 within a point or two - no improvement from
-- requiring a longer clean gap, and the "fresh" bucket's sample size only shrinks as N grows (fewer
-- candidates qualify for full-strength treatment for no accuracy gain). N=1 (the current
-- implementation) is still the right choice.

-- Depends on `elig` from analysis-freshness-discount.sql. If run standalone, execute that script's
-- CREATE TEMP TABLE statements up through `elig` first.

CREATE TEMP TABLE gapped AS
SELECT *, d::date - LAG(d::date) OVER (PARTITION BY base ORDER BY d) AS gap_back
FROM elig;

SELECT 'N=1 (current)' def, pair_type,
       CASE WHEN gap_back IS NULL OR gap_back > 1 THEN 'fresh' ELSE 'stale' END bucket,
       round(avg(realized)::numeric,1) realized, round(avg(spread)::numeric,1) signal,
       round((avg(realized)/nullif(avg(spread),0)*100)::numeric,0) ratio_pct, count(*) n
FROM gapped WHERE realized IS NOT NULL GROUP BY 2,3
UNION ALL
SELECT 'N=2', pair_type,
       CASE WHEN gap_back IS NULL OR gap_back > 2 THEN 'fresh' ELSE 'stale' END,
       round(avg(realized)::numeric,1), round(avg(spread)::numeric,1),
       round((avg(realized)/nullif(avg(spread),0)*100)::numeric,0), count(*)
FROM gapped WHERE realized IS NOT NULL GROUP BY 2,3
UNION ALL
SELECT 'N=3', pair_type,
       CASE WHEN gap_back IS NULL OR gap_back > 3 THEN 'fresh' ELSE 'stale' END,
       round(avg(realized)::numeric,1), round(avg(spread)::numeric,1),
       round((avg(realized)/nullif(avg(spread),0)*100)::numeric,0), count(*)
FROM gapped WHERE realized IS NOT NULL GROUP BY 2,3
ORDER BY 2, 1, 3;
