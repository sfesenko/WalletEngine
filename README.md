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



## Run with docker compose (Bonus 1)
To run application as service with docker compose, needs to build docker image first:
`mvn package -Ddocker.skip=false`
Then need to use compose `app` profile.

```shell
mvn package -Ddocker.skip=false          # build walletengine:local image when needed
docker compose --profile app up          # run app with docker compose
```

- it'll use [V3__seed_test_data.sql](src/test/resources/db/seed/V3__seed_test_data.sql) as test seed data
