CREATE TABLE ai_interview_app.experiences (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES ai_interview_app.app_users(id) ON DELETE CASCADE,
    title varchar(120) NOT NULL,
    organization varchar(120),
    start_date varchar(7),
    end_date varchar(7),
    description text NOT NULL,
    source varchar(12) NOT NULL,
    content_hash varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT experiences_source_check CHECK (source IN ('FORM', 'LINKEDIN')),
    CONSTRAINT experiences_start_date_check CHECK (start_date IS NULL OR start_date ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT experiences_end_date_check CHECK (end_date IS NULL OR end_date ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT experiences_title_check CHECK (char_length(btrim(title)) BETWEEN 1 AND 120),
    CONSTRAINT experiences_description_check CHECK (char_length(btrim(description)) BETWEEN 1 AND 4000),
    CONSTRAINT experiences_user_content_hash_unique UNIQUE (user_id, content_hash)
);

CREATE INDEX idx_experiences_user_created
ON ai_interview_app.experiences (user_id, created_at DESC);

-- Keep this table private from Supabase's Data API roles. The application connects through its
-- restricted runtime role and never exposes the experience table as a PostgREST resource.
REVOKE ALL ON TABLE ai_interview_app.experiences FROM PUBLIC;

DO $$
DECLARE
    role_name text;
BEGIN
    FOR role_name IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated', 'service_role')
    LOOP
        EXECUTE format('REVOKE ALL ON TABLE ai_interview_app.experiences FROM %I', role_name);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ai_interview_runtime') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE ai_interview_app.experiences TO ai_interview_runtime;
    END IF;
END
$$;
