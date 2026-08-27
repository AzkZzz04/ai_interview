# AI Interview Coach

An AI-powered interview coach for technical candidates. Upload a resume, add an optional job description, and receive evidence-grounded resume assessment, tailored interview questions, and feedback on practice answers.

Built as a reliable modular monolith: Spring Boot handles the API and asynchronous jobs, while Next.js provides the candidate workflow.

## What it does

| Capability | How it works |
| --- | --- |
| Resume ingestion | Accepts PDF, DOC, DOCX, TXT, and Markdown; Apache Tika/PDFBox extracts and normalizes text. |
| Grounded assessment | Scores technical depth, impact, clarity, relevance, and ATS alignment using Resume and JD evidence. |
| Interview practice | Generates role-specific questions at Warmup, Core, and Deep Dive difficulty, then evaluates submitted answers. |
| Reliable AI jobs | Runs extraction, analysis, and feedback asynchronously with PostgreSQL leases, checkpoints, retries, and a DLQ. |
| Document-level RAG | Uses section-aware chunking, pgvector retrieval, per-source Resume/JD recall, deterministic RRF ranking, and source evidence IDs. |

## Architecture

```mermaid
flowchart LR
  Candidate["Candidate"] --> Web["Next.js web app"]
  Web --> API["Spring Boot API"]

  API --> Postgres["PostgreSQL + pgvector\nresources · jobs · results · vectors"]
  API --> S3["S3 / LocalStack\nuploaded resumes"]
  API --> Queue["SQS / LocalStack\njobId wake-up"]
  API --> Redis["Redis\nrate limits + idempotency"]

  Queue --> Worker["Spring Boot worker\ntyped job handlers"]
  Worker --> Postgres
  Worker --> S3
  Worker --> RAG["RAG context builder\nsection chunks + RRF"]
  RAG --> Postgres
  RAG --> Gemini["Gemini 2.5 Flash\nstructured generation"]
  Gemini --> Worker
```

### Request flow

1. The API stores an uploaded resume, creates a durable PostgreSQL job, and sends only its `jobId` to SQS.
2. A worker claims the job through a PostgreSQL lease, extracts text, creates section-aware chunks, and persists progress checkpoints.
3. For long documents, RAG retrieves Resume and JD evidence independently, then merges candidates with deterministic Reciprocal Rank Fusion (RRF).
4. Gemini receives selected, traceable evidence and returns structured assessment, interview questions, or answer feedback.
5. The frontend polls job status until results are complete, partial, or failed.

PostgreSQL is the source of truth for job state. SQS wakes workers; it does not carry document content or determine job completion.

## RAG design

- **Structured chunks:** Experience, Research Experience, and Projects preserve blank-line-separated entries; large chunks use natural boundaries with overlap.
- **Per-source retrieval:** Resume and Job Description indexes are retrieved independently, so a strong Resume match cannot crowd out JD evidence.
- **Deterministic fusion:** All query/source candidates are collected before ranking with RRF (`rrf-k=15`), using stable tie-breaks.
- **Evidence quality controls:** JD evidence is reserved when available, section diversity is capped, and overlapping neighboring chunks are suppressed.
- **Prompt-safe snippets:** section prefixes improve embedding retrieval but original persisted text is restored before being sent to Gemini or returned as evidence.

## Tech stack

| Layer | Technology |
| --- | --- |
| Backend | Java, Spring Boot, Gradle, JdbcTemplate, Flyway |
| Frontend | Next.js, TypeScript |
| AI | Gemini 2.5 Flash, structured JSON generation |
| Retrieval | PostgreSQL, pgvector, Gemini embeddings |
| Async workflow | AWS SQS + DLQ, PostgreSQL leases/checkpoints |
| Storage | S3-compatible storage via LocalStack |
| Operational guardrails | Redis rate limits and idempotency keys |
| Testing | JUnit 5, Mockito, MockMvc, Testcontainers, Vitest |

## Quick start

### 1. Configure local environment

```bash
cp .env.example .env
```

Set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for a PostgreSQL instance with pgvector. The default is `interview_guide` on `localhost:5432`.

To enable Gemini locally, add the following to the untracked `.env` file:

```properties
GEMINI_API_KEY=your-api-key
AI_CHAT_MODEL=google-genai
AI_EMBEDDING_MODEL=google-genai
GEMINI_CHAT_MODEL=gemini-3.6-flash
GEMINI_THINKING_LEVEL=medium
GEMINI_MAX_OUTPUT_TOKENS=4096
GEMINI_EMBEDDING_MODEL=gemini-embedding-001
RAG_EMBEDDING_DIMENSIONS=1024
RAG_CHUNK_SCHEMA=section-block-v3
```

Never commit API keys or a populated `.env` file.

### 2. Start local dependencies

```bash
docker compose up -d
```

This starts LocalStack on `localhost:4566` and Redis on `localhost:6380` by default. LocalStack initializes the S3 bucket and the `ai-interview-jobs` / `ai-interview-jobs-dlq` queues.

To run an isolated PostgreSQL container too:

```bash
docker compose --profile managed-postgres up -d
```

The managed PostgreSQL service is exposed on `localhost:55432` by default.

### 3. Start the application

In one terminal:

```bash
./gradlew bootRun
```

In another terminal:

```bash
cd apps/web
npm install
npm run dev
```

Open `http://127.0.0.1:3000`. The backend status endpoint is `http://127.0.0.1:8080/api/status`.

## Runtime modes

The same Spring Boot build supports API and worker deployment independently:

```properties
JOB_RUNTIME_MODE=all     # local default: API and worker
JOB_RUNTIME_MODE=api     # submit/query jobs only
JOB_RUNTIME_MODE=worker  # consume jobs only
```

Workers use SQS long polling and PostgreSQL-backed leases. Database retries use full jitter; terminal failures are routed to the DLQ. `PARTIAL` jobs preserve successful Gemini output and only fill missing portions with fallback content.

## API overview

| Endpoint | Purpose |
| --- | --- |
| `POST /api/resumes` | Upload a resume and create an extraction job. |
| `GET /api/resumes/current` | Retrieve the current resume; `404` means none exists. |
| `POST /api/analyses` | Submit combined assessment and question-generation work. |
| `POST /api/interview/feedback` | Submit answer-feedback work. |
| `GET /api/jobs/{jobId}` | Poll job status, stage, attempts, result, and error. |

Mutation endpoints accept an optional `Idempotency-Key`. Redis handles short-lived HTTP idempotency and rate limits; PostgreSQL fingerprints reuse identical AI jobs for five minutes.

## Verification

```bash
./gradlew check
./gradlew integrationTest

cd apps/web
npm test -- --run
npm run typecheck
npm run build
```

Testcontainers covers PostgreSQL/pgvector and LocalStack integration scenarios. Frontend tests use Vitest.

## Local destructive reset

To remove every record in the local `interview_guide` database and this project's LocalStack S3/SQS state, first stop the API and worker, then run:

```bash
RESET_CONFIRM=DROP_INTERVIEW_GUIDE \
RESET_DATABASE_NAME=interview_guide \
RESET_DATABASE_OWNER=ai_interview \
RESET_DATABASE_ADMIN_URL='postgresql://…@localhost:5432/postgres' \
./scripts/reset-local-environment.sh
```

The script refuses non-local hosts, databases other than `interview_guide`, and resources outside this project. It is irreversible: resumes, jobs, chunks, vectors, and interview history must be recreated.

## Further documentation

See [project design](docs/project-design.md) for the architecture, domain model, API surface, and implementation details.
