# AI Interview Project Design

## Product Goal

Build a text-first resume and interview preparation application for technical job seekers.
Users upload a resume, optionally provide a job description, receive a structured resume strength assessment, then answer generated interview questions and get feedback.

The initial release should avoid video, audio, live coding, and complex collaboration features. It should focus on reliable text extraction, useful scoring, role-aware question generation, and clear feedback loops.

## Initial Scope

### In Scope

- Resume upload as PDF, DOCX, TXT, or pasted plain text.
- Text extraction and normalization.
- Optional job description input per assessment.
- Resume scoring for technical roles.
- Resume feedback grouped by category.
- RAG-grounded AI-generated interview questions based on resume, target role, and optional job description.
- Text answers from users.
- AI feedback on answer quality.
- Assessment and interview history.
- Basic admin-safe observability: logs, metrics, traces, and error reporting.

### Out of Scope

- Video interview recording.
- Voice transcription.
- Real-time interviewer avatar.
- Browser-based coding IDE.
- Payments and subscription billing.
- Recruiter-facing workflow.
- Multi-user organization accounts.
- Authentication and user accounts in the first prototype.

## Recommended Tech Stack

### Frontend

- Next.js with App Router.
- TypeScript.
- Tailwind CSS for fast UI delivery.
- shadcn/ui or Radix UI primitives for accessible components.
- TanStack Query for API state and server mutation handling.
- React Hook Form with Zod for form validation.
- Playwright for end-to-end smoke tests.
- Vitest and React Testing Library for component-level tests.

### Backend

- Java 21.
- Spring Boot.
- Spring Web MVC for REST APIs.
- Spring Data JPA for transactional domain data.
- Flyway for database migrations.
- Spring AI for Gemini chat, Gemini embeddings, and pgvector integration.
- Apache Tika for resume text extraction from PDF/DOCX/TXT.
- Bean Validation for request validation.
- MapStruct if DTO mapping grows beyond simple hand-written mappers.
- Testcontainers for PostgreSQL, pgvector, Redis, and object storage integration tests.

### Data and Infrastructure

- PostgreSQL as primary relational database.
- pgvector for RAG retrieval across resume, job description, question, and answer embeddings.
- Redis for rate limiting, idempotency responses, and five-minute completed-job lookup caching.
- AWS SQS Standard Queue for asynchronous job delivery, with a dedicated DLQ.
- LocalStack S3-compatible storage for local original resume files and generated artifacts.
- Docker Compose for local development.
- OpenTelemetry for traces and metrics.
- Prometheus and Grafana later, once the service has meaningful traffic.
- Sentry or equivalent error monitoring for frontend and backend.

### AI Provider

Gemini is the initial AI provider.

- Chat model: Google GenAI through Spring AI, starting with `gemini-2.5-flash` for fast structured generation.
- Thinking config: set `GEMINI_THINKING_BUDGET=0` for Gemini 2.5 Flash in the first text-only workflow to reduce latency.
- Embedding model: Google GenAI text embeddings, starting with `gemini-embedding-001` configured to 1024 dimensions.
- Authentication: `GEMINI_API_KEY` for local development through the Gemini Developer API; Vertex AI credentials can be added later for production.
- Backend config: keep chat and embedding models disabled by default for local startup, then enable with `AI_CHAT_MODEL=google-genai` and `AI_EMBEDDING_MODEL=google-genai`.

Abstract Gemini usage behind backend services even though it is the chosen provider. This keeps prompts, schema validation, retries, and future model swaps isolated from controllers and domain services.

## Target Repository Structure

The current repository keeps the Spring Boot backend at the root and the Next.js frontend under `apps/web`.
Keep the backend package tree shallow while the product is still small:

```text
ai_interview/
  src/main/java/dev/jiaming/ai_interview/
    assessment/                  # HTTP endpoint for resume assessment
    coach/                       # AI workflow orchestration and response contracts
    common/                      # API errors, config, local user helper
    gemini/                      # Gemini client boundary
    interview/                   # Interview endpoints and persistence
    jobs/                        # Durable jobs, SQS transport, leases, worker
    rag/                         # RAG indexing and retrieval
    resume/                      # Upload, extraction, chunking, latest-resume persistence
    storage/                     # S3-compatible object storage
  src/main/resources/
    db/migration/                # Flyway migrations; versioned names are expected here
    prompts/
  src/test/java/dev/jiaming/ai_interview/
  apps/
    web/                         # Next.js frontend
      app/
      lib/
      package.json
  docs/
    project-design.md
  docker-compose.yml             # Local PostgreSQL, Redis, LocalStack
  README.md
```

If the backend grows enough to need independent deployment or multiple services, move it to `apps/api` later. Do not do that until the extra directory level buys something concrete.

## Backend Package Structure

Use package-by-feature with a small shared kernel:

```text
dev.jiaming.ai_interview
  assessment/
  coach/
  common/
  gemini/
  interview/
  jobs/
  rag/
  resume/
  storage/
```

Important boundary: controllers should only handle HTTP concerns, `coach/` owns AI workflows, `rag/` owns context retrieval, `gemini/` owns provider calls, feature packages own their persistence, and `storage/` owns object storage. Add subpackages only after a package has enough files or mixed responsibilities to justify it.

## Core Domain Model

The prototype is single-user and single-resume from the API perspective. API requests are unauthenticated and operate on the latest uploaded resume, but the backend persists app-owned data in the `ai_interview_app` schema using an internal local user. Multi-user ownership can be added later after the resume and interview workflow is working end to end.

### Resume

- `id`
- `original_filename`
- `content_type`
- `detected_content_type`
- `size_bytes`
- `storage_key`
- `raw_text`
- `normalized_text`
- `detected_role`
- `detected_seniority`
- `parsed_skills`
- `created_at`

### JobDescription

- `id`
- `title`
- `company`
- `raw_text`
- `normalized_text`
- `parsed_requirements`
- `created_at`

### ResumeAssessment

- `id`
- `resume_id`
- `job_description_id`
- `overall_score`
- `technical_depth_score`
- `impact_score`
- `clarity_score`
- `relevance_score`
- `ats_score`
- `strengths_json`
- `weaknesses_json`
- `recommendations_json`
- `model_name`
- `prompt_name`
- `created_at`

### InterviewSession

- `id`
- `resume_id`
- `job_description_id`
- `assessment_id`
- `target_role`
- `seniority`
- `status`
- `created_at`
- `completed_at`

### InterviewQuestion

- `id`
- `session_id`
- `question_text`
- `category`
- `difficulty`
- `expected_signals_json`
- `source_context_json`
- `order_index`

### InterviewAnswer

- `id`
- `question_id`
- `answer_text`
- `score`
- `feedback_json`
- `created_at`

### Embedding Tables

Use pgvector for RAG retrieval, semantic matching, and deduplication:

- `resume_chunks`: resume section chunks and embeddings.
- `job_description_chunks`: job requirement chunks and embeddings.
- `question_embeddings`: generated question embeddings for deduplication.
- `answer_embeddings`: optional in the initial release, useful for analytics and future coaching.

## RAG Design

RAG is required in the initial release. The application should not ask Gemini to evaluate a whole unbounded resume and job description directly when a scoped retrieval step can provide better context.

### Indexing Pipeline

1. Extract resume text with Apache Tika.
2. Normalize whitespace, remove repeated headers/footers, and preserve section labels.
3. Split into chunks by resume section first, then by token/character limits.
4. Attach metadata to each chunk:
   - `userId`
   - `resumeId`
   - `sourceType`
   - `section`
   - `chunkIndex`
   - `detectedRole`
   - `detectedSeniority`
5. Generate embeddings with Google GenAI text embeddings.
6. Store chunk text, metadata, and vector in PostgreSQL/pgvector.

Job descriptions follow the same process, with metadata for title, company, requirement category, and seniority signal.

### Retrieval Pipeline

For resume assessment, retrieve context using multiple focused queries:

- technical depth and systems ownership
- measurable impact
- target role alignment
- job description must-have requirements
- missing or weak experience signals

For interview question generation, retrieve:

- strongest resume projects
- weakest resume areas
- most relevant job description requirements
- prior generated questions to avoid duplicates

For answer feedback, retrieve:

- the source resume/project context for the question
- expected answer signals
- the job description requirement that motivated the question

### Prompt Grounding Rules

- Gemini should receive only retrieved context snippets, user-visible question/answer text, scoring rubric, and schema instructions.
- Prompts must tell Gemini to avoid inventing experience not present in the retrieved context.
- Every response should include `sourceContextIds` where possible so the UI can explain which resume or job description snippets influenced the result.
- If retrieval confidence is weak, the output should say what information is missing instead of guessing.

### Retrieval Defaults

- Embedding dimensions: `1024`.
- Similarity metric: cosine distance.
- Default top K: `8`.
- Store enough metadata to filter by `userId`, `resumeId`, `jobDescriptionId`, and `sourceType`.
- Use separate domain chunk tables for application-owned queries and Spring AI `vector_store` for framework-backed retrieval experiments.

## Resume Scoring Design

Score resumes on dimensions that matter for technical hiring:

- `technical_depth`: specificity of technologies, systems, complexity, and ownership.
- `impact`: quantified results, business outcomes, scale, latency, revenue, adoption, reliability.
- `clarity`: readability, concise bullets, action verbs, structure.
- `relevance`: match against target role and optional job description.
- `ats`: formatting, section naming, keyword coverage, parseability.

The API should return both numeric scores and actionable feedback. Avoid pretending the score is absolute; it is a decision-support signal.

Example response shape:

```json
{
  "overallScore": 78,
  "scores": {
    "technicalDepth": 82,
    "impact": 70,
    "clarity": 76,
    "relevance": 84,
    "ats": 79
  },
  "strengths": ["Strong backend systems experience"],
  "weaknesses": ["Several bullets lack measurable impact"],
  "recommendations": [
    {
      "section": "Experience",
      "priority": "high",
      "message": "Add scale, latency, traffic, cost, or reliability metrics to backend project bullets."
    }
  ]
}
```

## Interview Generation Flow

1. User uploads resume.
2. Backend extracts text with Apache Tika.
3. Backend normalizes text and detects likely role/seniority.
4. Backend chunks resume and stores embeddings in pgvector.
5. User optionally adds job description.
6. Backend parses and embeds job description.
7. Backend retrieves relevant resume and job description chunks from pgvector.
8. Backend creates a Gemini-powered resume assessment using structured AI output grounded by retrieved chunks.
9. Backend generates interview questions from:
   - retrieved resume facts,
   - retrieved job requirements,
   - weak resume areas,
   - target role and seniority,
   - previous generated questions for deduplication.
10. User answers questions.
11. Backend retrieves the question context and evaluates answer quality with Gemini.

## Interview Question Categories

- Resume deep dive.
- Technical fundamentals.
- System design.
- Project architecture.
- Debugging and incident response.
- Collaboration and leadership.
- Behavioral examples.
- Role-specific tooling and domain knowledge.

For the initial release, generate 8 to 12 questions per session:

- 3 resume-specific questions.
- 2 technical fundamentals questions.
- 2 system/project design questions.
- 1 debugging or production-readiness question.
- 1 behavioral question.
- 1 to 3 job-description-specific questions when a job description exists.

## Answer Feedback Rubric

Each answer should receive:

- `score`: 0 to 100.
- `summary`: short direct evaluation.
- `strengths`: what the answer did well.
- `gaps`: missing details or weak reasoning.
- `betterAnswerOutline`: concise improved answer structure.
- `followUpQuestion`: one realistic interviewer follow-up.

The backend should enforce JSON schema output from the model and retry once with a repair prompt if parsing fails.

## REST API Surface

The current prototype is no-auth and single-resume from the API perspective, with uploaded resumes, assessments, interview questions, and answer feedback persisted for an internal local user.

### Resumes

- `POST /api/resumes` multipart upload for PDF, DOC, DOCX, TXT, or Markdown; returns `202` with a `RESUME_EXTRACTION` job.
- `GET /api/resumes/current`

### Assessments

- `POST /api/analyses`; returns `202` with one `ANALYSIS` job that checkpoints assessment before generating questions.

Request includes resume text or uses the latest uploaded resume, optional job description, target role, and seniority.

### Interview Practice

- `POST /api/interview/feedback`; returns `202` with an `ANSWER_FEEDBACK` job.
- `GET /api/jobs/{jobId}`

The legacy assessment and question routes submit the same combined analysis job.
Job status is one of `QUEUED`, `PROCESSING`, `RETRYING`, `SUCCEEDED`, `PARTIAL`,
or `FAILED`; stages report real work and never estimated percentages.

### Future Persistent APIs

These APIs should be added when database-backed history is introduced:

#### Job Descriptions

- `POST /api/job-descriptions`
- `GET /api/job-descriptions`
- `GET /api/job-descriptions/{jobDescriptionId}`
- `DELETE /api/job-descriptions/{jobDescriptionId}`

#### Assessments

- `GET /api/assessments`
- `GET /api/assessments/{assessmentId}`

#### Interview Sessions

- `POST /api/interview-sessions`
- `GET /api/interview-sessions`
- `GET /api/interview-sessions/{sessionId}`
- `POST /api/interview-sessions/{sessionId}/questions/{questionId}/answers`
- `GET /api/interview-sessions/{sessionId}/summary`

## Frontend Structure

```text
apps/web/
  app/
    dashboard/
    resumes/
      page.tsx
      [resumeId]/
    assessments/
      [assessmentId]/
    interviews/
      [sessionId]/
    layout.tsx
    page.tsx
  components/
    ui/
    layout/
  features/
    resume-upload/
    resume-assessment/
    job-description/
    interview-session/
  lib/
    api-client.ts
    query-client.ts
    validation/
    formatters/
```

First screen should be the working dashboard, not a marketing page. It should show the latest uploaded resume, assessment score, active interview session, and a clear upload action.

## Initial User Flow

1. Upload resume or paste resume text.
2. Optionally paste job description.
3. Start the combined assessment and question-generation job.
4. Review score, recommendations, and generated questions.
6. Answer questions one by one.
7. Review feedback and session summary.
8. Re-upload revised resume and compare scores.

## PostgreSQL and pgvector Notes

Enable pgvector:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Use vector dimensions matching the selected embedding model. The initial release uses Google GenAI `gemini-embedding-001` configured to 1024 dimensions, but this must remain configurable because embedding providers and model options vary.

Recommended vector indexes:

```sql
CREATE INDEX idx_resume_chunks_embedding
ON resume_chunks
USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_job_description_chunks_embedding
ON job_description_chunks
USING hnsw (embedding vector_cosine_ops);
```

## Redis Usage

The current implementation uses Redis at the HTTP submission boundary, not as product storage:

- Rate limit AI-heavy endpoints per client.
- Rate limit resume uploads per client.
- Cache accepted-job responses for retry when clients send an `Idempotency-Key` header.
- Cache completed job IDs for five minutes by user, job type, and request fingerprint.

Do not use Redis as the source of truth for jobs, assessments, answers, or user
history. PostgreSQL stores job state and leases.

## Object Storage Usage

The upload API validates and stores the original file synchronously, creates a
pending resume plus extraction job, and returns `202`. The worker reads the file
from S3, extracts with PDFBox/Apache Tika, and completes the same resume row and
chunks. `/api/resumes/current` only returns completed resumes from PostgreSQL.

Use the S3-compatible storage boundary for:

- Original uploaded resumes.
- Extracted text snapshots if needed.
- Generated downloadable reports later.

Store only object keys in PostgreSQL. Keep object storage private and serve downloads through signed URLs or backend proxy endpoints.

## Background Processing

The implemented job types are `RESUME_EXTRACTION`, `ANALYSIS`, and
`ANSWER_FEEDBACK`. PostgreSQL stores request/result JSON, status, stage, attempts,
timestamps, fingerprints, errors, and worker leases. SQS messages contain only
the job ID so resume and answer content never enters the queue body.

The API commits a job before dispatching it. A scheduled recovery scan republishes
queued rows whose `enqueued_at` is null, closing the database-commit/SQS-send
failure window. Duplicate queue deliveries are safe because a worker must claim
the row with a conditional lease update before processing it.

Workers use 20-second long polling, two processing threads, 300-second visibility
and lease timeouts, and 60-second heartbeats. Temporary Gemini/network failures
retry up to three attempts; invalid documents and validation failures do not.
Application retries use a fresh SQS message after the database `run_after` delay;
the consumed message is deleted only after that retry is durable. An exhausted
job is explicitly published to the DLQ before its final main-queue message is
deleted. Native SQS redrive still handles malformed messages and worker crashes.
An analysis checkpoint prevents a question-generation retry from scoring the
resume twice, and produces `PARTIAL` when only the assessment succeeds.

The application supports `all`, `api`, and `worker` runtime modes. EventBridge
Scheduler is deliberately deferred; a future production schedule can invoke the
same seven-day payload-cleanup operation for reminders or batch maintenance.

## Security and Privacy

- Store credentials with strong password hashing such as Argon2 or BCrypt.
- Validate upload file type and size.
- Scan uploaded files before processing if the app becomes public.
- Never expose raw model prompts to the frontend.
- Log request ids, not resume contents.
- Redact PII from logs.
- Add authentication and per-user authorization checks only when multi-user accounts are introduced.
- Encrypt secrets through environment variables or a secrets manager.

## Prompt and AI Output Management

Keep prompts in named backend files or database records:

```text
src/main/resources/prompts/
  resume-assessment.md
  interview-question-generation.md
  answer-feedback.md
```

Every AI-generated persisted record should store:

- `model_name`
- `prompt_name`
- `input_hash`
- `source_context_ids`
- `created_at`

This makes evaluations and prompt migrations possible.

## Implementation Milestones

### Milestone 1: Project Foundation

- Restructure as monorepo or consciously keep backend at root.
- Add Next.js frontend.
- Add Docker Compose for PostgreSQL with pgvector, Redis, and LocalStack S3.
- Add Flyway.
- Add basic health endpoint.

### Milestone 2: Resume Upload

- Implement resume upload and text extraction.
- Persist the latest extracted resume for the local internal user.
- Store original uploaded files in S3-compatible object storage.

### Milestone 3: Assessment

- Add job description input.
- Add resume and job description chunking.
- Store embeddings in pgvector.
- Retrieve resume and job description context with RAG.
- Generate structured Gemini resume assessment.
- Render assessment results in frontend.

### Milestone 4: Interview Sessions

- Generate RAG-grounded Gemini question sets.
- Let users answer questions.
- Generate Gemini answer feedback grounded by the original question context.
- Show session summary.

### Milestone 5: Hardening

- Add rate limits.
- Add retry and schema-repair logic for AI calls.
- Add observability.
- Add integration tests with Testcontainers.
- Add frontend end-to-end smoke tests.

## Design Decisions

- Keep AI orchestration in the backend so prompts, model keys, and scoring logic are not exposed to the browser.
- Use Gemini as the initial AI provider for chat and embeddings.
- Use PostgreSQL as the durable source of truth and pgvector for RAG semantic retrieval.
- Use Redis only for ephemeral workflow concerns.
- Defer object storage in the prototype; when persistence is added, store uploaded files in object storage rather than PostgreSQL.
- Treat every AI response as untrusted data until it passes schema validation.
- Keep the initial release text-only to reduce product and infrastructure complexity.
