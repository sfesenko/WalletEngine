# WalletEngine
High-throughput Player Wallet Service prototype

## Docs
- [Task](docs/TASK.md) — the requirements this repo implements
- [Design](docs/DESIGN.md) — design notes
- [AWS](docs/AWS.md) — how to host on AWS (Bonus 2)


## Development
Docker Compose is a hard requirement, it's used as dev environment, will spin up automatically (Spring Boot integration).

Build and run application: `mvn spring-boot:run` - infra auto-starts

### Tests
Profile `fast-test` skips `slow`-tagged tests (`mvn test -Pfast-test`).

- `mvn test` — full suite against Docker Compose infra.
- `mvn test -Pfast-test` — fast loop; skips the `slow`-tagged stress tests.
- `mvn verify` — full build: Checkstyle, tests, SpotBugs.

The suite shares one Postgres/RabbitMQ stack between runs and tolerates leftover test
data. 
For clean-up fresh start may be required:
```shell
docker compose down -v
```

### Manual testing
`grpcurl` CLI tool may be used to access gRPC api.
See [docs/api-test.sh](docs/api-test.sh)

Available endpoints:
- gRPC API is `wallet.v1.WalletService` on `:9090` (proto: `src/main/proto/wallet/v1/wallet.proto`),
  - wallet.v1.WalletService/GetBalance
  - wallet.v1.WalletService/Credit
  - wallet.v1.WalletService/Debit
  - wallet.v1.WalletService/CreateWallet
- actuator/health on `http://localhost:8080/actuator/health`.

Seeded wallets (`V3__seed_test_data.sql`):

| wallet_id | balance |
|---|---|
| `00000000-0000-0000-0000-000000000001` | 100000 |
| `00000000-0000-0000-0000-000000000002` | 5000 |
| `00000000-0000-0000-0000-000000000003` | 25000 |

#### Request samples
```shell
grpcurl -plaintext -d '{"wallet_id": "00000000-0000-0000-0000-000000000001"}' localhost:9090 wallet.v1.WalletService/GetBalance

grpcurl -plaintext -d '{"wallet_id": "10000000-0000-0000-0000-000000000000"}' localhost:9090 wallet.v1.WalletService/CreateWallet
grpcurl -plaintext -d '{"transaction_id": "aaaaaaaa-0000-0000-0000-000000000002", "wallet_id": "10000000-0000-0000-0000-000000000000", "amount": 100}' localhost:9090 wallet.v1.WalletService/Credit
grpcurl -plaintext -d '{"transaction_id": "aaaaaaaa-0000-0000-0000-000000000003", "wallet_id": "10000000-0000-0000-0000-000000000000", "amount": 100}' localhost:9090 wallet.v1.WalletService/Debit
```

### RabbitMQ UI
Available at `http://localhost:15672` (`myuser`/`secret`)
exchange `wallet.events`, queue `wallet.updated` - see `WalletAmqpConfiguration.java`
See [docs/rabbitmq.sh](docs/rabbitmq.sh)

## Run with docker compose (Bonus 1)
To run application as service with docker compose, needs to build docker image first:
`mvn package -Ddocker.skip=false`
Then need to use compose `app` profile.

```shell
mvn package -Ddocker.skip=false -DskipTests   # build walletengine:local image
docker compose --profile app up               # run app with docker compose
```
It'll use [V3__seed_test_data.sql](src/test/resources/db/seed/V3__seed_test_data.sql) as test seed data

### Why it's not 1-shot-command
It's not self-sufficient solution where docker-compose.yml refer Dockerfile, and `compose up` command is enough to run whole application.
Also Dockerfile is not self-sufficient (not multi-stage, doesn't build image from sources) and required pre-build artifact (.jar)
  - PROS:
      - it package verified artifact, that has been already build and verified
      - provides control on what is build and released
      - allows to use docker-compose both for development and running (not prod grade, but ok for DEV/STAGE env)
  - CONS:
    - require to build .jar artifact and docker image separately

## Possible enhancements
Assuming the application is feature complete (no new requirements), possible enhancements lie in infrastructure, maintainability and testability:
- introduce Uber's NullAway: enables stricter null checking
  - CONS: may require wrapping third-party APIs (unnecessary otherwise)

- Spring Data JDBC migration - explicit SQL mapping, likely "cleaner" Java code
  - CONS: tradeoff between where application logic should belong: Java or PostgreSQL

- Move Flyway migrations into separate project - explicitly run migrations and update database
  - CONS: requires additional deployment step, more ceremony during development, more complexity in CI/CD
  - PROS: allows more careful DB schema management, DBA will like that

- Scalability improvements: allow multiple instances of PostgresSQL (sharding), Redis, RabbitMQ
  - CONS: much more complexity in infrastructure, need proper evaluation of scalability strategy

- More Performance vs some consistency: 
  - do not wait for some operations, like RabbitMQ event publish, wallet balance update - use "fire and forget" strategy
  - instead - check invariants and operation status later
    - validate wallet balance on app start
    - validate if RabbitMQ event was properly published after some time, then do "acknowledge" (clean up outbox, re-schedule message)
      - CONS: "failures" become more expensive, it's "I'm feeling lucky" strategy

- More Performance vs visibility: cut response to just ok/error status, provide more api to get info about operation status
  - PROS: allows to reduce DB reads and execute minimum DB operation per request
  - CONS: makes client to do more api calls:
    - call GetBalance to get wallet's balance after movement
    - call GetError to get info about why movement failed

- CI/CD: it missing currently, but is "must have" requirement for most projects:
  - use testcontainers for testing: will create fresh database for each test run, current docker-compose approach may breaks due to db schema changes
  - add more reporting tools, like SonarQube

## Performance
Here are numbers generated with  [docs/load-test.sh](docs/load-test.sh):

| Scenario                               | Ops     | Throughput   | p50    | p99    |
|----------------------------------------|---------|--------------|--------|--------|
| mixed credit/debit - 50 wallets x 200  | 10,000  | 1,260 ops/s  | 38 ms  | 86 ms  |
| credit - 100 wallets x 200             | 20,000  | 1,294 ops/s  | 72 ms  | 183 ms |
| mixed - 200 wallets x 100              | 20,000  | 1,334 ops/s  | 145 ms | 335 ms |
| read (GetBalance) - 50 wallets x 2000  | 100,000 | 8,795 ops/s  | 4.3 ms | 22 ms  |
| read (GetBalance) - 100 wallets x 2000 | 200,000 | 10,888 ops/s | 8.2 ms | 26 ms  |

Reproduce: 
```shell
# run application
./docs/load-test.sh --wallets 50 --ops 200  # (also: `--mode credit|debit|read`, `--target host:port`).
```
