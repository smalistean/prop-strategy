-- Add explicit scheduled decision and capture-window timing to the immutable XVF shadow ledger.
--
-- The previous schema assigned cutoff_utc after market fetches finished, while signal evaluation
-- happened earlier. These columns separate the scheduled decision time from the wall-clock
-- capture window so skew and staleness can be measured rather than hidden.

ALTER TABLE xvf_signal_run
    ADD COLUMN scheduled_decision_at TIMESTAMPTZ(6),
    ADD COLUMN capture_started_at  TIMESTAMPTZ(6),
    ADD COLUMN capture_ended_at    TIMESTAMPTZ(6),
    ADD COLUMN scheduled_attempt_id VARCHAR(80);

-- Existing rows were written before timing was captured. V21 made the ledger append-only, so
-- temporarily suspend only the row mutation trigger for this migration-owned backfill. PostgreSQL
-- executes this migration transactionally: a later failure also rolls back the trigger state.
ALTER TABLE xvf_signal_run
    DISABLE TRIGGER xvf_signal_run_reject_update_delete_trg;

UPDATE xvf_signal_run
   SET scheduled_decision_at = cutoff_utc,
       capture_started_at = cutoff_utc,
       capture_ended_at = generated_at,
       scheduled_attempt_id = 'LEGACY-' || signal_run_id::text;

ALTER TABLE xvf_signal_run
    ENABLE TRIGGER xvf_signal_run_reject_update_delete_trg;

ALTER TABLE xvf_signal_run
    ALTER COLUMN scheduled_decision_at SET NOT NULL,
    ALTER COLUMN capture_started_at SET NOT NULL,
    ALTER COLUMN capture_ended_at SET NOT NULL,
    ALTER COLUMN scheduled_attempt_id SET NOT NULL;

ALTER TABLE xvf_signal_run
    ADD CONSTRAINT xvf_signal_run_scheduled_decision_ck
        CHECK (scheduled_decision_at <= cutoff_utc),
    ADD CONSTRAINT xvf_signal_run_capture_window_ck
        CHECK (capture_started_at <= capture_ended_at),
    ADD CONSTRAINT xvf_signal_run_generated_after_capture_ck
        CHECK (generated_at >= capture_ended_at),
    ADD CONSTRAINT xvf_signal_run_scheduled_attempt_id_ck
        CHECK (btrim(scheduled_attempt_id) <> '');

COMMENT ON COLUMN xvf_signal_run.scheduled_decision_at IS
    'Scheduled production decision timestamp in UTC, recorded before any market fetch.';
COMMENT ON COLUMN xvf_signal_run.capture_started_at IS
    'Wall-clock timestamp when the service started capturing market data.';
COMMENT ON COLUMN xvf_signal_run.capture_ended_at IS
    'Wall-clock timestamp when the last venue response was collected.';
COMMENT ON COLUMN xvf_signal_run.scheduled_attempt_id IS
    'Stable idempotency key for the scheduled production attempt.';
