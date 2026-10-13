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



## Run with docker compose (Bonus 1)
To run application as service with docker compose, needs to build docker image first:
`mvn package -Ddocker.skip=false`
Then need to use compose `app` profile.

```shell
mvn package -Ddocker.skip=false -DskipTests   # build walletengine:local image
docker compose --profile app up               # run app with docker compose
```

- it'll use [V3__seed_test_data.sql](src/test/resources/db/seed/V3__seed_test_data.sql) as test seed data

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

- Trade more consistency for performance: 
  - do not wait for some operations, like RabbitMQ event publish, wallet balance update - use "fire and forget" strategy
  - instead - check invariants and operation status later
    - validate wallet balance on app start
    - validate if RabbitMQ event was properly published after some time, then do "acknowledge" (clean up outbox, re-schedule message)
      - CONS: "failures" become more expensive, it's "I'm feeling lucky" strategy

- CI/CD: it missing currently, but is "must have" requirement for most projects:
  - use testcontainers for testing: will create fresh database for each test run, current docker-compose approach may breaks due to db schema changes
  - add more reporting tools, like SonarQube