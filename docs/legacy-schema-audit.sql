-- Read-only inventory for deciding whether legacy public-schema objects can be
-- removed in a later migration. This script intentionally performs no writes.

-- Table inventory and PostgreSQL row estimates. Run ANALYZE separately first if
-- current estimates are required; this audit itself remains read-only.
SELECT
    schemaname AS schema_name,
    relname AS table_name,
    n_live_tup AS estimated_rows,
    n_dead_tup AS estimated_dead_rows,
    last_analyze,
    last_autoanalyze
FROM pg_stat_user_tables
WHERE schemaname IN ('public', 'ai_interview_app')
ORDER BY schemaname, relname;

-- Foreign keys and their delete behavior.
SELECT
    tc.table_schema,
    tc.table_name,
    tc.constraint_name,
    kcu.column_name,
    ccu.table_schema AS referenced_schema,
    ccu.table_name AS referenced_table,
    ccu.column_name AS referenced_column,
    rc.delete_rule
FROM information_schema.table_constraints tc
JOIN information_schema.key_column_usage kcu
  ON kcu.constraint_schema = tc.constraint_schema
 AND kcu.constraint_name = tc.constraint_name
JOIN information_schema.constraint_column_usage ccu
  ON ccu.constraint_schema = tc.constraint_schema
 AND ccu.constraint_name = tc.constraint_name
JOIN information_schema.referential_constraints rc
  ON rc.constraint_schema = tc.constraint_schema
 AND rc.constraint_name = tc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
  AND tc.table_schema IN ('public', 'ai_interview_app')
ORDER BY tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position;

-- Index definitions, including legacy pgvector indexes.
SELECT schemaname AS schema_name, tablename AS table_name, indexname, indexdef
FROM pg_indexes
WHERE schemaname IN ('public', 'ai_interview_app')
ORDER BY schemaname, tablename, indexname;

-- Legacy/current vector metadata distribution. No resume/JD content is selected.
SELECT
    COALESCE(metadata ->> 'sourceType', '<missing>') AS source_type,
    COALESCE(metadata ->> 'indexId', '<legacy>') AS index_id,
    COALESCE(metadata ->> 'claimVersion', '<legacy>') AS claim_version,
    COALESCE(metadata ->> 'corpusId', '<none>') AS legacy_corpus_id,
    count(*) AS vector_count
FROM public.vector_store
GROUP BY 1, 2, 3, 4
ORDER BY vector_count DESC, source_type, index_id;
