# Elevation of Privilege - EoP

A threat modeling card game based on the STRIDE framework (Spoofing, Tampering, Repudiation, Information Disclosure, Denial of Service, Elevation of Privilege).

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4.1.0 |
| Build | Maven Wrapper (`./mvnw`) |
| Tests | JUnit 5 |
| Front End | React + TypeScript + Vite, GOV.UK Design System (`ui/`) ([ADR-009](docs/adr/ADR-009-frontend-react-typescript.md)) |
| Serving | Caddy reverse proxy — one origin for the site and the API ([ADR-017](docs/adr/ADR-017-frontend-delivery-topology.md)) |
| CI | GitHub Actions — `./mvnw verify`, front-end gates, then build, smoke test and publish both images to GHCR; a Playwright end-to-end job then drives those images after the merge, never on the PR ([ADR-072](docs/adr/ADR-072-e2e-ci-integration.md)) |
| Container | Multi-stage `Dockerfile` and `ui/Dockerfile`, `compose.app.yml` with PostgreSQL |
| Infrastructure | Terraform (`infra/`) — single EC2 instance, not yet applied ([ADR-012](docs/adr/ADR-012-deployment-target.md)) |
| AI Agents | OpenCode multi-agent system |

## Quick Start

### The whole stack, the way it is deployed

```bash
colima start                                  # see ADR-016
docker build -t eop-threat-modeling:local .
docker build -t eop-ui:local ui
docker compose -f compose.app.yml up -d
open https://localhost/
```

Caddy is the only published port. The application container publishes nothing —
`https://localhost/api/v1/cards` and `https://localhost/health` are proxied to it
on the same origin, which is why there is no CORS configuration anywhere
([ADR-017](docs/adr/ADR-017-frontend-delivery-topology.md)).

### Just the back end, for development

```bash
./mvnw verify                                 # tests and quality gates
./mvnw spring-boot:run                        # H2 in memory, default profile
curl http://localhost:8080/health
# → OK
```

### Just the front end, for development

```bash
cd ui
npm install
npm run dev                                   # proxies /api to localhost:8080
npm run verify                                # typecheck, lint, test, build
```

## Project Structure

```
src/main/java/org/maglez/
├── Main.java                        # Spring Boot entry point + /health endpoint
└── eop/
    ├── entity/                      # Domain. Zero Spring, zero Jakarta imports.
    │   ├── Card.java                #   Immutable threat card
    │   ├── CardNotFoundException.java
    │   ├── Rank.java                #   Two through ace, ace high
    │   └── StrideCategory.java       #   The six suits; declaration order is load bearing
    ├── usecase/                     # Application. Ports and use cases, no framework.
    │   ├── CardRepository.java       #   Port, implemented outward
    │   ├── GetCardUseCase.java
    │   ├── ListCardsUseCase.java
    │   ├── PageQuery.java
    │   └── PageResult.java
    ├── adapter/
    │   ├── persistence/             # JPA lives here and nowhere else
    │   │   ├── CardJpaEntity.java
    │   │   ├── CardJpaRepository.java
    │   │   └── CardRepositoryAdapter.java
    │   └── web/                     # HTTP lives here and nowhere else
    │       ├── CardController.java
    │       ├── CardDto.java
    │       ├── GlobalExceptionHandler.java   # RFC 9457, one handler for the whole API
    │       └── PagedResponse.java
    └── config/
        └── UseCaseConfiguration.java # Bean definitions, so use cases stay framework-free

src/main/resources/db/changelog/      # Liquibase. Changesets are immutable once merged.
docs/api/openapi.yml                  # The API contract. Hand authored, ahead of the code.

ui/                                   # Front end. Its own build, its own image, its own CI job.
├── src/
│   ├── api.ts                        #   Typed client. Every path relative — see ADR-017.
│   ├── App.tsx                       #   The one page: shell plus the card catalogue
│   ├── App.test.tsx
│   └── main.tsx
├── scripts/copy-govuk-assets.mjs     #   Design System fonts and images into public/
├── Caddyfile                          #   Serves the site, proxies /api and /health
└── Dockerfile                         #   Node build → Caddy runtime, unprivileged
```

The layering is not decoration. `entity` and `usecase` compile without Spring or
Jakarta on the classpath; everything framework-shaped is under `adapter` or
`config`. A dependency pointing the other way is a review failure, not a style
preference — see [`.opencode/rules/clean-architecture.md`](.opencode/rules/clean-architecture.md).

## Documentation

- [Product Requirements](docs/requirements/PRD-eop-card-game.md) — what the game is, and what it is not
- [Architecture Blueprint](.opencode/docs/OpenCode_Autonomous_Engineering_System_Blueprint.md)
- [ADRs](docs/adr/README.md) — indexed, with implementation status
- [DevOps Guide](docs/devops/local-development.md)
- [CI/CD Pipeline](docs/devops/ci-cd-pipeline.md)

## Agent System

This project uses an OpenCode multi-agent team. See [AGENTS.md](AGENTS.md) for details and [Setup Guide](docs/devops/local-development.md) for prerequisites.
