#!/usr/bin/env bash
# Run the API locally against the AWS account configured in backend/.env.
#   cd backend && cp .env.example .env   # fill it in, then:
#   scripts/run_local.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -f .env ]; then
  echo "backend/.env not found. Copy .env.example to .env and fill it in." >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
source .env
set +a

: "${S3_BUCKET:?Set S3_BUCKET in backend/.env}"
if [ "${CATALOG_BACKEND:-dynamodb}" != "local" ]; then
  : "${DYNAMODB_TABLE:?Set DYNAMODB_TABLE in backend/.env (or CATALOG_BACKEND=local to run without DynamoDB)}"
fi

# shellcheck disable=SC1091
source .venv/bin/activate
exec uvicorn app.main:app --reload --host 0.0.0.0 --port "${PORT:-8000}"
