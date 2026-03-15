#!/usr/bin/env bash
set -euo pipefail

AWS_REGION="${AWS_REGION:-us-east-1}"
BACKEND_ROLE_NAME="${BACKEND_ROLE_NAME:-}"
DB_SECRET_NAME="${DB_SECRET_NAME:-vaultvibes/prod/db}"
APP_SECRET_NAME="${APP_SECRET_NAME:-vaultvibes/prod/app}"
S3_BUCKET_NAME="${S3_BUCKET_NAME:-vault-vibes-uploads}"

# Keep output non-interactive for CI and shells with pagers configured.
AWS_PAGER=""
export AWS_PAGER

echo "[1/5] Listing available secrets..."
aws --region "$AWS_REGION" secretsmanager list-secrets --query "SecretList[].Name" --output table

echo "[2/5] Validating DB secret JSON keys..."
DB_SECRET_JSON=$(aws --region "$AWS_REGION" secretsmanager get-secret-value --secret-id "$DB_SECRET_NAME" --query SecretString --output text)
python3 - <<'PY' "$DB_SECRET_JSON"
import json
import sys
payload = json.loads(sys.argv[1])
required = {"jdbcUrl", "username", "password"}
missing = sorted(required - set(payload))
if missing:
    raise SystemExit(f"Missing DB keys: {', '.join(missing)}")
print("DB secret keys OK")
PY

echo "[3/5] Validating app secret JSON keys..."
APP_SECRET_JSON=$(aws --region "$AWS_REGION" secretsmanager get-secret-value --secret-id "$APP_SECRET_NAME" --query SecretString --output text)
python3 - <<'PY' "$APP_SECRET_JSON"
import json
import sys
payload = json.loads(sys.argv[1])
required = {"springProfilesActive", "jwtSecret", "corsAllowedOrigins"}
missing = sorted(required - set(payload))
if missing:
    raise SystemExit(f"Missing app keys: {', '.join(missing)}")
print("App secret keys OK")
PY

if [[ -z "$BACKEND_ROLE_NAME" ]]; then
  if aws --region "$AWS_REGION" iam get-role --role-name vault-backend-role >/dev/null 2>&1; then
    BACKEND_ROLE_NAME="vault-backend-role"
  elif aws --region "$AWS_REGION" iam get-role --role-name vaultvibes-prod-ec2-role >/dev/null 2>&1; then
    BACKEND_ROLE_NAME="vaultvibes-prod-ec2-role"
  else
    echo "No default runtime role found. Set BACKEND_ROLE_NAME and rerun." >&2
    exit 1
  fi
fi

echo "[4/5] Applying least-privilege runtime policy to role $BACKEND_ROLE_NAME..."
read -r -d '' POLICY_DOC <<EOF || true
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadProdAppSecrets",
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue",
        "secretsmanager:DescribeSecret"
      ],
      "Resource": [
        "arn:aws:secretsmanager:${AWS_REGION}:*:secret:${DB_SECRET_NAME}*",
        "arn:aws:secretsmanager:${AWS_REGION}:*:secret:${APP_SECRET_NAME}*"
      ]
    },
    {
      "Sid": "ReadWriteUploadsBucket",
      "Effect": "Allow",
      "Action": [
        "s3:ListBucket",
        "s3:GetObject",
        "s3:PutObject"
      ],
      "Resource": [
        "arn:aws:s3:::${S3_BUCKET_NAME}",
        "arn:aws:s3:::${S3_BUCKET_NAME}/*"
      ]
    }
  ]
}
EOF

aws --region "$AWS_REGION" iam put-role-policy \
  --role-name "$BACKEND_ROLE_NAME" \
  --policy-name vaultvibes-prod-runtime-secrets-s3 \
  --policy-document "$POLICY_DOC"

echo "[5/5] Verifying S3 bucket location..."
aws --region "$AWS_REGION" s3api get-bucket-location --bucket "$S3_BUCKET_NAME"

echo "Done. Runtime role has inline policy for Secrets Manager and S3 bucket access."

