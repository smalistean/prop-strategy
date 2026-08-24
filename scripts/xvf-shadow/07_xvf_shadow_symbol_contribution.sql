-- Contribution and tails by normalized symbol, plus a venue-pair drill-down.

WITH grouped AS (
    SELECT CASE WHEN grouping(short_venue) = 1 THEN 'SYMBOL' ELSE 'SYMBOL_VENUE_PAIR' END
               AS aggregation_level,
           base,
           CASE WHEN grouping(short_venue) = 1 THEN 'ALL' ELSE short_venue END AS short_venue,
           CASE WHEN grouping(long_venue) = 1 THEN 'ALL' ELSE long_venue END AS long_venue,
           count(*) AS observations,
           min(scheduled_decision_at) AS first_observation,
           max(scheduled_decision_at) AS last_observation,
           count(*) FILTER (WHERE NOT captured_within_tolerance) AS late_observations,
           sum(expected_net_usd) AS expected_net_usd,
           sum(realized_funding_usd) AS realized_funding_usd,
           sum(basis_price_pnl_usd) AS basis_price_pnl_usd,
           sum(assumed_fees_usd) AS assumed_fees_usd,
           sum(realized_net_usd) AS realized_net_usd,
           avg(realized_net_usd) AS average_net_usd,
           percentile_cont(0.5) WITHIN GROUP (ORDER BY realized_net_usd) AS median_net_usd,
           percentile_cont(0.1) WITHIN GROUP (ORDER BY realized_net_usd) AS p10_net_usd,
           percentile_cont(0.9) WITHIN GROUP (ORDER BY realized_net_usd) AS p90_net_usd,
           min(realized_net_usd) AS worst_outcome_usd,
           max(realized_net_usd) AS best_outcome_usd,
           avg((realized_net_usd > 0)::int) AS profitable_ratio
    FROM xvf_shadow_candidate_outcome_v1
    GROUP BY GROUPING SETS ((base), (base, short_venue, long_venue))
)
SELECT *,
       CASE WHEN observations >= 30 THEN 'PRELIMINARY_SAMPLE'
            WHEN observations >= 10 THEN 'DIAGNOSTIC_ONLY'
            ELSE 'INSUFFICIENT_SAMPLE' END AS evidence_status,
       'NO_SYMBOL_EXCLUSION_BEFORE_30_OUTCOMES'::text AS selection_guardrail,
       'COMPLETE_OUTCOMES_ONLY'::text AS missing_data_policy
FROM grouped
ORDER BY aggregation_level, realized_net_usd DESC NULLS LAST, base, short_venue, long_venue;
