-- Numbered answers to one practice question (contract §7.5). Status is derived on read: SCORED when feedback
-- exists, otherwise FAILED when the attempt's latest ANSWER_FEEDBACK job failed, otherwise PENDING.
ALTER TABLE ai_interview_app.practice_questions
    ADD CONSTRAINT uq_practice_questions_id_user_id UNIQUE (id, user_id);

CREATE TABLE ai_interview_app.answer_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id uuid NOT NULL,
    user_id uuid NOT NULL,
    number integer NOT NULL,
    text text NOT NULL,
    feedback jsonb,
    score integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_answer_attempts_number CHECK (number >= 1),
    CONSTRAINT ck_answer_attempts_text CHECK (char_length(btrim(text)) > 0 AND char_length(text) <= 4000),
    CONSTRAINT ck_answer_attempts_score CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT ck_answer_attempts_scored CHECK ((feedback IS NULL) = (score IS NULL)),
    CONSTRAINT uq_answer_attempts_question_number UNIQUE (question_id, number),
    CONSTRAINT fk_answer_attempts_question_owner FOREIGN KEY (question_id, user_id)
        REFERENCES ai_interview_app.practice_questions(id, user_id) ON DELETE CASCADE
);

-- Private like the other application tables: only the restricted runtime role reads and writes attempts.
REVOKE ALL ON TABLE ai_interview_app.answer_attempts FROM PUBLIC;

DO $$
DECLARE
    role_name text;
BEGIN
    FOR role_name IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated', 'service_role')
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE ai_interview_app.answer_attempts FROM %I', role_name);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_interview_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ai_interview_app.answer_attempts TO ai_interview_runtime;
    END IF;
END
$$;
