ALTER TABLE ai_interview_app.resumes
    ADD COLUMN IF NOT EXISTS processing_status varchar(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS failure_code varchar(120),
    ADD COLUMN IF NOT EXISTS failure_message text,
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

UPDATE ai_interview_app.background_jobs
SET status = 'FAILED',
    error_code = 'LEGACY_JOB_UNSUPPORTED',
    last_error = 'This active job predates durable request payloads and cannot be resumed safely',
    retryable = false,
    completed_at = now(),
    lease_token = NULL,
    lease_expires_at = NULL,
    updated_at = now()
WHERE status IN ('QUEUED', 'PROCESSING', 'RETRYING')
  AND (
      user_id IS NULL
      OR request_payload IS NULL
      OR request_payload = '{}'::jsonb
      OR job_type NOT IN ('RESUME_EXTRACTION', 'ANALYSIS', 'ANSWER_FEEDBACK')
  );

UPDATE ai_interview_app.resumes
SET processing_status = 'READY',
    failure_code = NULL,
    failure_message = NULL,
    updated_at = now()
WHERE normalized_text IS NOT NULL
  AND btrim(normalized_text) <> '';

UPDATE ai_interview_app.resumes resume
SET processing_status = 'PENDING',
    failure_code = NULL,
    failure_message = NULL,
    updated_at = now()
WHERE (resume.normalized_text IS NULL OR btrim(resume.normalized_text) = '')
  AND EXISTS (
      SELECT 1
      FROM ai_interview_app.background_jobs job
      WHERE job.job_type = 'RESUME_EXTRACTION'
        AND job.resource_id = resume.id
        AND job.status IN ('QUEUED', 'PROCESSING', 'RETRYING')
  );

UPDATE ai_interview_app.resumes resume
SET processing_status = 'FAILED',
    failure_code = 'LEGACY_RESUME_UNRESOLVED',
    failure_message = 'The resume has no extracted text and no active extraction job',
    updated_at = now()
WHERE (resume.normalized_text IS NULL OR btrim(resume.normalized_text) = '')
  AND NOT EXISTS (
      SELECT 1
      FROM ai_interview_app.background_jobs job
      WHERE job.job_type = 'RESUME_EXTRACTION'
        AND job.resource_id = resume.id
        AND job.status IN ('QUEUED', 'PROCESSING', 'RETRYING')
  );

ALTER TABLE ai_interview_app.resumes
    ADD CONSTRAINT resumes_processing_status_check
    CHECK (processing_status IN ('PENDING', 'READY', 'FAILED'));

CREATE INDEX IF NOT EXISTS idx_resumes_processing_status
ON ai_interview_app.resumes (user_id, processing_status, created_at DESC);

CREATE TABLE ai_interview_app.background_job_effects (
    job_id uuid NOT NULL REFERENCES ai_interview_app.background_jobs(id) ON DELETE CASCADE,
    effect_type varchar(40) NOT NULL,
    resource_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (job_id, effect_type),
    CONSTRAINT background_job_effect_type_check
        CHECK (effect_type IN ('ASSESSMENT', 'QUESTIONS', 'ANSWER_FEEDBACK'))
);

CREATE INDEX idx_background_job_effects_resource
ON ai_interview_app.background_job_effects (resource_id);
