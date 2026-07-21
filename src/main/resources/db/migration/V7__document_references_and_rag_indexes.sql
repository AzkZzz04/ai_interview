ALTER TABLE ai_interview_app.resumes
    ADD COLUMN IF NOT EXISTS content_hash varchar(64);

ALTER TABLE ai_interview_app.job_descriptions
    ADD COLUMN IF NOT EXISTS content_hash varchar(64);

UPDATE ai_interview_app.resumes
SET content_hash = encode(digest(convert_to(normalized_text, 'UTF8'), 'sha256'), 'hex')
WHERE normalized_text IS NOT NULL
  AND content_hash IS NULL;

UPDATE ai_interview_app.job_descriptions
SET content_hash = encode(digest(convert_to(normalized_text, 'UTF8'), 'sha256'), 'hex')
WHERE normalized_text IS NOT NULL
  AND content_hash IS NULL;

CREATE INDEX IF NOT EXISTS idx_resumes_user_content_hash
ON ai_interview_app.resumes (user_id, content_hash);

CREATE INDEX IF NOT EXISTS idx_job_descriptions_user_content_hash
ON ai_interview_app.job_descriptions (user_id, content_hash);

CREATE TABLE IF NOT EXISTS ai_interview_app.rag_document_indexes (
    index_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type varchar(40) NOT NULL,
    content_hash varchar(64) NOT NULL,
    embedding_model varchar(160) NOT NULL,
    embedding_dimensions integer NOT NULL,
    chunk_schema varchar(80) NOT NULL,
    status varchar(20) NOT NULL,
    claim_version bigint NOT NULL DEFAULT 1,
    indexing_started_at timestamptz,
    document_count integer NOT NULL DEFAULT 0,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_used_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT rag_document_indexes_source_type_check
        CHECK (source_type IN ('RESUME', 'JOB_DESCRIPTION')),
    CONSTRAINT rag_document_indexes_status_check
        CHECK (status IN ('INDEXING', 'READY', 'FAILED', 'DELETING')),
    CONSTRAINT rag_document_indexes_dimensions_check
        CHECK (embedding_dimensions > 0),
    CONSTRAINT rag_document_indexes_document_count_check
        CHECK (document_count >= 0),
    CONSTRAINT rag_document_indexes_claim_version_check
        CHECK (claim_version > 0),
    CONSTRAINT uq_rag_document_index_identity
        UNIQUE (source_type, content_hash, embedding_model, embedding_dimensions, chunk_schema)
);

CREATE INDEX IF NOT EXISTS idx_rag_document_indexes_cleanup
ON ai_interview_app.rag_document_indexes (status, last_used_at);
