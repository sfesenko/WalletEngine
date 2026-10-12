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

-- Core integrity guardrails: balances stay non-negative, ledger rows always move money.
alter table wallets
    add constraint wallets_amount_nonnegative check (amount >= 0);

alter table wallet_ledger
    add constraint wallet_ledger_amount_nonzero check (amount <> 0);

-- Strict idempotency (wallet.idempotency.strict=true): state-dependent movement rejections,
-- replayed on duplicate requests instead of re-executing the movement.
-- No FK on wallet_id: WALLET_NOT_FOUND rejections reference wallets that do not exist.
create table rejected_movements (
    transaction_id uuid primary key,
    wallet_id      uuid        not null,
    amount         bigint      not null,
    error_code     varchar     not null,
    created_at     timestamptz not null default now()
);

-- Lookups by wallet (support / debugging) without scanning the whole log.
create index if not exists idx_wallet_ledger_wallet_id
    on wallet_ledger (wallet_id);
