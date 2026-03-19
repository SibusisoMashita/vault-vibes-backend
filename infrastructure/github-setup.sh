#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# GitHub Environments, Variables, and Secrets Setup
#
# Run AFTER terraform apply (dev) completes so you can paste the outputs.
# Prerequisites: gh CLI authenticated, jq installed
# Usage: ./github-setup.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

BACKEND_REPO="SibusisoMashita/vault-vibes-backend"
FRONTEND_REPO="SibusisoMashita/vault-vibes-frontend"

# ─── Paste Terraform outputs here ─────────────────────────────────────────────
# Run: cd infrastructure/terraform/dev && terraform output
# Then fill in the values below.

DEV_COGNITO_USER_POOL_ID="us-east-1_gqGUV6CKU"
DEV_COGNITO_API_CLIENT_ID="1kvrbhp3vegeo5q7dvltdog8as"
DEV_COGNITO_SPA_CLIENT_ID="28lsmakalo98o9p1uv3piho3pq"
DEV_CLOUDFRONT_DIST_ID="E3BEWJ9GRXPL65"
DEV_GITHUB_BACKEND_ROLE="arn:aws:iam::861870144419:role/vaultvibes-dev-github-deploy-role"
DEV_GITHUB_FRONTEND_ROLE="arn:aws:iam::861870144419:role/vaultvibes-dev-frontend-github-deploy-role"

PROD_CLOUDFRONT_DIST_ID="E2LTR00BB2MIAY"
PROD_GITHUB_BACKEND_ROLE="arn:aws:iam::861870144419:role/vaultvibes-prod-github-deploy-role"
PROD_COGNITO_USER_POOL_ID="us-east-1_Pmg4WjBdm"
PROD_COGNITO_API_CLIENT_ID="3qsigtvtgu0jm2h0qdb2cthfh2"
PROD_COGNITO_SPA_CLIENT_ID="79kvqh819qcj0kojp3560jvj1"

# ─── 1. Create GitHub Environments ────────────────────────────────────────────

echo "==> Creating GitHub environments..."

gh api --method PUT "repos/$BACKEND_REPO/environments/dev" \
  --field "wait_timer=0" >/dev/null
gh api --method PUT "repos/$BACKEND_REPO/environments/prod" \
  --field "wait_timer=0" \
  --field 'reviewers=[{"type":"User","id":0}]' >/dev/null 2>&1 || \
  gh api --method PUT "repos/$BACKEND_REPO/environments/prod" >/dev/null

gh api --method PUT "repos/$FRONTEND_REPO/environments/dev" \
  --field "wait_timer=0" >/dev/null
gh api --method PUT "repos/$FRONTEND_REPO/environments/prod" >/dev/null

echo "    Environments created."

# ─── 2. Backend — DEV Variables ───────────────────────────────────────────────

echo "==> Setting backend DEV variables..."

gh variable set AWS_GITHUB_DEPLOY_ROLE_ARN \
  --env dev --repo "$BACKEND_REPO" \
  --body "$DEV_GITHUB_BACKEND_ROLE"

gh variable set DEV_COGNITO_USER_POOL_ID \
  --env dev --repo "$BACKEND_REPO" \
  --body "$DEV_COGNITO_USER_POOL_ID"

gh variable set DEV_COGNITO_API_CLIENT_ID \
  --env dev --repo "$BACKEND_REPO" \
  --body "$DEV_COGNITO_API_CLIENT_ID"

# ─── 3. Backend — PROD Variables ──────────────────────────────────────────────

echo "==> Setting backend PROD variables..."

gh variable set AWS_GITHUB_DEPLOY_ROLE_ARN \
  --env prod --repo "$BACKEND_REPO" \
  --body "$PROD_GITHUB_BACKEND_ROLE"

# ─── 4. Backend — PROD Secrets (already set; verify they exist) ───────────────

echo "==> Verifying backend PROD secrets exist..."
for secret in AWS_GITHUB_DEPLOY_ROLE_ARN ECS_TASK_EXECUTION_ROLE_ARN ECS_TASK_ROLE_ARN; do
  if gh secret list --env prod --repo "$BACKEND_REPO" | grep -q "$secret"; then
    echo "    $secret: present"
  else
    echo "    WARNING: $secret is missing from prod environment — set it manually"
  fi
done

# ─── 5. Frontend — DEV Variables ──────────────────────────────────────────────

echo "==> Setting frontend DEV variables..."

gh variable set AWS_ROLE_TO_ASSUME \
  --env dev --repo "$FRONTEND_REPO" \
  --body "$DEV_GITHUB_FRONTEND_ROLE"

gh variable set S3_BUCKET \
  --env dev --repo "$FRONTEND_REPO" \
  --body "vaultvibes-dev-frontend"

gh variable set CLOUDFRONT_DISTRIBUTION_ID \
  --env dev --repo "$FRONTEND_REPO" \
  --body "$DEV_CLOUDFRONT_DIST_ID"

gh variable set VITE_API_BASE_URL \
  --env dev --repo "$FRONTEND_REPO" \
  --body "https://dev-api.vaultvibes.co.za/api"

gh variable set DEV_COGNITO_USER_POOL_ID \
  --env dev --repo "$FRONTEND_REPO" \
  --body "$DEV_COGNITO_USER_POOL_ID"

gh variable set DEV_COGNITO_SPA_CLIENT_ID \
  --env dev --repo "$FRONTEND_REPO" \
  --body "$DEV_COGNITO_SPA_CLIENT_ID"

gh variable set DEV_COGNITO_DOMAIN \
  --env dev --repo "$FRONTEND_REPO" \
  --body "vaultvibes-dev-auth.auth.us-east-1.amazoncognito.com"

# ─── 6. Frontend — PROD Variables ─────────────────────────────────────────────

echo "==> Setting frontend PROD variables..."

# AWS_ROLE_TO_ASSUME is a secret in prod; keep it as secret
gh variable set S3_BUCKET \
  --env prod --repo "$FRONTEND_REPO" \
  --body "vaultvibes-frontend"

gh variable set CLOUDFRONT_DISTRIBUTION_ID \
  --env prod --repo "$FRONTEND_REPO" \
  --body "$PROD_CLOUDFRONT_DIST_ID"

gh variable set VITE_API_BASE_URL \
  --env prod --repo "$FRONTEND_REPO" \
  --body "https://api.vaultvibes.co.za/api"

gh variable set PROD_COGNITO_USER_POOL_ID \
  --env prod --repo "$FRONTEND_REPO" \
  --body "$PROD_COGNITO_USER_POOL_ID"

gh variable set PROD_COGNITO_SPA_CLIENT_ID \
  --env prod --repo "$FRONTEND_REPO" \
  --body "$PROD_COGNITO_SPA_CLIENT_ID"

gh variable set PROD_COGNITO_DOMAIN \
  --env prod --repo "$FRONTEND_REPO" \
  --body "vaultvibes-auth.auth.us-east-1.amazoncognito.com"

echo ""
echo "==> GitHub setup complete."
echo ""
echo "REMAINING MANUAL STEPS:"
echo "  1. Add required reviewers to the 'prod' environment in GitHub Settings"
echo "     → https://github.com/$BACKEND_REPO/settings/environments"
echo "     → https://github.com/$FRONTEND_REPO/settings/environments"
echo ""
echo "  2. Set the following SECRETS (not variables) on prod environments:"
echo "     Backend prod: AWS_GITHUB_DEPLOY_ROLE_ARN, ECS_TASK_EXECUTION_ROLE_ARN, ECS_TASK_ROLE_ARN"
echo "     Frontend prod: AWS_ROLE_TO_ASSUME, CLOUDFRONT_DOMAIN_NAME"
echo ""
echo "  3. Set DEV DB password in Secrets Manager:"
echo "     aws secretsmanager put-secret-value \\"
echo "       --secret-id vaultvibes/dev/backend/db \\"
echo "       --secret-string '{\"DB_URL\":\"...\",\"DB_USERNAME\":\"vaultvibes_dev_app\",\"DB_PASSWORD\":\"<secure>\"}'"
