-- One practice set per resume and target job pair (contract §11.11). Status is derived on read from its
-- AI questions and latest PRACTICE_QUESTIONS job, so it is not stored.
CREATE TABLE ai_interview_app.practice_sets (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    resume_id uuid NOT NULL,
    target_job_id uuid NOT NULL,
    mode varchar(16) NOT NULL DEFAULT 'PRACTICE',
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_practice_sets_user_resume_target UNIQUE (user_id, resume_id, target_job_id),
    CONSTRAINT uq_practice_sets_id_user_id UNIQUE (id, user_id),
    CONSTRAINT ck_practice_sets_mode CHECK (mode = 'PRACTICE'),
    CONSTRAINT fk_practice_sets_resume_owner FOREIGN KEY (resume_id, user_id)
        REFERENCES ai_interview_app.resumes(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_practice_sets_target_job_owner FOREIGN KEY (target_job_id, user_id)
        REFERENCES ai_interview_app.job_descriptions(id, user_id) ON DELETE CASCADE
);

CREATE INDEX idx_practice_sets_resume_owner ON ai_interview_app.practice_sets (resume_id, user_id);
CREATE INDEX idx_practice_sets_target_job_owner ON ai_interview_app.practice_sets (target_job_id, user_id);

-- AI questions are listed first, then user questions; order_index orders each origin, so a set can never
-- receive a second copy of the same AI question.
CREATE TABLE ai_interview_app.practice_questions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    practice_set_id uuid NOT NULL,
    user_id uuid NOT NULL,
    origin varchar(4) NOT NULL,
    order_index integer NOT NULL,
    text text NOT NULL,
    rationale text,
    category text,
    expected_signals jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_practice_questions_origin CHECK (origin IN ('AI', 'USER')),
    CONSTRAINT ck_practice_questions_order_index CHECK (order_index >= 1),
    CONSTRAINT ck_practice_questions_text CHECK (char_length(btrim(text)) > 0),
    CONSTRAINT ck_practice_questions_signals_array CHECK (jsonb_typeof(expected_signals) = 'array'),
    CONSTRAINT uq_practice_questions_set_origin_order UNIQUE (practice_set_id, origin, order_index),
    CONSTRAINT fk_practice_questions_set_owner FOREIGN KEY (practice_set_id, user_id)
        REFERENCES ai_interview_app.practice_sets(id, user_id) ON DELETE CASCADE
);

ALTER TABLE ai_interview_app.background_job_effects
    DROP CONSTRAINT background_job_effect_type_check;

ALTER TABLE ai_interview_app.background_job_effects
    ADD CONSTRAINT background_job_effect_type_check
        CHECK (effect_type IN ('ASSESSMENT', 'QUESTIONS', 'ANSWER_FEEDBACK', 'RESUME_SCORE', 'JOB_FIT', 'EXPERIENCE_SUGGESTIONS', 'PRACTICE_QUESTIONS'));

-- Private like the other application tables: only the restricted runtime role reads and writes practice data.
REVOKE ALL ON TABLE ai_interview_app.practice_sets FROM PUBLIC;
REVOKE ALL ON TABLE ai_interview_app.practice_questions FROM PUBLIC;

DO $$
DECLARE
    role_name text;
BEGIN
    FOR role_name IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated', 'service_role')
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE ai_interview_app.practice_sets FROM %I', role_name);
        EXECUTE format('REVOKE ALL ON TABLE ai_interview_app.practice_questions FROM %I', role_name);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_interview_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ai_interview_app.practice_sets TO ai_interview_runtime;
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ai_interview_app.practice_questions TO ai_interview_runtime;
    END IF;
END
$$;
