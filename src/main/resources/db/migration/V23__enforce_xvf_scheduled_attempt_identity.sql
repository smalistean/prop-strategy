-- A scheduler retry must resolve to the already-recorded immutable attempt rather than append a
-- second decision. Independent audit experiments remain possible by supplying distinct attempt ids.

CREATE UNIQUE INDEX xvf_signal_run_scheduled_attempt_id_uq
    ON xvf_signal_run (scheduled_attempt_id);

COMMENT ON INDEX xvf_signal_run_scheduled_attempt_id_uq IS
    'One immutable XVF signal run per stable scheduler attempt id.';
