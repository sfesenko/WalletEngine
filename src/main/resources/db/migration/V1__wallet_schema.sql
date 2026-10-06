create table wallets (
    wallet_id  uuid primary key,
    amount     bigint      not null default 0,
    updated_at timestamptz not null default now()
);

create table wallet_ledger (
    transaction_id uuid primary key,
    wallet_id      uuid        not null references wallets (wallet_id),
    amount         bigint      not null,
    updated_at     timestamptz not null default now()
);

create or replace function prevent_wallet_ledger_mutation()
    returns trigger
    language plpgsql
as $$
begin
    raise exception 'wallet_ledger is append-only: % is not allowed', tg_op
        using errcode = 'restrict_violation';
end;
$$;

create trigger trg_wallet_ledger_immutable
    before update or delete
    on wallet_ledger
    for each row
execute function prevent_wallet_ledger_mutation();
