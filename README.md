# WalletEngine

- Requirements: Docker Compose (the dev environment spins it up automatically for infra).
- Run: `mvn spring-boot:run` (infra auto-starts) — or the full stack with seed data via Docker Compose:

```shell
mvn clean package                 # build application
./scripts/build-image.sh          # build docker image, require application in targets/
docker compose --profile app up   # run app with docker compose 
```

## Tests

- `mvn test` — full suite  against Docker Compose infra.
- `mvn test -Pfast-test` — fast loop; skips the `slow`-tagged stress tests.

## Docs

- [Task](docs/TASK.md) — the requirements this repo implements
- [Design](docs/DESIGN.md) — design notes
- [AWS](docs/AWS.md) — how to host on AWS (Bonus 2)
