#!/bin/sh
set -eu

ROOT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
ENV_FILE="$ROOT_DIR/.env"

fail() {
	echo "reset-local-environment: $*" >&2
	exit 1
}

[ -f "$ENV_FILE" ] || fail "Missing .env. Copy .env.example and configure the local database first."
set -a
. "$ENV_FILE"
set +a

[ "${RESET_CONFIRM:-}" = "DROP_INTERVIEW_GUIDE" ] || fail "Set RESET_CONFIRM=DROP_INTERVIEW_GUIDE to continue."
[ "${RESET_DATABASE_NAME:-}" = "interview_guide" ] || fail "RESET_DATABASE_NAME must be interview_guide."
[ "${RESET_DATABASE_OWNER:-}" = "ai_interview" ] || fail "RESET_DATABASE_OWNER must be ai_interview."
[ -n "${RESET_DATABASE_ADMIN_URL:-}" ] || fail "RESET_DATABASE_ADMIN_URL is required."
[ "${S3_BUCKET:-}" = "ai-interview" ] || fail "S3_BUCKET must be ai-interview."
[ "${SQS_QUEUE_NAME:-}" = "ai-interview-jobs" ] || fail "SQS_QUEUE_NAME must be ai-interview-jobs."
[ "${SQS_DLQ_NAME:-}" = "ai-interview-jobs-dlq" ] || fail "SQS_DLQ_NAME must be ai-interview-jobs-dlq."

case "${DATABASE_URL:-}" in
	jdbc:postgresql://localhost:5432/interview_guide|jdbc:postgresql://127.0.0.1:5432/interview_guide)
		;;
	*)
		fail "DATABASE_URL must target localhost:5432/interview_guide exactly."
		;;
esac

case "$RESET_DATABASE_ADMIN_URL" in
	postgresql://localhost:5432/postgres*|postgresql://127.0.0.1:5432/postgres*)
		;;
	*)
		fail "RESET_DATABASE_ADMIN_URL must target localhost:5432/postgres."
		;;
esac

[ "$(psql "$RESET_DATABASE_ADMIN_URL" -Atqc 'select current_database()')" = "postgres" ] \
	|| fail "RESET_DATABASE_ADMIN_URL must connect to the postgres maintenance database."

command -v docker >/dev/null 2>&1 || fail "docker is required to reset LocalStack."
docker compose -f "$ROOT_DIR/docker-compose.yml" ps localstack --status running | grep -q localstack \
	|| fail "LocalStack must be running before reset."

echo "Resetting local interview_guide database and ai-interview LocalStack resources."
psql "$RESET_DATABASE_ADMIN_URL" -v ON_ERROR_STOP=1 -v database_name=interview_guide <<'SQL'
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = :'database_name' AND pid <> pg_backend_pid();
DROP DATABASE interview_guide;
CREATE DATABASE interview_guide OWNER ai_interview;
SQL

docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T localstack \
	awslocal s3 rb "s3://$S3_BUCKET" --force >/dev/null 2>&1 || true
docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T localstack \
	awslocal s3 mb "s3://$S3_BUCKET" >/dev/null

for queue_name in "$SQS_QUEUE_NAME" "$SQS_DLQ_NAME"; do
	queue_url=$(docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T localstack \
		awslocal sqs get-queue-url --queue-name "$queue_name" --query QueueUrl --output text 2>/dev/null || true)
	if [ -n "$queue_url" ]; then
		docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T localstack \
			awslocal sqs delete-queue --queue-url "$queue_url" >/dev/null
	fi
done
docker compose -f "$ROOT_DIR/docker-compose.yml" exec -T localstack \
	sh /etc/localstack/init/ready.d/init-jobs.sh >/dev/null

echo "Reset complete. Start the backend to run Flyway and recreate application state."
