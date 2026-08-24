-- Greedy capital-aware backfill for each immutable decision cycle. This measures allocation and
-- stranded collateral, but deliberately does not pretend that positions were retained across runs.

WITH RECURSIVE scenarios AS (
    SELECT 1 AS scenario_order, 'EQUAL_THIRDS'::text AS scenario,
           1::numeric / 3 AS binance_share, 1::numeric / 3 AS bybit_share,
           1::numeric / 3 AS hyperliquid_share
    UNION ALL
    SELECT 2, 'CHALLENGER_40_25_35', .40, .25, .35
    UNION ALL
    SELECT 100 + b + y, format('GRID_%s_%s_%s', b, y, 100 - b - y),
           b::numeric / 100, y::numeric / 100, (100 - b - y)::numeric / 100
    FROM generate_series(20, 60, 10) b
    CROSS JOIN generate_series(20, 60, 10) y
    WHERE 100 - b - y BETWEEN 20 AND 60
), universe AS (
    SELECT c.signal_run_id, c.evaluation_order, r.scheduled_decision_at, r.capital_usd,
           c.base, c.short_venue, c.long_venue,
           c.requested_leg_notional_usd AS leg_notional_usd, c.expected_net_bps,
           c.requested_leg_notional_usd * c.expected_net_bps / 10000 AS expected_net_usd,
           o.realized_funding_usd, o.basis_price_pnl_usd, o.assumed_fees_usd,
           o.realized_net_usd,
           row_number() OVER (PARTITION BY c.signal_run_id
                              ORDER BY c.expected_net_bps DESC, c.gross_rank) AS candidate_number
    FROM xvf_signal_candidate c
    JOIN xvf_signal_run r USING (signal_run_id)
    LEFT JOIN xvf_shadow_candidate_outcome_v1 o
      ON o.signal_run_id = c.signal_run_id AND o.evaluation_order = c.evaluation_order
    WHERE c.score_status = 'SCORABLE'
      AND c.expected_net_bps > 0
      AND c.short_venue IN ('binance', 'bybit', 'hyperliquid')
      AND c.long_venue IN ('binance', 'bybit', 'hyperliquid')
), run_sizes AS (
    SELECT signal_run_id, max(scheduled_decision_at) AS scheduled_decision_at,
           max(capital_usd) AS capital_usd, count(*) AS candidate_count
    FROM universe
    GROUP BY signal_run_id
), state AS (
    SELECT s.scenario_order, s.scenario, s.binance_share, s.bybit_share,
           s.hyperliquid_share, r.signal_run_id, r.scheduled_decision_at, r.capital_usd,
           0::bigint AS candidate_number,
           r.capital_usd * s.binance_share AS binance_remaining,
           r.capital_usd * s.bybit_share AS bybit_remaining,
           r.capital_usd * s.hyperliquid_share AS hyperliquid_remaining,
           ARRAY[]::text[] AS selected_bases, 0 AS selected_pairs,
           0 AS capital_rejections, 0 AS duplicate_base_rejections,
           0 AS position_limit_rejections, 0 AS measured_outcomes,
           0 AS missing_outcomes, 0::numeric AS blocked_expected_net_usd,
           0::numeric AS expected_net_usd, 0::numeric AS realized_funding_usd,
           0::numeric AS basis_price_pnl_usd, 0::numeric AS assumed_fees_usd,
           0::numeric AS realized_net_usd
    FROM scenarios s
    CROSS JOIN run_sizes r

    UNION ALL

    SELECT st.scenario_order, st.scenario, st.binance_share, st.bybit_share,
           st.hyperliquid_share, st.signal_run_id, st.scheduled_decision_at, st.capital_usd,
           u.candidate_number,
           st.binance_remaining - CASE WHEN decision.accepted AND
                 'binance' IN (u.short_venue, u.long_venue) THEN u.leg_notional_usd ELSE 0 END,
           st.bybit_remaining - CASE WHEN decision.accepted AND
                 'bybit' IN (u.short_venue, u.long_venue) THEN u.leg_notional_usd ELSE 0 END,
           st.hyperliquid_remaining - CASE WHEN decision.accepted AND
                 'hyperliquid' IN (u.short_venue, u.long_venue)
                 THEN u.leg_notional_usd ELSE 0 END,
           CASE WHEN decision.accepted THEN array_append(st.selected_bases, u.base)
                ELSE st.selected_bases END,
           st.selected_pairs + decision.accepted::int,
           st.capital_rejections + decision.capital_blocked::int,
           st.duplicate_base_rejections + decision.duplicate_base::int,
           st.position_limit_rejections + decision.position_blocked::int,
           st.measured_outcomes + (decision.accepted AND u.realized_net_usd IS NOT NULL)::int,
           st.missing_outcomes + (decision.accepted AND u.realized_net_usd IS NULL)::int,
           st.blocked_expected_net_usd
             + CASE WHEN decision.capital_blocked THEN u.expected_net_usd ELSE 0 END,
           st.expected_net_usd + CASE WHEN decision.accepted THEN u.expected_net_usd ELSE 0 END,
           st.realized_funding_usd + CASE WHEN decision.accepted
                 THEN coalesce(u.realized_funding_usd, 0) ELSE 0 END,
           st.basis_price_pnl_usd + CASE WHEN decision.accepted
                 THEN coalesce(u.basis_price_pnl_usd, 0) ELSE 0 END,
           st.assumed_fees_usd + CASE WHEN decision.accepted
                 THEN coalesce(u.assumed_fees_usd, 0) ELSE 0 END,
           st.realized_net_usd + CASE WHEN decision.accepted
                 THEN coalesce(u.realized_net_usd, 0) ELSE 0 END
    FROM state st
    JOIN universe u ON u.signal_run_id = st.signal_run_id
                   AND u.candidate_number = st.candidate_number + 1
    CROSS JOIN LATERAL (
        SELECT st.selected_pairs >= 20 AS position_blocked,
               u.base = ANY(st.selected_bases) AS duplicate_base,
               (CASE u.short_venue
                    WHEN 'binance' THEN st.binance_remaining
                    WHEN 'bybit' THEN st.bybit_remaining
                    ELSE st.hyperliquid_remaining END < u.leg_notional_usd
                OR CASE u.long_venue
                    WHEN 'binance' THEN st.binance_remaining
                    WHEN 'bybit' THEN st.bybit_remaining
                    ELSE st.hyperliquid_remaining END < u.leg_notional_usd) AS lacks_capital
    ) gates
    CROSS JOIN LATERAL (
        SELECT NOT gates.position_blocked AND NOT gates.duplicate_base AND NOT gates.lacks_capital
                   AS accepted,
               NOT gates.position_blocked AND NOT gates.duplicate_base AND gates.lacks_capital
                   AS capital_blocked,
               NOT gates.position_blocked AND gates.duplicate_base AS duplicate_base,
               gates.position_blocked AS position_blocked
    ) decision
), terminal AS (
    SELECT st.*
    FROM state st
    JOIN run_sizes r USING (signal_run_id)
    WHERE st.candidate_number = r.candidate_count
)
SELECT scenario_order, scenario,
       round(binance_share * 100, 2) AS binance_pct,
       round(bybit_share * 100, 2) AS bybit_pct,
       round(hyperliquid_share * 100, 2) AS hyperliquid_pct,
       count(*) AS decision_cycles,
       avg(selected_pairs) AS average_selected_pairs,
       sum(capital_rejections) AS capital_rejections,
       sum(duplicate_base_rejections) AS duplicate_base_rejections,
       sum(position_limit_rejections) AS position_limit_rejections,
       sum(measured_outcomes) AS measured_outcomes,
       sum(missing_outcomes) AS missing_outcomes,
       sum(blocked_expected_net_usd) AS expected_net_blocked_by_capital_usd,
       avg(capital_usd * binance_share - binance_remaining) AS average_binance_used_usd,
       avg(capital_usd * bybit_share - bybit_remaining) AS average_bybit_used_usd,
       avg(capital_usd * hyperliquid_share - hyperliquid_remaining)
           AS average_hyperliquid_used_usd,
       avg(binance_remaining) AS average_binance_stranded_usd,
       avg(bybit_remaining) AS average_bybit_stranded_usd,
       avg(hyperliquid_remaining) AS average_hyperliquid_stranded_usd,
       sum(expected_net_usd) AS expected_net_usd,
       sum(realized_funding_usd) AS realized_funding_usd,
       sum(basis_price_pnl_usd) AS basis_price_pnl_usd,
       sum(assumed_fees_usd) AS assumed_fees_usd,
       sum(realized_net_usd) AS realized_net_usd,
       sum(realized_net_usd) / NULLIF(sum(capital_usd), 0) AS return_on_cycle_capital,
       'EXPECTED_NET_DESC_GREEDY_BACKFILL_MAX_20_ONE_PAIR_PER_BASE'::text AS selection_model,
       'INDEPENDENT_72H_CYCLES_NO_RETENTION_OR_TRANSFERS'::text AS transition_model,
       'MISSING_OUTCOMES_EXCLUDED_FROM_REALIZED_COMPONENTS_AND_COUNTED'::text
           AS missing_data_policy
FROM terminal
GROUP BY scenario_order, scenario, binance_share, bybit_share, hyperliquid_share
ORDER BY scenario_order, scenario;
