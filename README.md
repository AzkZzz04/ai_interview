# AI Interview

Text-first resume assessment and interview practice application for technical job seekers.

The product lets users upload a resume, optionally provide a job description, receive a structured resume strength assessment, generate tailored interview questions, answer them, and get feedback.

## Current State

This repository currently contains the Spring Boot backend, the first local infrastructure setup, and a Next.js frontend prototype. The backend prototype is no-auth and single-resume for now.

- Spring Boot API backend.
- Next.js frontend.
- PostgreSQL with pgvector.
- Redis.
- LocalStack S3-compatible object storage and SQS queues with a DLQ.

See [docs/project-design.md](docs/project-design.md) for the proposed architecture, domain model, API surface, repository structure, and implementation milestones.

## Local Infrastructure

Create local environment config:

```bash
cp .env.example .env
```

Set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` in `.env` to match your existing PostgreSQL/pgvector instance. The local default database is `interview_guide` on `localhost:5432`.

Start the local services:

```bash
docker compose up -d
```

This starts:

- Redis on `localhost:6380` by default when managed by Compose
- LocalStack S3 and SQS on `localhost:4566`

LocalStack initializes the `ai-interview-jobs` standard queue and the
`ai-interview-jobs-dlq` dead-letter queue. Native SQS redrive moves malformed or
repeatedly interrupted messages after three receives; application retries that
exhaust their database attempt limit are published to the same DLQ explicitly.

The backend defaults to PostgreSQL on `localhost:5432` because this workspace already has pgvector there. If you need Compose to manage a separate PostgreSQL instance later, run:

```bash
docker compose --profile managed-postgres up -d
```

The managed PostgreSQL service binds to `localhost:55432` by default, or to `POSTGRES_PORT` if set.

The backend defaults to Redis on `localhost:6379`. The bundled Compose Redis service publishes container port `6379` to `localhost:6380` by default, so set `REDIS_PORT=6380` only if you are using that Compose-managed Redis instance. If your Docker Redis is already mapped to host port `6379`, leave `REDIS_PORT=6379`.

Redis is used for submission-time operational guardrails only: per-client limits,
and `Idempotency-Key` responses. Five-minute same-input job reuse is resolved
directly from PostgreSQL.
PostgreSQL remains the source of truth for job status and results; database
leases prevent concurrent workers from owning the same job.

Mutation endpoints also support an optional `Idempotency-Key` header. When present, Redis stores the successful response for the same client, endpoint, and request fingerprint for `REDIS_IDEMPOTENCY_TTL_SECONDS` seconds. Retrying the same request with the same key returns the cached response; reusing the key with a different payload returns `409`.

The job submission service also fingerprints the complete AI request. The same
local user, job type, resume, job description, target role, and seniority reuse
an active or completed job for 300 seconds instead of calling Gemini again.

The backend stores original uploaded resume files in S3-compatible storage and defaults to LocalStack:

```properties
S3_ENDPOINT=http://localhost:4566
S3_REGION=us-east-1
S3_BUCKET=ai-interview
S3_ACCESS_KEY=test
S3_SECRET_KEY=test
```

Application-owned persistence tables are created under the `ai_interview_app` schema to avoid collisions with existing local tables in `public`.

The same Spring Boot build supports three process modes:

```properties
JOB_RUNTIME_MODE=all     # local default: API and worker
JOB_RUNTIME_MODE=api     # submit and query jobs only
JOB_RUNTIME_MODE=worker  # consume SQS jobs only
```

Workers use 20-second SQS long polling, two processing threads, a 300-second
visibility timeout, and a 60-second SQS/database lease heartbeat by default.

Gemini calls use the Gemini Developer API key from local `.env`. To enable Gemini chat and embeddings for development, update `.env`:

```properties
GEMINI_API_KEY=your-api-key
AI_CHAT_MODEL=google-genai
AI_EMBEDDING_MODEL=google-genai
GEMINI_CHAT_MODEL=gemini-2.5-flash
GEMINI_REQUEST_TIMEOUT_SECONDS=90
GEMINI_MAX_OUTPUT_TOKENS=2048
GEMINI_THINKING_BUDGET=0
GEMINI_EMBEDDING_MODEL=gemini-embedding-001
RAG_EMBEDDING_DIMENSIONS=1024
RAG_CHUNK_SCHEMA=section-context-v2
```

Keep `.env` local and untracked. Do not put real API keys in `.env.example`.

## First Implementation Milestones

1. Set up project foundation and local infrastructure.
2. Add resume upload and text extraction.
3. Add resume assessment with optional job description matching.
4. Add interview question generation and answer feedback.
5. Harden with rate limits, tests, observability, and schema validation.

## Backend API

The no-auth API creates persistent jobs for a single internal local user:

- `POST /api/resumes` with multipart field `file` returns `202`
- `GET /api/resumes/current`
- `POST /api/analyses` returns one job for assessment and question generation
- `POST /api/interview/feedback` returns `202`
- `GET /api/jobs/{jobId}` returns status, stage, attempts, result, and error

The legacy `POST /api/assessments` and `POST /api/interview/questions` routes
also submit the combined asynchronous analysis job.

Supported upload formats: PDF, DOC, DOCX, TXT, and Markdown.

The frontend stores active job IDs in `localStorage` and resumes polling after a
refresh. It displays backend stages rather than estimated percentages. Failed
jobs use local draft output; partial analysis jobs retain the completed Gemini
assessment and only replace missing questions.

## Verification

Run the backend and frontend checks:

```bash
./gradlew check
cd apps/web
npm test
npm run typecheck
npm run build
```

With LocalStack running, exercise real SQS publish, visibility, redelivery, and
DLQ behavior with the opt-in integration test:

```bash
RUN_LOCALSTACK_TESTS=true ./gradlew test \
  --tests dev.jiaming.ai_interview.jobs.JobQueueServiceLocalStackTests
```
