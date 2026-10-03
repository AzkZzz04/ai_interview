CREATE OR REPLACE VIEW ai_interview_api.job_status WITH (security_invoker = true) AS
SELECT id, user_id, job_type, status, stage, attempts, result_payload, last_error,
       error_code, retryable, created_at, started_at, completed_at, resource_id,
       jsonb_strip_nulls(jsonb_build_object(
           'resumeId', request_payload -> 'resumeId',
           'targetJobId', request_payload -> 'targetJobId',
           'jobDescriptionId', request_payload -> 'jobDescriptionId',
           'practiceSetId', request_payload -> 'practiceSetId',
           'attemptId', request_payload -> 'attemptId'
       )) AS request_payload,
       max_attempts, resource_type
FROM ai_interview_app.background_jobs;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'service_role') THEN
        GRANT SELECT (max_attempts, resource_type) ON ai_interview_app.background_jobs TO service_role;
        GRANT SELECT ON ai_interview_api.job_status TO service_role;
    END IF;
END
$$;
