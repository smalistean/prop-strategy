-- Immutable XVF decision-time audit ledger.
--
-- This schema is deliberately separate from execution. A report-only shadow process will build a
-- complete snapshot in memory and insert it here without changing the book that production trades.
-- COMPLETE/PARTIAL runs and every captured candidate are committed atomically. A FAILED attempt is
-- inserted separately after the failed snapshot transaction rolls back and may not have candidates.

CREATE TABLE xvf_signal_run (
    signal_run_id                 UUID           NOT NULL,
    snapshot_schema_version       SMALLINT       NOT NULL DEFAULT 1,
    cutoff_utc                    TIMESTAMPTZ(6) NOT NULL,
    production_date               DATE           NOT NULL,
    production_zone               VARCHAR(64)    NOT NULL,
    generated_at                  TIMESTAMPTZ(6) NOT NULL,
    code_revision                 VARCHAR(80)    NOT NULL,
    strategy_version              VARCHAR(80)    NOT NULL,
    configuration_hash            VARCHAR(64)    NOT NULL,
    configuration_snapshot        JSONB          NOT NULL,
    settled_funding_watermarks     JSONB          NOT NULL,
    pending_funding_watermarks     JSONB          NOT NULL,
    venue_state_snapshot           JSONB          NOT NULL,
    capital_usd                    NUMERIC(30,12),
    candidate_count                INTEGER        NOT NULL,
    data_issues                    JSONB          NOT NULL DEFAULT '[]'::jsonb,
    capture_status                 VARCHAR(20)    NOT NULL,
    failure_code                   VARCHAR(80),
    failure_detail                 TEXT,
    created_at                     TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT xvf_signal_run_pk
        PRIMARY KEY (signal_run_id),
    CONSTRAINT xvf_signal_run_schema_version_ck
        CHECK (snapshot_schema_version > 0),
    CONSTRAINT xvf_signal_run_time_order_ck
        CHECK (generated_at >= cutoff_utc),
    CONSTRAINT xvf_signal_run_production_date_ck
        CHECK (production_date = timezone(production_zone, cutoff_utc)::date),
    CONSTRAINT xvf_signal_run_production_zone_ck
        CHECK (btrim(production_zone) <> ''),
    CONSTRAINT xvf_signal_run_code_revision_ck
        CHECK (btrim(code_revision) <> ''),
    CONSTRAINT xvf_signal_run_strategy_version_ck
        CHECK (btrim(strategy_version) <> ''),
    CONSTRAINT xvf_signal_run_configuration_hash_ck
        CHECK (configuration_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT xvf_signal_run_configuration_json_ck
        CHECK (jsonb_typeof(configuration_snapshot) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_run_settled_watermarks_json_ck
        CHECK (jsonb_typeof(settled_funding_watermarks) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_run_pending_watermarks_json_ck
        CHECK (jsonb_typeof(pending_funding_watermarks) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_run_venue_state_json_ck
        CHECK (jsonb_typeof(venue_state_snapshot) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_run_data_issues_json_ck
        CHECK (jsonb_typeof(data_issues) IS NOT DISTINCT FROM 'array'),
    CONSTRAINT xvf_signal_run_capital_ck
        CHECK (capital_usd IS NULL OR capital_usd > 0),
    CONSTRAINT xvf_signal_run_candidate_count_ck
        CHECK (candidate_count >= 0),
    CONSTRAINT xvf_signal_run_status_ck
        CHECK (capture_status IN ('COMPLETE', 'PARTIAL', 'FAILED')),
    CONSTRAINT xvf_signal_run_status_payload_ck
        CHECK (
            (
                capture_status = 'COMPLETE'
                AND capital_usd IS NOT NULL
                AND failure_code IS NULL
                AND failure_detail IS NULL
            )
            OR
            (
                capture_status = 'PARTIAL'
                AND capital_usd IS NOT NULL
                AND failure_code IS NOT NULL
                AND btrim(failure_code) <> ''
                AND jsonb_array_length(data_issues) > 0
            )
            OR
            (
                capture_status = 'FAILED'
                AND failure_code IS NOT NULL
                AND btrim(failure_code) <> ''
                AND jsonb_array_length(data_issues) > 0
                AND candidate_count = 0
            )
        )
);

CREATE INDEX xvf_signal_run_cutoff_idx
    ON xvf_signal_run (cutoff_utc DESC, generated_at DESC);

CREATE INDEX xvf_signal_run_incomplete_idx
    ON xvf_signal_run (generated_at DESC)
    WHERE capture_status <> 'COMPLETE';


CREATE TABLE xvf_signal_candidate (
    signal_run_id                       UUID           NOT NULL,
    evaluation_order                    INTEGER        NOT NULL,
    gross_rank                          INTEGER        NOT NULL,
    base                                VARCHAR(30)    NOT NULL,
    pair_type                           VARCHAR(20)    NOT NULL,
    short_venue                         VARCHAR(20)    NOT NULL,
    short_venue_symbol                  VARCHAR(120)   NOT NULL,
    long_venue                          VARCHAR(20)    NOT NULL,
    long_venue_symbol                   VARCHAR(120)   NOT NULL,
    baseline_book_rank                  INTEGER,
    shadow_book_rank                    INTEGER,
    raw_spread_annual_pct               NUMERIC(20,8)  NOT NULL,
    eligible_yesterday                  BOOLEAN        NOT NULL,
    stale_discount_factor               NUMERIC(20,12) NOT NULL,
    adjusted_spread_annual_pct          NUMERIC(20,8)  NOT NULL,
    pending_funding_fresh               BOOLEAN,
    thin_leg_weekly_quote_volume_usd    NUMERIC(38,12),
    maker_venue                         VARCHAR(20)    NOT NULL,
    taker_venue                         VARCHAR(20)    NOT NULL,
    planned_hold_hours                  INTEGER        NOT NULL,
    pending_funding_spread_bps          NUMERIC(20,8),
    entry_basis_bps                     NUMERIC(20,8),
    expected_funding_bps                NUMERIC(20,8),
    expected_basis_pnl_bps              NUMERIC(20,8),
    expected_entry_fee_bps              NUMERIC(20,8),
    expected_exit_fee_bps               NUMERIC(20,8),
    expected_slippage_bps               NUMERIC(20,8),
    risk_penalty_bps                     NUMERIC(20,8),
    expected_net_bps                    NUMERIC(20,8),
    requested_leg_notional_usd          NUMERIC(30,12),
    short_leg_snapshot                  JSONB          NOT NULL,
    long_leg_snapshot                   JSONB          NOT NULL,
    score_components                    JSONB          NOT NULL,
    gate_results                        JSONB          NOT NULL,
    score_status                        VARCHAR(20)    NOT NULL,
    decision_reasons                    JSONB          NOT NULL,
    created_at                          TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT xvf_signal_candidate_pk
        PRIMARY KEY (signal_run_id, evaluation_order),
    CONSTRAINT xvf_signal_candidate_run_fk
        FOREIGN KEY (signal_run_id)
        REFERENCES xvf_signal_run (signal_run_id)
        ON DELETE RESTRICT,
    CONSTRAINT xvf_signal_candidate_identity_uq
        UNIQUE (
            signal_run_id,
            base,
            short_venue,
            short_venue_symbol,
            long_venue,
            long_venue_symbol
        ),
    CONSTRAINT xvf_signal_candidate_gross_rank_uq
        UNIQUE (signal_run_id, gross_rank),
    CONSTRAINT xvf_signal_candidate_evaluation_order_ck
        CHECK (evaluation_order > 0),
    CONSTRAINT xvf_signal_candidate_gross_rank_ck
        CHECK (gross_rank > 0),
    CONSTRAINT xvf_signal_candidate_base_ck
        CHECK (btrim(base) <> ''),
    CONSTRAINT xvf_signal_candidate_venues_ck
        CHECK (
            btrim(short_venue) <> ''
            AND btrim(long_venue) <> ''
            AND short_venue <> long_venue
        ),
    CONSTRAINT xvf_signal_candidate_symbols_ck
        CHECK (
            btrim(short_venue_symbol) <> ''
            AND btrim(long_venue_symbol) <> ''
        ),
    CONSTRAINT xvf_signal_candidate_pair_type_ck
        CHECK (pair_type IN ('CEX_DEX', 'CEX_CEX', 'DEX_DEX')),
    CONSTRAINT xvf_signal_candidate_baseline_rank_ck
        CHECK (baseline_book_rank IS NULL OR baseline_book_rank > 0),
    CONSTRAINT xvf_signal_candidate_shadow_rank_ck
        CHECK (shadow_book_rank IS NULL OR shadow_book_rank > 0),
    CONSTRAINT xvf_signal_candidate_spreads_ck
        CHECK (
            raw_spread_annual_pct >= 0
            AND adjusted_spread_annual_pct >= 0
            AND stale_discount_factor > 0
            AND stale_discount_factor <= 1
        ),
    CONSTRAINT xvf_signal_candidate_weekly_volume_ck
        CHECK (
            thin_leg_weekly_quote_volume_usd IS NULL
            OR thin_leg_weekly_quote_volume_usd >= 0
        ),
    CONSTRAINT xvf_signal_candidate_route_ck
        CHECK (
            maker_venue <> taker_venue
            AND maker_venue IN (short_venue, long_venue)
            AND taker_venue IN (short_venue, long_venue)
        ),
    CONSTRAINT xvf_signal_candidate_hold_ck
        CHECK (planned_hold_hours > 0),
    CONSTRAINT xvf_signal_candidate_costs_ck
        CHECK (
            (expected_entry_fee_bps IS NULL OR expected_entry_fee_bps >= 0)
            AND (expected_exit_fee_bps IS NULL OR expected_exit_fee_bps >= 0)
            AND (expected_slippage_bps IS NULL OR expected_slippage_bps >= 0)
            AND (risk_penalty_bps IS NULL OR risk_penalty_bps >= 0)
        ),
    CONSTRAINT xvf_signal_candidate_notional_ck
        CHECK (
            requested_leg_notional_usd IS NULL
            OR requested_leg_notional_usd > 0
        ),
    CONSTRAINT xvf_signal_candidate_short_snapshot_json_ck
        CHECK (jsonb_typeof(short_leg_snapshot) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_candidate_long_snapshot_json_ck
        CHECK (jsonb_typeof(long_leg_snapshot) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_candidate_score_components_json_ck
        CHECK (jsonb_typeof(score_components) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_candidate_gate_results_json_ck
        CHECK (jsonb_typeof(gate_results) IS NOT DISTINCT FROM 'object'),
    CONSTRAINT xvf_signal_candidate_score_status_ck
        CHECK (score_status IN ('SCORABLE', 'UNSCORABLE')),
    CONSTRAINT xvf_signal_candidate_score_payload_ck
        CHECK (
            (
                score_status = 'SCORABLE'
                AND pending_funding_fresh IS TRUE
                AND thin_leg_weekly_quote_volume_usd IS NOT NULL
                AND pending_funding_spread_bps IS NOT NULL
                AND entry_basis_bps IS NOT NULL
                AND expected_funding_bps IS NOT NULL
                AND expected_basis_pnl_bps IS NOT NULL
                AND expected_entry_fee_bps IS NOT NULL
                AND expected_exit_fee_bps IS NOT NULL
                AND expected_slippage_bps IS NOT NULL
                AND risk_penalty_bps IS NOT NULL
                AND expected_net_bps IS NOT NULL
                AND requested_leg_notional_usd IS NOT NULL
            )
            OR
            (
                score_status = 'UNSCORABLE'
                AND expected_net_bps IS NULL
                AND shadow_book_rank IS NULL
                AND jsonb_array_length(decision_reasons) > 0
            )
        ),
    CONSTRAINT xvf_signal_candidate_decision_reasons_json_ck
        CHECK (jsonb_typeof(decision_reasons) IS NOT DISTINCT FROM 'array')
);

CREATE INDEX xvf_signal_candidate_base_idx
    ON xvf_signal_candidate (base, signal_run_id);

CREATE UNIQUE INDEX xvf_signal_candidate_baseline_rank_uq
    ON xvf_signal_candidate (signal_run_id, baseline_book_rank)
    WHERE baseline_book_rank IS NOT NULL;

CREATE UNIQUE INDEX xvf_signal_candidate_shadow_rank_uq
    ON xvf_signal_candidate (signal_run_id, shadow_book_rank)
    WHERE shadow_book_rank IS NOT NULL;


-- Failed attempts record the outage but can never be made to look like a usable decision later.
CREATE FUNCTION xvf_signal_candidate_require_captured_run()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    run_status VARCHAR(20);
    expected_count INTEGER;
BEGIN
    SELECT capture_status, candidate_count
      INTO run_status, expected_count
      FROM xvf_signal_run
     WHERE signal_run_id = NEW.signal_run_id;

    IF run_status IS NULL OR run_status NOT IN ('COMPLETE', 'PARTIAL') THEN
        RAISE EXCEPTION
            'xvf_signal_candidate requires COMPLETE or PARTIAL run %',
            NEW.signal_run_id
            USING ERRCODE = '23514';
    END IF;

    IF NEW.evaluation_order > expected_count OR NEW.gross_rank > expected_count THEN
        RAISE EXCEPTION
            'xvf signal run % declares % candidates but received evaluation order % / gross rank %',
            NEW.signal_run_id,
            expected_count,
            NEW.evaluation_order,
            NEW.gross_rank
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE TRIGGER xvf_signal_candidate_require_captured_run_trg
    BEFORE INSERT ON xvf_signal_candidate
    FOR EACH ROW
    EXECUTE FUNCTION xvf_signal_candidate_require_captured_run();


-- Seals child membership. Evaluation order and gross rank are unique positive integers bounded by
-- candidate_count, so N committed rows must fill exactly 1..N and no later insert can fit. This one
-- deferred parent check runs at commit, after the initial batch is complete, and verifies N rows
-- actually arrived.
CREATE FUNCTION xvf_signal_assert_candidate_count()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
DECLARE
    run_id UUID;
    expected_count INTEGER;
    actual_count BIGINT;
BEGIN
    run_id := NEW.signal_run_id;

    SELECT candidate_count
      INTO expected_count
      FROM xvf_signal_run
     WHERE signal_run_id = run_id;

    SELECT count(*)
      INTO actual_count
      FROM xvf_signal_candidate
     WHERE signal_run_id = run_id;

    IF expected_count IS NULL OR actual_count <> expected_count THEN
        RAISE EXCEPTION
            'xvf signal run % declares % candidates but has %',
            run_id,
            expected_count,
            actual_count
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$function$;

CREATE CONSTRAINT TRIGGER xvf_signal_run_candidate_count_trg
    AFTER INSERT ON xvf_signal_run
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION xvf_signal_assert_candidate_count();


CREATE FUNCTION xvf_signal_reject_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $function$
BEGIN
    RAISE EXCEPTION
        '% is append-only: % is forbidden',
        TG_TABLE_NAME,
        TG_OP
        USING ERRCODE = '55000';

    RETURN NULL;
END;
$function$;

CREATE TRIGGER xvf_signal_run_reject_update_delete_trg
    BEFORE UPDATE OR DELETE ON xvf_signal_run
    FOR EACH ROW
    EXECUTE FUNCTION xvf_signal_reject_mutation();

CREATE TRIGGER xvf_signal_run_reject_truncate_trg
    BEFORE TRUNCATE ON xvf_signal_run
    FOR EACH STATEMENT
    EXECUTE FUNCTION xvf_signal_reject_mutation();

CREATE TRIGGER xvf_signal_candidate_reject_update_delete_trg
    BEFORE UPDATE OR DELETE ON xvf_signal_candidate
    FOR EACH ROW
    EXECUTE FUNCTION xvf_signal_reject_mutation();

CREATE TRIGGER xvf_signal_candidate_reject_truncate_trg
    BEFORE TRUNCATE ON xvf_signal_candidate
    FOR EACH STATEMENT
    EXECUTE FUNCTION xvf_signal_reject_mutation();

COMMENT ON TABLE xvf_signal_run IS
    'Immutable XVF decision-time capture attempts; never read by live execution.';

COMMENT ON TABLE xvf_signal_candidate IS
    'Immutable snapshot of every evaluated cross-venue XVF candidate.';
