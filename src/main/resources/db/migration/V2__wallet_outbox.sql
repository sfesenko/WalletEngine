create table wallet_outbox (
    transaction_id uuid primary key,
    wallet_id      uuid        not null,
    amount         bigint      not null,
    seq            bigserial   not null,
    status         varchar     not null default 'PENDING',
    attempts       integer     not null default 0,
    last_error     text,
    created_at     timestamptz not null default now(),
    claimed_at     timestamptz
);

create unique index idx_wallet_outbox_seq on wallet_outbox (seq);
create index idx_wallet_outbox_status_seq on wallet_outbox (status, seq);
