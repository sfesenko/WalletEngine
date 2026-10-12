-- Test data only, applied by the `test` profile
insert into wallets (wallet_id)
values ('00000000-0000-0000-0000-000000000001'),
       ('00000000-0000-0000-0000-000000000002'),
       ('00000000-0000-0000-0000-000000000003')
on conflict (wallet_id) do nothing;

-- genesis transactions: the only place the seeded money amounts are defined
insert into wallet_ledger (transaction_id, wallet_id, amount)
values ('10000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000001', 100000),
       ('10000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000002', 5000),
       ('10000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000003', 25000)
on conflict (transaction_id) do nothing;

-- balances are derived from the ledger
update wallets w
   set amount = (select coalesce(sum(amount), 0) from wallet_ledger l where l.wallet_id = w.wallet_id);
