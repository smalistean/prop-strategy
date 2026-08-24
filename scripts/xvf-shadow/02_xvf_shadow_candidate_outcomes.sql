-- One auditable row per COMPLETE outcome. The formula is versioned in
-- V25__create_xvf_outcome_measurement_view.sql so every downstream report uses identical math.

SELECT *
FROM xvf_shadow_candidate_outcome_v1
ORDER BY target_exit_utc, signal_run_id, evaluation_order;
