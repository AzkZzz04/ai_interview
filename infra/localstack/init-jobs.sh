#!/bin/sh
set -eu

DLQ_NAME="${SQS_DLQ_NAME:-ai-interview-jobs-dlq}"
QUEUE_NAME="${SQS_QUEUE_NAME:-ai-interview-jobs}"
MAX_RECEIVE_COUNT="${SQS_MAX_RECEIVE_COUNT:-3}"
VISIBILITY_TIMEOUT="${JOB_VISIBILITY_TIMEOUT_SECONDS:-300}"
LONG_POLL_SECONDS="${JOB_LONG_POLL_SECONDS:-20}"

if ! DLQ_URL="$(awslocal sqs get-queue-url --queue-name "$DLQ_NAME" --query QueueUrl --output text 2>/dev/null)"; then
  DLQ_URL="$(awslocal sqs create-queue --queue-name "$DLQ_NAME" --query QueueUrl --output text)"
fi
DLQ_ARN="$(awslocal sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names QueueArn \
  --query 'Attributes.QueueArn' \
  --output text)"

QUEUE_ATTRIBUTES="{\"VisibilityTimeout\":\"$VISIBILITY_TIMEOUT\",\"ReceiveMessageWaitTimeSeconds\":\"$LONG_POLL_SECONDS\",\"RedrivePolicy\":\"{\\\"deadLetterTargetArn\\\":\\\"$DLQ_ARN\\\",\\\"maxReceiveCount\\\":\\\"$MAX_RECEIVE_COUNT\\\"}\"}"

if ! QUEUE_URL="$(awslocal sqs get-queue-url --queue-name "$QUEUE_NAME" --query QueueUrl --output text 2>/dev/null)"; then
  QUEUE_URL="$(awslocal sqs create-queue --queue-name "$QUEUE_NAME" --query QueueUrl --output text)"
fi

awslocal sqs set-queue-attributes --queue-url "$QUEUE_URL" --attributes "$QUEUE_ATTRIBUTES"
