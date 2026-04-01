# hello-world

A Kotlin demo showing a long-running session architecture on Google Cloud:

- `console`: Ktor API/static host deployed as a Cloud Run Service
- `console-web`: Compose Multiplatform for Web frontend compiled to JS
- `worker`: Kotlin coroutine-based process deployed as a Cloud Run Job
- `Firestore`: durable store for session state and progress events

## What It Does

The console starts a session with a single button. That creates a Firestore session document and triggers a Cloud Run
Job execution. The worker performs a demo workload built around public zero-auth APIs plus delays, and writes progress
updates back to Firestore as it runs.

The browser UI is implemented with Compose Multiplatform for Web and polls the backend every few seconds, so the session
view refreshes while the job is still running.

## Modules

- `source/shared-models`: multiplatform DTOs shared by backend and web
- `source/shared`: JVM backend support such as config and Firestore repository
- `source/console`: Ktor console service and static asset host
- `source/console-web`: Compose Multiplatform JS frontend
- `source/worker`: worker job entrypoint and demo workload

## Session Data Model

Firestore collections:

- `sessions/{sessionId}`
- `sessions/{sessionId}/events/{eventId}`

Each session stores status, timestamps, current step, progress percentage, execution name, result summary, and failure
details.

## Local Development

Environment variables required by the console:

- `GCP_PROJECT_ID`
- `GCP_REGION`
- `WORKER_JOB_NAME`

Environment variable required by the worker:

- `SESSION_ID`

Run the console locally:

```bash
cd source
GCP_PROJECT_ID=<project-id> \
GCP_REGION=<region> \
WORKER_JOB_NAME=hello-world-worker \
./gradlew :console:run
```

Build the frontend bundle explicitly if you want to inspect it:

```bash
cd source
./gradlew :console-web:jsBrowserDistribution
```

The console server build automatically packages the frontend bundle into the server resources.

Run the worker locally:

```bash
cd source
SESSION_ID=<existing-session-id> ./gradlew :worker:run
```

## Build

```bash
cd source
./gradlew build
./gradlew :console-web:jsBrowserDistribution
./gradlew :console:buildFatJar :worker:buildFatJar
```

## Docker Images

The root `source/Dockerfile` builds both deployables using multi-stage targets:

- `console`
- `worker`

Examples:

```bash
docker build --target console -t console-image ./source
docker build --target worker -t worker-image ./source
```

## Infrastructure

### Foundation Stack

`infra/foundation/` provisions:

- GCP project and API enablement
- Artifact Registry
- application service account
- GitHub Actions deployment service account
- IAM needed for Firestore and Cloud Run deployment

### Support Stack

`infra/support/` provisions:

- Firestore database
- Cloud Run Service for the console
- Cloud Run Job for the worker

The console service receives these environment variables from Terraform:

- `GCP_PROJECT_ID`
- `GCP_REGION`
- `WORKER_JOB_NAME`

## CI/CD

GitHub Actions now:

1. runs `./gradlew build`
2. builds two images
3. pushes both images to Artifact Registry
4. applies Terraform with `console_image_reference` and `worker_image_reference`

## API Surface

- `GET /` serves the Compose Web app shell
- `GET /web/{asset}` serves the compiled frontend bundle
- `POST /sessions` creates a session and starts a worker job execution
- `GET /api/sessions` lists recent sessions
- `GET /api/sessions/{sessionId}` returns one session plus its events

## Notes

- The Compose frontend is compiled to JS and packaged into the console server jar during the `:console` resource phase.
- The demo intentionally uses polling for the console refresh path to keep the first version simple and reliable on
  Cloud Run.
- The worker uses coroutine-based I/O and is safe to run without any active observer.
- Worker retries are disabled in Terraform for now to avoid duplicate progress events in the demo.
