# Supabase development migration

This migration starts with an empty project, mjzycnjhtwyqcblwbjvy. It copies no existing records or embeddings. JDBC retains writes, transactions, leases, checkpoints, document persistence and Spring AI vector access. Only job-status polling uses the Supabase Kotlin SDK in the supabase profile. Local PostgreSQL remains supported. The fixed local user remains a development boundary; public multi-user deployment requires the separate authentication work.

## Credentials and certificate

Use the project's [API keys](https://supabase.com/dashboard/project/mjzycnjhtwyqcblwbjvy/settings/api-keys) for a backend secret key starting sb_secret_. A personal access token starting sbp_ is not an SDK key. Keep the backend key out of browsers and chat.

Use [database settings](https://supabase.com/dashboard/project/mjzycnjhtwyqcblwbjvy/database/settings) for the project database password and root certificate. The migration password is the current project password. Choose a separate new password for the runtime role. Get the session-pooler host from the dashboard's Connect panel (session mode, port 5432); do not use transaction mode or disable certificate verification.

Create a private, ignored .env.supabase at the repository root. It is Java Properties, with unquoted values, not a shell script; do not source it. Escape Java Properties backslashes in passwords if needed. Use these fields:

~~~properties
SUPABASE_URL=https://mjzycnjhtwyqcblwbjvy.supabase.co
SUPABASE_SECRET_KEY=<backend sb_secret_ key>
SUPABASE_SSL_ROOT_CERT=/absolute/path/to/.supabase/prod-ca-2021.crt
SUPABASE_MIGRATION_URL=jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=verify-full&currentSchema=public,extensions
SUPABASE_MIGRATION_USERNAME=postgres.mjzycnjhtwyqcblwbjvy
SUPABASE_MIGRATION_PASSWORD=<current project database password>
DATABASE_URL=jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=verify-full&currentSchema=public,extensions
DATABASE_USERNAME=ai_interview_runtime.mjzycnjhtwyqcblwbjvy
DATABASE_PASSWORD=<new runtime password>
DATABASE_MAX_POOL_SIZE=4
REDIS_KEY_PREFIX=ai-interview:supabase-mjzycnjhtwyqcblwbjvy:
S3_BUCKET=ai-interview-supabase-mjzycnjhtwyqcblwbjvy
SQS_QUEUE_NAME=ai-interview-supabase-mjzycnjhtwyqcblwbjvy-jobs
SQS_DLQ_NAME=ai-interview-supabase-mjzycnjhtwyqcblwbjvy-jobs-dlq
~~~

Keep this file mode 600. Both .env.supabase and .supabase/ are ignored. Supply Gemini and existing provider credentials separately as before.

## Initialize without starting application processes

Before initialization inspect the selected target and confirm it contains no application objects. The runner also rejects initial application objects without owned Flyway history. Flyway is the sole migration system: V1–V7 stay unchanged, V8 adds the restricted API view. Provider-created public objects trigger explicit baseline version 0, so V1 still executes. Extensions resolve through public,extensions.

~~~sh
./gradlew supabaseMigrate --no-daemon
./gradlew supabaseMigrate --args=bootstrap-runtime --no-daemon
~~~

The second command validates existing migrations, creates or updates the restricted ai_interview_runtime role, and grants application DML and vector access. It rejects existing privileged roles, role memberships, and application ownership. It does not start Spring, API servers, or schedulers. Supabase API and worker processes do not migrate automatically. Existing migrations are checksum-validated; no clean operation is allowed.

In [Data API settings](https://supabase.com/dashboard/project/mjzycnjhtwyqcblwbjvy/integrations/data_api/settings), keep the Data API enabled and set Exposed schemas to only ai_interview_api. Remove public, graphql_public, and any application schema. V8 grants service_role only the job-status view and its necessary source columns; anon and authenticated receive no access. Do not apply broad anon/authenticated grants from generic custom-schema examples. Application tables and public.vector_store must remain unexposed.

For local startup, use SPRING_PROFILES_ACTIVE=supabase explicitly. Provision the new S3/SQS resources and Redis namespace before submitting work:

~~~sh
SPRING_PROFILES_ACTIVE=supabase JOB_RUNTIME_MODE=api ./gradlew bootRun
~~~

Start workers separately with JOB_RUNTIME_MODE=worker and the same isolated provider configuration. Workers require JDBC and CA credentials, but no SDK secret key. Preserve the 1,024-dimensional vector column, HNSW cosine index and gemini-embedding-001 model.

## Helm configuration

Build and publish the backend image from this migration branch first. Replace the example API and worker image tags with that immutable commit tag; the chart defaults predate this migration. The default chart still deploys bundled PostgreSQL. For Supabase use deploy/ai-interview/values-supabase.example.yaml and create referenced secrets in the intended namespace. A private runtime.env must contain only DATABASE_URL, DATABASE_USERNAME, DATABASE_PASSWORD; a private sdk.env must contain only SUPABASE_SECRET_KEY. Never create a pod secret from the complete .env.supabase file, which contains migration administrator credentials.

~~~sh
kubectl -n <namespace> create secret generic ai-interview-supabase-runtime --from-env-file=/private/path/runtime.env
kubectl -n <namespace> create secret generic ai-interview-supabase-sdk --from-env-file=/private/path/sdk.env
kubectl -n <namespace> create secret generic ai-interview-supabase-ca --from-file=ca.crt=/absolute/path/to/.supabase/prod-ca-2021.crt
helm lint deploy/ai-interview
node scripts/check-supabase-chart.cjs
helm lint deploy/ai-interview -f deploy/ai-interview/values-supabase.example.yaml
helm template interview deploy/ai-interview -f deploy/ai-interview/values-supabase.example.yaml
~~~

API and worker receive only three named runtime DB secret keys and a read-only CA mounted at /etc/supabase/ca.crt. Only API receives the SDK key. Missing external DB, SDK or CA references and contradictory bundled-PostgreSQL/Supabase settings fail rendering. Disabling bundled PostgreSQL removes its workload/service/development secret and its dependency wait. The PostgreSQL PVC remains rendered when persistence is enabled and has Helm's keep policy; preserve it for rollback.

At pool size 4, one API and two workers permit 12 application connections. Default rolling-update surges can permit five processes, or 20 connections. Verify the project's Supavisor per-role session-pool capacity, PostgreSQL reserved connections, Supabase services and deployment overlap before deploying. Observing max_connections alone is insufficient. Keep old submissions/workers stopped during this controlled development cutover.

## Cutover verification and rollback

1. Save the complete old configuration bundle, Helm values, secret references and resource names. Preserve old database volumes, queues/DLQ, Redis state and S3 objects.
2. Stop old submissions and consumers; let active work finish where possible. Do not mix old queues or cached workflow state with the empty database.
3. Start the new environment using the example's new Redis prefix, SQS/DLQ names and S3 bucket. Do not run scripts/reset-local-environment.sh for cutover or rollback.
4. Clear only this application's saved browser workflow keys: ai-interview:job:resume, ai-interview:job:analysis, ai-interview:job:feedback, and their session-storage <base>:context:<generation> keys. Do not clear all browser storage.
5. Verify upload, extraction, analysis, question generation, feedback and reload recovery. Confirm JDBC-written transitions appear through SDK polling; missing jobs remain JOB_NOT_FOUND, upstream failures become sanitized 503 and browser polling retains active work. Verify vector retrieval respects index IDs and claim versions.
6. Verify anonymous/authenticated Data API reads fail and backend SDK reads succeed. Verify runtime JDBC cannot create application tables. Repeat permission checks through the actual Data API after changing exposed schemas.
7. Rehearse rollback: stop new submissions and workers, then restore the complete old DB/profile/Redis/SQS/DLQ/S3 configuration bundle and start the old environment. Verify old saved workflows against the old resources. Retain new Supabase data separately; rollback does not merge post-cutover writes.

Watch readiness, SDK 503 rates, job failures/retries, queue age, JDBC pool exhaustion and cross-environment resource use during the first complete workflow and reload. Roll back for persistent unexpected polling failures, failed durable transitions, incorrect ownership, or namespace mixing. The development operator owns validation. Do not switch production traffic based only on local test results.

## Verification status (2026-09-30)

Verified on the selected live project: certificate-verified session-pooler connection; Flyway baseline 0 and V1–V8; vector(1024); HNSW vector_cosine_ops index; security_invoker view; restricted runtime role; runtime JDBC reads; application-table DDL rejection (42501). Effective database grants deny anon/authenticated view reads and allow service_role reads.

Local backend check and packaged build passed: 184 unit and 38 integration tests. Frontend polling passed 8 tests; typecheck and lint passed. Bootstrap corrections and table-list simplification passed focused integration checks. Helm lint/render and the runnable chart contract check passed for local/Supabase resources, secret isolation, CA mounts, PVC retention, namespace isolation, and five invalid configurations. The chart check uses js-yaml already installed by npm ci in apps/web.

Data API exposed-schema configuration, backend SDK live smoke, full workflow operation, deployment capacity and rollback rehearsal remain unverified. At this checkpoint the configured SDK key did not have the required sb_secret_ prefix. The migration is not cutover-complete until these live checks pass.
