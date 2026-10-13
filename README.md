# WalletEngine

- Requirements: Docker Compose (the dev environment spins it up automatically for infra).
- Run: `mvn spring-boot:run` (infra auto-starts) — or the full stack with seed data via Docker Compose:

```shell
mvn clean package                        # fast, no image (docker skipped by default)
mvn package -Ddocker.skip=false          # build walletengine:local image when needed
docker compose --profile app up          # run app with docker compose 
```

## Tests

- `mvn test` — full suite  against Docker Compose infra.
- `mvn test -Pfast-test` — fast loop; skips the `slow`-tagged stress tests.

## Maven profiles

- `fast-test` — skips `slow`-tagged tests (`mvn test -Pfast-test`). This is currently the only profile defined in `pom.xml`.
- Note: the Docker image build is not a profile but an opt-in property: `mvn package -Ddocker.skip=false` (skipped by default).

## Docs

- [Task](docs/TASK.md) — the requirements this repo implements
- [Design](docs/DESIGN.md) — design notes
- [AWS](docs/AWS.md) — how to host on AWS (Bonus 2)
