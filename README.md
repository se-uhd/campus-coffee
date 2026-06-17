# CampusCoffee

A Spring Boot teaching application for managing points of sale (POS) — cafés, bakeries, vending machines, and the like — on campus, with users and reviews. It follows a hexagonal (ports-and-adapters) architecture enforced by ArchUnit, built with Gradle (Kotlin DSL) on Java 25.

## Scope: no authentication (yet)

CampusCoffee is a teaching application and currently has no authentication or authorization. User
identity is client-asserted: `POST /api/reviews` takes the author id in the request body, and
`PUT /api/reviews/{id}/approve?user_id=...` takes the approver id as a query parameter. Every endpoint is
open, including the destructive ones, and `GET /api/users` returns all users with their email addresses.
Authentication and authorization will be added in a later iteration of the course material.

Until then, do not run the application on a publicly reachable host with real data. If you deploy the
demo (see [Deployment](#deployment)), restrict access — e.g., deploy to Cloud Run with
`--no-allow-unauthenticated` — or treat the deployment as throwaway.

The approval workflow inherits this gap: approvals are anonymous counts, so the same user can approve
a review repeatedly (see [Approve reviews](#approve-reviews)). The rework, tracking approvers per user, only becomes meaningful once identity is trustworthy. It is
therefore deferred to the same iteration and marked as `TODO (Exercise 5)` in `ReviewServiceImpl`.

## Prerequisites

* Install [Docker Desktop](https://www.docker.com/products/docker-desktop/) or a compatible open-source alternative such as [Rancher Desktop](https://rancherdesktop.io/).
* Install the [Temurin JDK 25](https://adoptium.net/temurin/releases/?version=25&os=any&arch=any) and [Gradle 9.5](https://gradle.org/install/) either via the provided [`mise.toml`](mise.toml) file (see [getting started guide](https://mise.jdx.dev/getting-started.html) for details) or directly via your favorite package manager. If you use `mise`, run `mise trust mise.toml` and then `mise install` in the project root to set up the required tool versions. There is no Gradle wrapper; run Gradle through `mise`.
* Install an IDE with Kotlin support. We recommend [IntelliJ](https://www.jetbrains.com/idea/), but you are free to use alternatives such as [VS Code](https://code.visualstudio.com/) with suitable extensions.
* Import the project into your IDE. In IntelliJ, you can do this via `File` -> `Open` and selecting the project's root folder (the multi-module build is defined by the root-level `settings.gradle.kts`; there is no root `build.gradle.kts` because the shared build logic lives in `build-logic/`). If you have the `mise` [plugin](https://plugins.jetbrains.com/plugin/24904-mise) installed, IntelliJ will ask you to select the appropriate tool versions.
* Ensure that your IDE as initialized the project correctly, including all `src`, `test`, and `resources` folders.

## Build application

First, make sure that the Docker daemon is running (the tests use Testcontainers).
Then, to build the application, run the following command in the command line (or use the Gradle integration of your IDE):

```shell
gradle build
```
**Note:** The application does not load any data on startup. In the `dev` profile you can load or reset it on demand — see [Dev endpoints](#dev-endpoints-apidev).

You can use the quiet mode to suppress most log messages:

```shell
gradle build -q
```

The Kotlin code is formatted and linted with [ktlint](https://pinterest.github.io/ktlint/). `gradle build`
fails on formatting violations (the `ktlintCheck` task runs as part of `check`); apply the fixes with:

```shell
gradle ktlintFormat
```

Static analysis runs with [detekt](https://detekt.dev/), also wired into `check`, so `gradle build`
fails on findings too. A per-module `detekt-baseline.xml` grandfathers the existing findings (regenerate
it with `gradle detektBaseline`).

## Start application

First, make sure that the Docker daemon is running.
Before you start the application, you first need to start a Postgres docker container:

```shell
docker run -d --name db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:17-alpine
```

Then, you can start the application in the `dev` profile for local development:

```shell
gradle :application:bootRun --args='--spring.profiles.active=dev'
```
**Note:** The data source is configured via the [`application.yaml`](application/src/main/resources/application.yaml) file.

The application starts with an empty database. Load the fixture dataset so the examples below
have data to work with — see [Dev endpoints](#dev-endpoints-apidev).

## Explore the REST API

### OpenAPI specification

After starting the application in the `dev` profile, you can access the OpenAPI specification (JSON) at [`http://localhost:8080/api/api-docs`](http://localhost:8080/api/api-docs).<br/>
You can also access the Swagger UI to interactively explore the API at [`http://localhost:8080/api/swagger-ui.html`](http://localhost:8080/api/swagger-ui.html).

### Local testing

You can use `curl` in the command line to send HTTP requests to the REST API.

#### Dev endpoints (/api/dev)

The application does not load any data on startup (in any profile), and the database persists across
application restarts. In the `dev` profile, three endpoints let you inspect, replace, and clear the
data on demand (they are not registered outside `dev`):

```shell
# report the current counts ({users, pos, reviews})
curl http://localhost:8080/api/dev/data

# replace the data with the fixture dataset (idempotent: clears first, safe to repeat)
curl --request PUT http://localhost:8080/api/dev/data

# clear all data
curl --request DELETE http://localhost:8080/api/dev/data
```

The fixture dataset includes four users with known passwords and cumulative role sets (an admin is also a
moderator and a user). Passwords are stored only as hashes and are never returned in any response.

| Login name      | Password               | Roles                        |
| --------------- | ---------------------- | ---------------------------- |
| `jane_doe`      | `aaaMbnPdFYDqkOpS3fVA` | `USER`, `MODERATOR`, `ADMIN` |
| `maxmustermann` | `AmLtoD3r8lVdnwoLN1Nn` | `USER`, `MODERATOR`          |
| `student2023`   | `ZwTwB8Hn8VkNLZec7bR1` | `USER`                       |
| `lisa_lee`      | `lG6v9dGKZA5kfOHTFLNR` | `USER`                       |

The fixture data also records `review_approvals` rows consistent with each review's approval count, so no
review has a non-zero count without matching approver rows. Security is currently permissive
(every endpoint is open); enforcing authentication and authorization is the subject of the assignment.

#### POS endpoints (/api/pos)

**Get POS:**

All POS:
```shell
curl http://localhost:8080/api/pos
```

POS by ID:
```shell
curl http://localhost:8080/api/pos/1 # add valid POS id here
```

POS by name:
```shell
curl 'http://localhost:8080/api/pos/filter?name=Schmelzpunkt' # add valid POS name here; quote the URL so zsh does not glob the ?
```

##### Create POS

Create a POS based on a JSON object provided in the request body:

```shell
curl --request POST --header "Content-Type: application/json" --data '{"name":"New Café","description":"Description","type":"CAFE","campus":"ALTSTADT","street":"Hauptstraße","houseNumber":"100","postalCode":"69117","city":"Heidelberg"}' http://localhost:8080/api/pos
```

Create a POS based on an OpenStreetMap node:

```shell
curl --request POST 'http://localhost:8080/api/pos/import/osm/5589879349?campus_type=ALTSTADT' # set a valid OSM node ID here
```

IDs for testing:
* 5589879349 (Rada Coffee & Rösterei in ALTSTADT)
* 1864600258 (La Fée in ALTSTADT)
* 1864600236 (Café Moro in ALTSTADT) --> missing address

See bean validation in action:

```shell
curl --header "Content-Type: application/json" --request POST -i --data '{"name":"","description":"","type":"CAFE","campus":"ALTSTADT","street":"Hauptstraße","houseNumber":"100","postalCode":"69117","city":"Heidelberg"}' http://localhost:8080/api/pos
```

##### Update POS

Update title and description:
```shell
curl --header "Content-Type: application/json" --request PUT --data '{"id":4,"name":"New coffee","description":"Great croissants","type":"CAFE","campus":"ALTSTADT","street":"Hauptstraße","houseNumber":"95","postalCode":"69117","city":"Heidelberg"}' http://localhost:8080/api/pos/4 # set correct POS id here and in the body
```

##### Delete POS

Delete POS by ID:
```shell
curl --request DELETE -i http://localhost:8080/api/pos/1 # set existing POS ID here
```

**Note:** A POS that still has reviews cannot be deleted; the API answers `409 Conflict`. With the
fixture data, POS 1 has reviews. Delete its reviews first or pick a POS without reviews (e.g., 3).

#### Users endpoints (/api/users)

##### Get users

All users:
```shell
curl http://localhost:8080/api/users
```

User by ID:
```shell
curl http://localhost:8080/api/users/1 # add valid user id here
```

User by login name:
```shell
curl 'http://localhost:8080/api/users/filter?login_name=jane_doe' # add valid user login name here
```

##### Create users

```shell
curl --header "Content-Type: application/json" --request POST --data '{"loginName":"other_login_name","emailAddress":"other.person@uni-heidelberg.de","firstName":"New","lastName":"Person"}' http://localhost:8080/api/users
```

See bean validation in action:
```shell
curl --header "Content-Type: application/json" --request POST -i --data '{"loginName":"other_login_name!","emailAddress":"other.personATuni-heidelberg.de","firstName":"","lastName":""}' http://localhost:8080/api/users
```

##### Update user

Update the login name and the email address:
```shell
curl --header "Content-Type: application/json" --request PUT --data '{"id":1,"createdAt":"2025-06-03T12:00:00","updatedAt":"2025-06-03T12:00:00","loginName":"jane_doe_new","emailAddress":"jane.doe.new@uni-heidelberg.de","firstName":"Jane","lastName":"Doe"}' http://localhost:8080/api/users/1 # set correct user id here and in the body
```

##### Delete user

Delete user by ID:
```shell
curl --request DELETE -i http://localhost:8080/api/users/1 # set existing user ID here
```

**Note:** A user who still has reviews cannot be deleted; the API answers `409 Conflict`. With the
fixture data, users 1-3 have authored reviews. Delete their reviews first or create a fresh user.

#### Reviews endpoint (/api/reviews)

##### Get reviews

All reviews:
```shell
curl http://localhost:8080/api/reviews
```

Review by ID:
```shell
curl http://localhost:8080/api/reviews/1 # add valid user id here
```

Get approved reviews for a POS:
```shell
curl 'http://localhost:8080/api/reviews/filter?pos_id=1&approved=true' # add valid POS id here; quote the URL so the shell does not treat & as a job control operator
```

##### Create reviews

```shell
curl --header "Content-Type: application/json" --request POST --data '{"posId":2,"authorId":1,"review":"Great place!"}' http://localhost:8080/api/reviews # use existing IDs for posId and authorId
```

Users cannot create more than one review per POS (the second request returns `409 Conflict`):
```shell
curl --header "Content-Type: application/json" --request POST --data '{"posId":2,"authorId":1,"review":"Great place!"}' http://localhost:8080/api/reviews # use existing IDs for posId and authorId
```

##### Approve reviews

Users cannot approve their own reviews:
```shell
curl --request PUT 'http://localhost:8080/api/reviews/4/approve?user_id=1' # use existing review ID and user ID (of the author)
```

However, users can approve the same review multiple times. This is a known limitation of the current
implementation: The system only counts approvals and never records *who* approved, and without
authentication the approver id is client-asserted anyway. The fix — recording approvers in a
`review_approvals` table with a unique `(review_id, user_id)` constraint — is tracked as `TODO (Exercise 5)`
in `ReviewServiceImpl` and lands together with authentication/authorization (see
[Scope](#scope-no-authentication-yet)):
```shell
curl --request PUT 'http://localhost:8080/api/reviews/4/approve?user_id=2' # use existing review ID and user ID (different from author)
```
```shell
curl --request PUT 'http://localhost:8080/api/reviews/4/approve?user_id=2' # use existing review ID and user ID (different from author)
```
```shell
curl --request PUT 'http://localhost:8080/api/reviews/4/approve?user_id=2' # use existing review ID and user ID (different from author)
```

## Docker

### Building an image from the Dockerfile

```shell
docker build -t campus-coffee:latest .
```

#### Manually create and run a Docker container based on the created image

First, create a new Docker network named `campus-coffee-net`,
then run a Postgres container and connect it to `campus-coffee-net`.
Finally, run the container with the application (in `dev` profile, do not use in production),
connect it to the network, and configure the application
to use the database provided in the started Postgres container.

```shell
docker network create campus-coffee-net 2>/dev/null || true
docker rm -f db 2>/dev/null || true
docker run -d --name db --net campus-coffee-net -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:17-alpine
docker run --net campus-coffee-net -e SPRING_PROFILES_ACTIVE=dev -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/postgres -p 8080:8080  -it --rm campus-coffee:latest
```

Explanation of selected options:

`docker run -p 8080:8080 ` runs the container with port 8080 exposed to the host machine. You can change the port mapping if needed.
`docker run ... -it`  runs a container in interactive mode with a pseudo-TTY (terminal).
`docker run ... --rm` automatically removes the container (and its associated resources) if it exists already.

Both run methods start the app in the `dev` profile. Since the application does not load data on startup, the API comes up empty — load it with `PUT /api/dev/data` (see [Dev endpoints](#dev-endpoints-apidev)).

#### Use Docker compose to run the app container together with the DB container

Build container image:

```shell
docker compose build
```

Delete existing DB container (if you manually created it before):

```shell
docker rm -f db 2>/dev/null || true
```

Create and start containers (the Compose file defaults `DB_HOST` to `localhost` for Cloud Run, so set
`DB_HOST=db` locally to reach the database container by its Compose service name):

```shell
docker compose down && DB_HOST=db docker compose up
```

Stop and remove containers and networks:

```shell
docker compose down
```

The `db` service has no named volume, so `docker compose down` discards its data and the next `DB_HOST=db docker compose up` starts with an empty database — reload it with `PUT /api/dev/data`.

## Deployment

### Deploy CampusCoffee to Google Cloud Run

We use the `gcloud` CLI (see [`mise.toml`](mise.toml)) to build CampusCoffee from source with Cloud Build
and deploy it to Cloud Run, using [`compose.yaml`](compose.yaml) (the `dev` profile).

> **Keep this deployment private.** The `dev` profile exposes Swagger and the unauthenticated `/api/dev`
> data endpoints, and — until you finish the assignment — write requests need no credentials. Do not expose it on
> the public internet: leave the service **IAM-gated** (the default below), or treat it as throwaway. Once
> your solution enforces authentication, the prod profile ([`compose.prod.yaml`](compose.prod.yaml)) is
> the one designed for a public URL.

Deploying from Compose is [still in preview](https://docs.cloud.google.com/run/docs/deploy-run-compose),
so install the `beta` component first (`gcloud` prompts to install any other required components on first
run). You also need a Google Cloud project with billing enabled.

```shell
gcloud components install beta
gcloud auth login
gcloud config set project <your-project-id>
gcloud config set run/region <region>   # e.g. europe-west3; otherwise compose up prompts for one
```

Build and deploy from source. This creates **one** Cloud Run service (named after the Compose project,
`campus-coffee`) that runs the app and PostgreSQL as **sidecar containers** sharing one network namespace —
which is why `compose.yaml` reaches the database at `localhost`, not the Compose service name `db`
(`DB_HOST` defaults to `localhost`; see the file's comments):

```shell
gcloud beta run compose up compose.yaml
```

`compose up` deploys the service IAM-gated (not publicly invocable), which is what we want here. Read its
URL and reach it with your own identity token:

```shell
URL=$(gcloud run services describe campus-coffee --format='value(status.url)')
curl -H "Authorization: Bearer $(gcloud auth print-identity-token)" "$URL/api/pos"
```

This is a throwaway demo. Running PostgreSQL as a sidecar container next to the app is fine for a demo but
not how you would run it in production: Cloud Run treats the container as ephemeral, so a cold start
brings up an empty database. A real cloud deployment points the app at a **managed (hosted) database** —
e.g., Cloud SQL — instead of a self-managed Postgres container. Delete the deployment when you are done:

```shell
gcloud run services delete campus-coffee
```

## Code coverage and mutation testing

### Code coverage (JaCoCo)

Coverage is measured with JaCoCo. Most production code in `domain`, `api`, and `data` is tested
by the system and acceptance tests in the `application` module, so per-module reports alone are
misleading. The `coverage` module aggregates the execution data from every module into a single
report that covers all of them together.

Run the full build to produce the reports:

```shell
gradle build
```

- Combined report: [`coverage/build/reports/jacoco/testCodeCoverageReport/html/index.html`](coverage/build/reports/jacoco/testCodeCoverageReport/html/index.html)
- Per-module reports: `domain/build/reports/jacoco/test/html/index.html`, `api/...`, `data/...`

`gradle build` also enforces the coverage gate (the `coverageGate` task, wired into `check`): the build
fails when the aggregated line or branch coverage is below the minimums configured in
[`coverage/build.gradle.kts`](coverage/build.gradle.kts). The minimums are set to the current measured
coverage; raise them as you add tests so the bar follows the suite. The CI workflow runs `gradle build`
and uploads the reports as the `jacoco-coverage-reports` artifact, so you can browse the uncovered lines
without a local run.

### Mutation testing (PITest)

Mutation testing reports whether the tests actually detect changed behavior. It is opt-in via the
`-Pmutation` property and meant to be run locally, since it re-runs the tests for every mutant and the
data and system tests run against a PostgreSQL database in a container managed by Testcontainers. Each
module runs PITest against its own tests and writes its own report: `domain` mutates `domain.*`, `api`
mutates `api.*`, and `data` mutates `data.*`, each against that module's own unit and integration tests;
the `application` module additionally mutates `api.*` and `data.*` against the system and acceptance
tests via additional mutable code paths (the Gradle equivalent of Maven's `crossModule`). The api and
data classes therefore appear in two reports (the module's own and application's), which are not merged.
Read a module's report for what its own tests catch and the application report for what the system tests
catch (the controllers, for example, have no api-local tests and are killed only there). The generated
`*MapperImpl` classes are excluded from mutation, mirroring the JaCoCo gate.

```shell
# Full run across all modules.
gradle pitest -Pmutation

# Stronger or exhaustive mutator groups produce more, harder-to-kill mutants:
gradle pitest -Pmutation -Ppitest.mutators=STRONGER
gradle pitest -Pmutation -Ppitest.mutators=ALL

# Scope to one module while iterating (e.g., only domain, skipping the slow Testcontainers modules):
gradle :domain:pitest -Pmutation
```

Reports are written per module at `<module>/build/reports/pitest/index.html` (`domain`, `api`, `data`, and
`application`).

Surviving mutants point to behavior the tests run but do not assert; add assertions until they are
killed.

### Growing the test suite

The reports are a worklist for new tests:

1. Open the aggregate coverage report and pick an uncovered package or class.
2. Add tests for the uncovered lines and branches.
3. Run PITest on that class and add assertions until the surviving mutants are killed.
4. Raise the coverage minimums in `coverage/build.gradle.kts` to the new measured level.
