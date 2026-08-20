-- Tardis's monthly archives (RESEARCH_OPTIONS.md item 4, "Correction: historical quotes DO exist,
-- free") carry sizes and greeks the live recorder's schema never stored. Adding them now costs
-- nothing; not adding them means re-downloading and re-importing 24GB of archives later if a
-- question turns out to need them - the same "cannot be fetched later" reasoning V10 already applies
-- to the quotes themselves applies to these columns too.
--
-- All nullable: the live recorder's existing INSERT lists its 15 columns by name and will keep
-- working unchanged, leaving these NULL on its rows.
ALTER TABLE deribit_option_quote
    ADD COLUMN bid_amount NUMERIC(30,8),
    ADD COLUMN ask_amount NUMERIC(30,8),
    ADD COLUMN bid_iv     NUMERIC(12,4),
    ADD COLUMN ask_iv     NUMERIC(12,4),
    ADD COLUMN delta      NUMERIC(12,6),
    ADD COLUMN gamma      NUMERIC(14,8),
    ADD COLUMN vega       NUMERIC(14,8),
    ADD COLUMN theta      NUMERIC(14,8),
    ADD COLUMN rho        NUMERIC(14,8);
