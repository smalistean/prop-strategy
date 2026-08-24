-- Reusable SQL-first outcome surface. Scenario reports read this view; execution never does.

CREATE FUNCTION xvf_shadow_depth_vwap(snapshot JSONB, book_side TEXT, target_notional NUMERIC)
RETURNS NUMERIC
LANGUAGE sql
IMMUTABLE
PARALLEL SAFE
AS $function$
    WITH levels AS (
        SELECT (value ->> 'price')::numeric AS price,
               (value ->> 'quantity')::numeric AS quantity,
               level_number
        FROM jsonb_array_elements(
            CASE WHEN book_side IN ('bids', 'asks')
                 THEN snapshot -> 'orderBook' -> book_side ELSE '[]'::jsonb END)
             WITH ORDINALITY AS level(value, level_number)
    ), cumulative AS (
        SELECT price,
               price * quantity AS level_notional,
               sum(price * quantity) OVER (ORDER BY level_number) AS cumulative_notional
        FROM levels
    ), consumed AS (
        SELECT price,
               greatest(least(level_notional,
                   target_notional - (cumulative_notional - level_notional)), 0) AS take_notional
        FROM cumulative
    )
    SELECT CASE WHEN target_notional > 0 AND sum(take_notional) >= target_notional
                THEN round(sum(take_notional)
                     / NULLIF(sum(take_notional / price), 0), 12) END
    FROM consumed;
$function$;

CREATE VIEW xvf_shadow_candidate_outcome_v1 AS
WITH candidates AS (
    SELECT r.signal_run_id, c.evaluation_order, r.production_date,
           r.scheduled_decision_at, r.cutoff_utc AS entry_cutoff_utc, r.capital_usd,
           r.configuration_snapshot, c.gross_rank, c.baseline_book_rank, c.shadow_book_rank,
           c.base, c.pair_type, c.short_venue, c.short_venue_symbol,
           c.long_venue, c.long_venue_symbol, c.maker_venue, c.taker_venue,
           c.planned_hold_hours, c.requested_leg_notional_usd AS leg_notional_usd,
           c.adjusted_spread_annual_pct, c.entry_basis_bps,
           c.expected_funding_bps, c.expected_basis_pnl_bps,
           c.expected_entry_fee_bps, c.expected_exit_fee_bps,
           c.expected_slippage_bps, c.risk_penalty_bps, c.expected_net_bps,
           c.short_leg_snapshot, c.long_leg_snapshot, c.score_components,
           (c.score_components ->> 'shortExecutableEntryPrice')::numeric AS short_entry_raw,
           (c.score_components ->> 'longExecutableEntryPrice')::numeric AS long_entry_raw,
           (c.short_leg_snapshot ->> 'baseUnitsPerContract')::numeric AS short_multiplier,
           (c.long_leg_snapshot ->> 'baseUnitsPerContract')::numeric AS long_multiplier
    FROM xvf_signal_candidate c
    JOIN xvf_signal_run r USING (signal_run_id)
    WHERE c.score_status = 'SCORABLE'
), outcomes AS (
    SELECT c.*, o.outcome_attempt_id, o.target_exit_utc, o.captured_at,
           o.capture_tolerance_seconds, o.short_exit_snapshot, o.long_exit_snapshot,
           o.funding_observations, o.funding_watermarks, o.data_issues,
           o.formula_inputs_version,
           xvf_shadow_depth_vwap(o.short_exit_snapshot, 'asks', c.leg_notional_usd)
               AS short_exit_raw,
           xvf_shadow_depth_vwap(o.long_exit_snapshot, 'bids', c.leg_notional_usd)
               AS long_exit_raw
    FROM candidates c
    JOIN xvf_signal_candidate_outcome o
      ON o.signal_run_id = c.signal_run_id
     AND o.evaluation_order = c.evaluation_order
     AND o.horizon_hours = c.planned_hold_hours
     AND o.capture_status = 'COMPLETE'
), components AS (
    SELECT o.*, funding.short_rate_sum, funding.long_rate_sum,
           o.short_entry_raw / o.short_multiplier AS short_entry_normalized,
           o.long_entry_raw / o.long_multiplier AS long_entry_normalized,
           o.short_exit_raw / o.short_multiplier AS short_exit_normalized,
           o.long_exit_raw / o.long_multiplier AS long_exit_normalized,
           o.leg_notional_usd / (o.short_entry_raw / o.short_multiplier)
               AS short_canonical_base_quantity,
           o.leg_notional_usd / (o.long_entry_raw / o.long_multiplier)
               AS long_canonical_base_quantity,
           o.leg_notional_usd / (o.short_entry_raw / o.short_multiplier)
               * ((o.short_entry_raw - o.short_exit_raw) / o.short_multiplier)
               AS short_price_pnl_usd,
           o.leg_notional_usd / (o.long_entry_raw / o.long_multiplier)
               * ((o.long_exit_raw - o.long_entry_raw) / o.long_multiplier)
               AS long_price_pnl_usd,
           o.leg_notional_usd * funding.short_rate_sum AS short_funding_usd,
           -o.leg_notional_usd * funding.long_rate_sum AS long_funding_usd,
           o.leg_notional_usd * (o.expected_entry_fee_bps + o.expected_exit_fee_bps) / 10000
               AS assumed_fees_usd,
           o.leg_notional_usd * o.expected_net_bps / 10000 AS expected_net_usd
    FROM outcomes o
    LEFT JOIN LATERAL (
        SELECT sum(f."fundingRate") FILTER (
                   WHERE f.venue = o.short_venue AND f."venueSymbol" = o.short_venue_symbol)
                   AS short_rate_sum,
               sum(f."fundingRate") FILTER (
                   WHERE f.venue = o.long_venue AND f."venueSymbol" = o.long_venue_symbol)
                   AS long_rate_sum
        FROM jsonb_to_recordset(o.funding_observations) AS f(
            venue text, "venueSymbol" text, "fundingTime" timestamptz, "fundingRate" numeric)
        WHERE f."fundingTime" > o.entry_cutoff_utc
          AND f."fundingTime" <= o.target_exit_utc
    ) funding ON true
)
SELECT c.*,
       (c.short_exit_snapshot #>> '{topOfBook,bidPrice}')::numeric / c.short_multiplier
           AS short_exit_best_bid_normalized,
       (c.short_exit_snapshot #>> '{topOfBook,askPrice}')::numeric / c.short_multiplier
           AS short_exit_best_ask_normalized,
       (c.short_exit_snapshot #>> '{reference,markPrice}')::numeric / c.short_multiplier
           AS short_exit_mark_normalized,
       (c.short_exit_snapshot #>> '{reference,indexPrice}')::numeric / c.short_multiplier
           AS short_exit_index_normalized,
       (c.long_exit_snapshot #>> '{topOfBook,bidPrice}')::numeric / c.long_multiplier
           AS long_exit_best_bid_normalized,
       (c.long_exit_snapshot #>> '{topOfBook,askPrice}')::numeric / c.long_multiplier
           AS long_exit_best_ask_normalized,
       (c.long_exit_snapshot #>> '{reference,markPrice}')::numeric / c.long_multiplier
           AS long_exit_mark_normalized,
       (c.long_exit_snapshot #>> '{reference,indexPrice}')::numeric / c.long_multiplier
           AS long_exit_index_normalized,
       CASE WHEN c.short_exit_raw IS NOT NULL THEN c.leg_notional_usd END
           AS short_exit_filled_usd,
       CASE WHEN c.long_exit_raw IS NOT NULL THEN c.leg_notional_usd END
           AS long_exit_filled_usd,
       c.short_price_pnl_usd + c.long_price_pnl_usd AS basis_price_pnl_usd,
       c.short_funding_usd + c.long_funding_usd AS realized_funding_usd,
       c.short_price_pnl_usd + c.long_price_pnl_usd
         + c.short_funding_usd + c.long_funding_usd - c.assumed_fees_usd
           AS realized_net_usd,
       c.short_price_pnl_usd + c.long_price_pnl_usd
         + c.short_funding_usd + c.long_funding_usd - c.assumed_fees_usd
         - c.expected_net_usd AS prediction_error_usd,
       (c.short_price_pnl_usd + c.long_price_pnl_usd
         + c.short_funding_usd + c.long_funding_usd - c.assumed_fees_usd)
         / (2 * c.leg_notional_usd) AS return_on_used_capital,
       (c.short_price_pnl_usd + c.long_price_pnl_usd
         + c.short_funding_usd + c.long_funding_usd - c.assumed_fees_usd)
         / c.leg_notional_usd * 10000 AS realized_net_bps_per_leg_notional,
       (c.short_price_pnl_usd + c.long_price_pnl_usd
         + c.short_funding_usd + c.long_funding_usd - c.assumed_fees_usd)
         / c.capital_usd AS return_on_declared_total_capital,
       extract(epoch FROM c.captured_at - c.target_exit_utc)::numeric AS capture_delay_seconds,
       c.captured_at <= c.target_exit_utc
           + make_interval(secs => c.capture_tolerance_seconds) AS captured_within_tolerance,
       ln(c.short_entry_normalized / c.long_entry_normalized) * 10000
           AS entry_basis_bps_recalculated,
       ln(c.short_exit_normalized / c.long_exit_normalized) * 10000 AS exit_basis_bps,
       ln(((c.short_exit_snapshot #>> '{reference,markPrice}')::numeric / c.short_multiplier)
          / ((c.long_exit_snapshot #>> '{reference,markPrice}')::numeric / c.long_multiplier))
          * 10000 AS exit_mark_basis_bps,
       ln(((c.short_exit_snapshot #>> '{reference,indexPrice}')::numeric / c.short_multiplier)
          / ((c.long_exit_snapshot #>> '{reference,indexPrice}')::numeric / c.long_multiplier))
          * 10000 AS exit_index_basis_bps,
       'INITIAL_LEG_NOTIONAL'::text AS funding_cash_model,
       'DECISION_TIME_ASSUMPTION'::text AS fee_model,
       'OPTIMISTIC_PASSIVE_TOUCH_NOT_FILL_PROVEN'::text AS maker_fill_model
FROM components c;

COMMENT ON VIEW xvf_shadow_candidate_outcome_v1 IS
    'Formula-v1 expected-versus-realized XVF candidate outcomes for SQL-only analysis.';
