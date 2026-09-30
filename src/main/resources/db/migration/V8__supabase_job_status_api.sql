CREATE SCHEMA IF NOT EXISTS ai_interview_api;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA ai_interview_app, ai_interview_api FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA ai_interview_app FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA ai_interview_app FROM PUBLIC;
REVOKE ALL ON TABLE
    public.app_users, public.resumes, public.job_descriptions, public.resume_assessments,
    public.interview_sessions, public.interview_questions, public.interview_answers,
    public.resume_chunks, public.job_description_chunks, public.question_embeddings,
    public.answer_embeddings, public.vector_store, public.background_jobs, public.flyway_schema_history
FROM PUBLIC;

CREATE VIEW ai_interview_api.job_status WITH (security_invoker = true) AS
SELECT id, user_id, job_type, status, stage, attempts, result_payload, last_error,
       error_code, retryable, created_at, started_at, completed_at, resource_id,
       jsonb_strip_nulls(jsonb_build_object(
           'resumeId', request_payload -> 'resumeId',
           'jobDescriptionId', request_payload -> 'jobDescriptionId'
       )) AS request_payload
FROM ai_interview_app.background_jobs;

REVOKE ALL ON ai_interview_api.job_status FROM PUBLIC;

DO $$
DECLARE
    role_name text;
BEGIN
    FOR role_name IN
        SELECT rolname FROM pg_roles WHERE rolname IN ('anon', 'authenticated', 'service_role')
    LOOP
        EXECUTE format('REVOKE ALL ON SCHEMA ai_interview_app, ai_interview_api FROM %I', role_name);
        EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA ai_interview_app, ai_interview_api FROM %I', role_name);
        EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA ai_interview_app FROM %I', role_name);
        EXECUTE format('REVOKE ALL ON TABLE
            public.app_users, public.resumes, public.job_descriptions, public.resume_assessments,
            public.interview_sessions, public.interview_questions, public.interview_answers,
            public.resume_chunks, public.job_description_chunks, public.question_embeddings,
            public.answer_embeddings, public.vector_store, public.background_jobs, public.flyway_schema_history FROM %I', role_name);
    END LOOP;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
        GRANT USAGE ON SCHEMA ai_interview_app, ai_interview_api TO service_role;
        GRANT SELECT (id, user_id, job_type, status, stage, attempts, result_payload, last_error,
                      error_code, retryable, created_at, started_at, completed_at, resource_id, request_payload)
            ON ai_interview_app.background_jobs TO service_role;
        GRANT SELECT ON ai_interview_api.job_status TO service_role;
    END IF;
END
$$;
