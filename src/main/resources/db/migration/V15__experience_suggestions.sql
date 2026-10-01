-- One suggestion set per resume and target job pair. source_ids is the sorted snapshot of the source
-- resume and experience IDs the stored result was built from; staleness is derived from it on read.
CREATE TABLE ai_interview_app.experience_suggestions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    resume_id uuid NOT NULL,
    target_job_id uuid NOT NULL,
    result_payload jsonb,
    source_ids jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    result_created_at timestamptz,
    CONSTRAINT uq_experience_suggestions_user_resume_target UNIQUE (user_id, resume_id, target_job_id),
    CONSTRAINT ck_experience_suggestions_result_object CHECK (result_payload IS NULL OR jsonb_typeof(result_payload) = 'object'),
    CONSTRAINT ck_experience_suggestions_source_ids_array CHECK (source_ids IS NULL OR jsonb_typeof(source_ids) = 'array'),
    CONSTRAINT ck_experience_suggestions_result_has_sources CHECK ((result_payload IS NULL) = (source_ids IS NULL)),
    CONSTRAINT fk_experience_suggestions_resume_owner FOREIGN KEY (resume_id, user_id)
        REFERENCES ai_interview_app.resumes(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_experience_suggestions_target_job_owner FOREIGN KEY (target_job_id, user_id)
        REFERENCES ai_interview_app.job_descriptions(id, user_id) ON DELETE CASCADE
);

CREATE INDEX idx_experience_suggestions_resume_owner ON ai_interview_app.experience_suggestions (resume_id, user_id);
CREATE INDEX idx_experience_suggestions_target_job_owner ON ai_interview_app.experience_suggestions (target_job_id, user_id);

ALTER TABLE ai_interview_app.background_job_effects
    DROP CONSTRAINT background_job_effect_type_check;

ALTER TABLE ai_interview_app.background_job_effects
    ADD CONSTRAINT background_job_effect_type_check
        CHECK (effect_type IN ('ASSESSMENT', 'QUESTIONS', 'ANSWER_FEEDBACK', 'RESUME_SCORE', 'JOB_FIT', 'EXPERIENCE_SUGGESTIONS'));

-- Private like the other application tables: only the restricted runtime role reads and writes suggestions.
REVOKE ALL ON TABLE ai_interview_app.experience_suggestions FROM PUBLIC;

DO $$
DECLARE
    role_name text;
BEGIN
    FOR role_name IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated', 'service_role')
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE ai_interview_app.experience_suggestions FROM %I', role_name);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_interview_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ai_interview_app.experience_suggestions TO ai_interview_runtime;
    END IF;
END
$$;
