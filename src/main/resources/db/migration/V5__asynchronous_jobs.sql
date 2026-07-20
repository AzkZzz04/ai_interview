ALTER TABLE ai_interview_app.background_jobs
    ALTER COLUMN resource_type DROP NOT NULL,
    ALTER COLUMN resource_id DROP NOT NULL,
    ALTER COLUMN status SET DEFAULT 'QUEUED';

UPDATE ai_interview_app.background_jobs
SET status = 'QUEUED'
WHERE status = 'PENDING';

ALTER TABLE ai_interview_app.background_jobs
    ADD COLUMN IF NOT EXISTS user_id uuid REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    ADD COLUMN IF NOT EXISTS stage varchar(80) NOT NULL DEFAULT 'QUEUED',
    ADD COLUMN IF NOT EXISTS request_payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS result_payload jsonb,
    ADD COLUMN IF NOT EXISTS request_fingerprint varchar(64),
    ADD COLUMN IF NOT EXISTS error_code varchar(120),
    ADD COLUMN IF NOT EXISTS retryable boolean,
    ADD COLUMN IF NOT EXISTS max_attempts integer NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS enqueued_at timestamptz,
    ADD COLUMN IF NOT EXISTS started_at timestamptz,
    ADD COLUMN IF NOT EXISTS completed_at timestamptz,
    ADD COLUMN IF NOT EXISTS lease_token uuid,
    ADD COLUMN IF NOT EXISTS lease_expires_at timestamptz;

CREATE INDEX IF NOT EXISTS idx_background_jobs_user_fingerprint
ON ai_interview_app.background_jobs (user_id, job_type, request_fingerprint, created_at DESC);

CREATE UNIQUE INDEX IF NOT EXISTS uq_background_jobs_active_fingerprint
ON ai_interview_app.background_jobs (user_id, job_type, request_fingerprint)
WHERE request_fingerprint IS NOT NULL
  AND status IN ('QUEUED', 'PROCESSING', 'RETRYING');

CREATE INDEX IF NOT EXISTS idx_background_jobs_undispatched
ON ai_interview_app.background_jobs (created_at)
WHERE enqueued_at IS NULL
  AND status IN ('QUEUED', 'RETRYING');

CREATE INDEX IF NOT EXISTS idx_background_jobs_lease
ON ai_interview_app.background_jobs (lease_expires_at)
WHERE status = 'PROCESSING';
