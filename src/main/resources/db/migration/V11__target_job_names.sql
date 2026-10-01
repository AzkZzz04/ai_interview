ALTER TABLE ai_interview_app.job_descriptions
    ADD COLUMN name varchar(80),
    ADD COLUMN updated_at timestamptz;

UPDATE ai_interview_app.job_descriptions
SET name = COALESCE(NULLIF(LEFT(BTRIM(title), 80), ''), 'Untitled job'),
    updated_at = created_at
WHERE name IS NULL OR updated_at IS NULL;

ALTER TABLE ai_interview_app.job_descriptions
    ALTER COLUMN name SET DEFAULT 'Untitled job',
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN updated_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET NOT NULL;
