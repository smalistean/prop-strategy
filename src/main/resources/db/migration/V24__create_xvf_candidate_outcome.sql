-- Immutable exit facts for measuring XVF predictions after their planned holding period.
-- Calculated P&L deliberately lives in versioned SQL, not in this table.

CREATE TABLE xvf_signal_candidate_outcome (
    outcome_attempt_id       UUID           NOT NULL,
    signal_run_id            UUID           NOT NULL,
    evaluation_order         INTEGER        NOT NULL,
    horizon_hours            INTEGER        NOT NULL,
    target_exit_utc          TIMESTAMPTZ(6) NOT NULL,
    capture_started_at       TIMESTAMPTZ(6) NOT NULL,
    captured_at              TIMESTAMPTZ(6) NOT NULL,
    capture_tolerance_seconds INTEGER       NOT NULL,
    capture_status           VARCHAR(20)    NOT NULL,
    short_exit_snapshot      JSONB          NOT NULL DEFAULT '{}'::jsonb,
    long_exit_snapshot       JSONB          NOT NULL DEFAULT '{}'::jsonb,
    funding_observations     JSONB          NOT NULL DEFAULT '[]'::jsonb,
    funding_watermarks       JSONB          NOT NULL DEFAULT '{}'::jsonb,
    data_issues              JSONB          NOT NULL DEFAULT '[]'::jsonb,
    formula_inputs_version   SMALLINT       NOT NULL DEFAULT 1,
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT xvf_signal_candidate_outcome_pk PRIMARY KEY (outcome_attempt_id),
    CONSTRAINT xvf_signal_candidate_outcome_candidate_fk
        FOREIGN KEY (signal_run_id, evaluation_order)
        REFERENCES xvf_signal_candidate (signal_run_id, evaluation_order)
        ON DELETE RESTRICT,
    CONSTRAINT xvf_signal_candidate_outcome_horizon_ck CHECK (horizon_hours > 0),
    CONSTRAINT xvf_signal_candidate_outcome_time_ck
        CHECK (captured_at >= capture_started_at),
    CONSTRAINT xvf_signal_candidate_outcome_tolerance_ck
        CHECK (capture_tolerance_seconds >= 0),
    CONSTRAINT xvf_signal_candidate_outcome_status_ck
        CHECK (capture_status IN ('COMPLETE', 'PARTIAL', 'FAILED')),
    CONSTRAINT xvf_signal_candidate_outcome_short_json_ck
        CHECK (jsonb_typeof(short_exit_snapshot) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_candidate_outcome_long_json_ck
        CHECK (jsonb_typeof(long_exit_snapshot) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_candidate_outcome_funding_json_ck
        CHECK (jsonb_typeof(funding_observations) IS NOT DISTINCT FROM 'array'),
    CONSTRAINT xvf_signal_candidate_outcome_watermarks_json_ck
        CHECK (jsonb_typeof(funding_watermarks) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_candidate_outcome_issues_json_ck
        CHECK (jsonb_typeof(data_issues) IS NOT DISTINCT FROM 'array'),
    CONSTRAINT xvf_signal_candidate_outcome_version_ck
        CHECK (formula_inputs_version > 0),
    CONSTRAINT xvf_signal_candidate_outcome_status_payload_ck CHECK (
        (capture_status = 'COMPLETE'
            AND short_exit_snapshot <> '{}'::jsonb
            AND long_exit_snapshot <> '{}'::jsonb)
        OR
        (capture_status IN ('PARTIAL', 'FAILED')
            AND jsonb_array_length(data_issues) > 0)
    )
);

CREATE UNIQUE INDEX xvf_signal_candidate_outcome_complete_uq
    ON xvf_signal_candidate_outcome (signal_run_id, evaluation_order, horizon_hours)
    WHERE capture_status = 'COMPLETE';

CREATE INDEX xvf_signal_candidate_outcome_target_idx
    ON xvf_signal_candidate_outcome (target_exit_utc, captured_at);

CREATE FUNCTION xvf_signal_candidate_outcome_validate()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    candidate_status VARCHAR(20);
    candidate_horizon INTEGER;
    decision_at TIMESTAMPTZ;
    expected_target TIMESTAMPTZ;
BEGIN
    SELECT c.score_status, c.planned_hold_hours, r.scheduled_decision_at
      INTO candidate_status, candidate_horizon, decision_at
      FROM xvf_signal_candidate c
      JOIN xvf_signal_run r ON r.signal_run_id = c.signal_run_id
     WHERE c.signal_run_id = NEW.signal_run_id
       AND c.evaluation_order = NEW.evaluation_order;

    IF candidate_status IS NULL OR candidate_status <> 'SCORABLE' THEN
        RAISE EXCEPTION 'XVF outcomes require a SCORABLE candidate'
            USING ERRCODE = '23514';
    END IF;

    expected_target := decision_at + make_interval(hours => candidate_horizon);
    IF NEW.horizon_hours <> candidate_horizon OR NEW.target_exit_utc <> expected_target THEN
        RAISE EXCEPTION
            'XVF outcome horizon/target must match the candidate plan (% hours, %)',
            candidate_horizon, expected_target
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER xvf_signal_candidate_outcome_validate_trg
    BEFORE INSERT ON xvf_signal_candidate_outcome
    FOR EACH ROW
    EXECUTE FUNCTION xvf_signal_candidate_outcome_validate();

CREATE TRIGGER xvf_signal_candidate_outcome_reject_update_delete_trg
    BEFORE UPDATE OR DELETE ON xvf_signal_candidate_outcome
    FOR EACH ROW
    EXECUTE FUNCTION xvf_signal_reject_mutation();

CREATE TRIGGER xvf_signal_candidate_outcome_reject_truncate_trg
    BEFORE TRUNCATE ON xvf_signal_candidate_outcome
    FOR EACH STATEMENT
    EXECUTE FUNCTION xvf_signal_reject_mutation();

COMMENT ON TABLE xvf_signal_candidate_outcome IS
    'Append-only public-market exit and settled-funding facts for an XVF candidate; never read by execution.';
