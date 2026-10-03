ALTER TABLE ai_interview_app.resumes
    ADD COLUMN name varchar(80),
    ADD COLUMN job_title varchar(100),
    ADD COLUMN source varchar(16),
    ADD COLUMN file_hash varchar(64);

UPDATE ai_interview_app.resumes
SET source = CASE
        WHEN storage_key IS NULL AND original_filename = 'pasted-resume.txt' THEN 'PASTE'
        ELSE 'UPLOAD'
    END,
    name = LEFT(
        COALESCE(NULLIF(BTRIM(regexp_replace(original_filename, '\.[^.]*$', '')), ''), 'Untitled resume'),
        80
    );

UPDATE ai_interview_app.resumes
SET original_filename = NULL
WHERE source = 'PASTE' AND storage_key IS NULL AND original_filename = 'pasted-resume.txt';

ALTER TABLE ai_interview_app.resumes
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN source SET NOT NULL,
    ADD CONSTRAINT ck_resumes_source CHECK (source IN ('UPLOAD', 'PASTE'));

CREATE UNIQUE INDEX uq_resumes_user_file_hash
    ON ai_interview_app.resumes (user_id, file_hash)
    WHERE file_hash IS NOT NULL;

CREATE INDEX idx_resumes_user_created
    ON ai_interview_app.resumes (user_id, created_at DESC);

CREATE INDEX idx_resume_extraction_jobs_resource
    ON ai_interview_app.background_jobs (user_id, resource_type, resource_id, created_at DESC)
    WHERE job_type = 'RESUME_EXTRACTION';

CREATE TABLE ai_interview_app.storage_cleanup (
    storage_key varchar(512) PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_storage_cleanup_created
    ON ai_interview_app.storage_cleanup (created_at);

REVOKE ALL ON ai_interview_app.storage_cleanup FROM PUBLIC;

DO $$
DECLARE
    role_name text;
BEGIN
    FOR role_name IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated', 'service_role')
    LOOP
        EXECUTE format('REVOKE ALL ON ai_interview_app.storage_cleanup FROM %I', role_name);
    END LOOP;
END
$$;
