CREATE TABLE ai_interview_app.resume_scores (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    resume_id uuid NOT NULL REFERENCES ai_interview_app.resumes(id) ON DELETE CASCADE,
    job_title varchar(100),
    overall integer NOT NULL CHECK (overall BETWEEN 0 AND 100),
    result jsonb NOT NULL,
    scored_at timestamptz NOT NULL
);

CREATE INDEX idx_resume_scores_resume_scored
ON ai_interview_app.resume_scores (resume_id, scored_at DESC, id DESC);

CREATE INDEX idx_resume_scores_user_scored
ON ai_interview_app.resume_scores (user_id, scored_at);

ALTER TABLE ai_interview_app.background_job_effects
    DROP CONSTRAINT background_job_effect_type_check,
    ADD CONSTRAINT background_job_effect_type_check
        CHECK (effect_type IN ('ASSESSMENT', 'QUESTIONS', 'ANSWER_FEEDBACK', 'RESUME_SCORE'));

-- Private like the other application tables: only the restricted runtime role reads and writes scores.
REVOKE ALL ON TABLE ai_interview_app.resume_scores FROM PUBLIC;

DO $$
DECLARE
    role_name text;
BEGIN
    FOR role_name IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated', 'service_role')
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE ai_interview_app.resume_scores FROM %I', role_name);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_interview_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ai_interview_app.resume_scores TO ai_interview_runtime;
    END IF;
END
$$;
