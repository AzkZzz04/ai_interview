-- U13: remove the legacy analysis and interview flow. Stop legacy submissions and consumers before applying.
-- Attempt-backed ANSWER_FEEDBACK jobs (resource_type 'attempt') and their effects stay.
-- Effects of the deleted jobs go with them through background_job_effects' ON DELETE CASCADE.
DELETE FROM ai_interview_app.background_jobs
WHERE job_type = 'ANALYSIS'
   OR (job_type = 'ANSWER_FEEDBACK' AND resource_type IS DISTINCT FROM 'attempt');

ALTER TABLE ai_interview_app.background_job_effects
    DROP CONSTRAINT background_job_effect_type_check,
    ADD CONSTRAINT background_job_effect_type_check
        CHECK (effect_type IN ('ANSWER_FEEDBACK', 'RESUME_SCORE', 'JOB_FIT', 'EXPERIENCE_SUGGESTIONS', 'PRACTICE_QUESTIONS'));

-- The embedding tables reference the interview tables, so they go first. Only the private application copies are
-- dropped; the legacy public.* tables and public.vector_store are untouched.
DROP TABLE ai_interview_app.question_embeddings, ai_interview_app.answer_embeddings;
DROP TABLE ai_interview_app.interview_answers, ai_interview_app.interview_questions, ai_interview_app.interview_sessions,
    ai_interview_app.resume_assessments;
