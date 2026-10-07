-- Cursor of the background event publisher: where it stopped reading the transactions
-- log. Single row; mirrored in Redis, this row is the durable copy used on restart.
--
-- Numbered V3 so it does not clash with the test-only seed (V2), which lives in a
-- separate location and is applied only by the `test` profile.
create table publisher_state (
    id                  integer primary key,
    last_processed_at   timestamptz,
    last_transaction_id uuid,
    constraint publisher_state_single_row check (id = 1)
);

insert into publisher_state (id) values (1);
