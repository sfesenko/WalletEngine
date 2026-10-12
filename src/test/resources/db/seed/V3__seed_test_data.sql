-- Test data only, applied by the `test` profile (spring.flyway.locations in
-- application-test.properties). Lives on the test classpath, so it is never
-- packaged into the application jar and never runs against production.
insert into wallets (wallet_id, amount)
values ('00000000-0000-0000-0000-000000000001', 100000),
       ('00000000-0000-0000-0000-000000000002', 5000),
       ('00000000-0000-0000-0000-000000000003', 25000)
on conflict (wallet_id) do nothing;
